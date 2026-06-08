"""
Backward Causal Slicing Engine for Counterexample Analysis.

Performs backward slicing from counterexample endpoint to build
a causal DAG with counterfactual evaluation at expression level.
"""

import re
import hashlib
from dataclasses import dataclass, field
from typing import Dict, List, Set, Tuple, Optional, Any
from enum import Enum

from .verilog_parser import VerilogParser, Dependency, DependencyType
from .cycle_waveform import CycleAlignedWaveform, parse_binary_value, invert_value, values_differ


class ContributionType(Enum):
    """Type of causal contribution."""
    EXPR_EVAL = "expr_eval"        # Expression evaluation verified causality
    TOGGLE = "toggle"              # Signal toggle correlation
    STATE = "state"                # State machine transition
    DIRECT = "direct"              # Direct assignment
    CONDITIONAL = "conditional"    # Condition branch taken
    UNKNOWN = "unknown"            # Could not determine


@dataclass
class CausalNode:
    """A node in the causal DAG: (signal, cycle, value) tuple."""
    id: str
    signal: str
    cycle: int
    value: str
    suspect_score: float = 0.0
    rtl_refs: List[Dict] = field(default_factory=list)
    rtl_context_missing: bool = False
    is_root: bool = False
    is_endpoint: bool = False
    depth: int = 0

    def __hash__(self): return hash(self.id)
    def __eq__(self, other): return isinstance(other, CausalNode) and self.id == other.id

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "signal": self.signal,
            "cycle": self.cycle,
            "value": self.value,
            "suspect_score": self.suspect_score,
            "rtl_refs": self.rtl_refs,
            "rtl_context_missing": self.rtl_context_missing,
            "is_root": self.is_root,
            "is_endpoint": self.is_endpoint,
            "depth": self.depth
        }


@dataclass
class CausalEdge:
    """An edge representing direct causality in the DAG."""
    src_node_id: str
    dst_node_id: str
    reason: str
    contribution_type: ContributionType
    contribution_score: float
    evidence: Dict[str, Any] = field(default_factory=dict)
    change_examples: List[Dict] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "src_node_id": self.src_node_id,
            "dst_node_id": self.dst_node_id,
            "reason": self.reason,
            "contribution_type": self.contribution_type.value,
            "contribution_score": self.contribution_score,
            "evidence": self.evidence,
            "change_examples": self.change_examples
        }


# Operator precedence table (higher = binds tighter)
# NOTE: Only binary operators belong here. Unary !, ~ are handled separately.
_PRECEDENCE = {
    '||': 1, '|': 2, '&&': 3, '&': 4,
    '^': 5, '~^': 5, '^~': 5,
    '==': 6, '!=': 6, '===': 6, '!==': 6,
    '<': 7, '>': 7, '<=': 7, '>=': 7,
    '<<': 8, '>>': 8, '<<<': 8, '>>>': 8,
    '+': 9, '-': 9, '*': 10, '/': 10, '%': 10,
}

# Signal names to ignore during causal analysis (assertion-helper signals)
_IGNORED_SIGNALS = frozenset({'hasBeenReset', 'hasBeenResetReg', 'reset'})


class ExpressionEvaluator:
    """Evaluates Verilog expressions for counterfactual analysis."""
    
    RE_NUMBER = re.compile(r"(\d+)'([bhd])([0-9a-fA-F_xXzZ]+)")
    RE_DECIMAL = re.compile(r'\b(\d+)\b')
    RE_TERNARY = re.compile(r'(.+?)\s*\?\s*(.+?)\s*:\s*(.+)')  
    RE_SVA_IMPLICATION = re.compile(r'^(.+?)\s*\|->\s*(?:##\[\d+:\d+\]\s*)?(.+)$', re.DOTALL)

    def __init__(self, signal_values: Dict[str, str]):
        """Initialize with signal_name -> binary_value mapping."""
        self.signal_values = signal_values

    def evaluate(self, expr: str) -> Optional[str]:
        """Evaluate Verilog expression; returns binary string or None."""
        expr = expr.strip()
        if not expr:
            return None
        try:
            sva_match = self.RE_SVA_IMPLICATION.match(expr)
            if sva_match:
                return self._eval_sva_implication(sva_match.group(1), sva_match.group(2))
            return self._eval_expr(expr)
        except Exception:
            return None
    
    def _eval_sva_implication(self, antecedent: str, consequent: str) -> Optional[str]:
        """Evaluate SVA implication: if antecedent true, check consequent; else vacuously true."""
        ante_val = self._eval_expr(antecedent.strip())
        if ante_val is None:
            return None
        if not self._is_true(ante_val):
            return '1'  # Vacuously true
        cons_val = self._eval_expr(consequent.strip())
        return cons_val if cons_val is not None else '0'
    
    def _eval_expr(self, expr: str) -> Optional[str]:
        """Evaluate expression recursively."""
        expr = expr.strip()
        if not expr:
            return None
        
        # Handle Verilog concatenation: {a, b, c}
        if expr.startswith('{') and expr.endswith('}'):
            # Verify the outer braces actually match (not {a} & {b})
            depth = 0
            for ci, cc in enumerate(expr):
                if cc == '{':
                    depth += 1
                elif cc == '}':
                    depth -= 1
                if depth == 0 and ci < len(expr) - 1:
                    break  # Outer { closes before end — not a single concat
            else:
                parts = self._split_concat_parts(expr[1:-1])
                if parts:
                    result_bits = []
                    for part in parts:
                        val = self._eval_expr(part.strip())
                        if val is None:
                            return None
                        result_bits.append(val)
                    return ''.join(result_bits)
        
        # Handle parentheses
        if expr.startswith('(') and expr.endswith(')'):
            depth = 0
            for i, c in enumerate(expr):
                if c == '(':
                    depth += 1
                elif c == ')':
                    depth -= 1
                if depth == 0 and i < len(expr) - 1:
                    break
            else:
                return self._eval_expr(expr[1:-1])
        
        # Handle ternary operator
        ternary = self._parse_ternary(expr)
        if ternary:
            cond, then_expr, else_expr = ternary
            cond_val = self._eval_expr(cond)
            if cond_val is None:
                return None
            if self._is_true(cond_val):
                return self._eval_expr(then_expr)
            else:
                return self._eval_expr(else_expr)
        
        # Binary operators first (find lowest precedence outside parens)
        # This must come before unary checks so that "!a & b" correctly
        # splits at '&' rather than treating '!' as consuming the whole expr.
        op_pos, op = self._find_lowest_op(expr)
        if op is not None and op_pos > 0:
            left = self._eval_expr(expr[:op_pos])
            right = self._eval_expr(expr[op_pos + len(op):])
            if left is None or right is None:
                return None
            return self._apply_binary_op(left, right, op)
        
        # Handle unary operators: !, ~, -
        if expr.startswith('!'):
            val = self._eval_expr(expr[1:])
            if val is None:
                return None
            return '1' if not self._is_true(val) else '0'
        
        if expr.startswith('~'):
            val = self._eval_expr(expr[1:])
            return self._bitwise_not(val) if val else None
        
        if expr.startswith('-') and len(expr) > 1:
            val = self._eval_expr(expr[1:])
            if val is None:
                return None
            int_val = parse_binary_value(val)
            if int_val is not None:
                neg_val = (-int_val) & ((1 << len(val)) - 1)
                return bin(neg_val)[2:].zfill(len(val))
            return None
        
        # Reduction operators: &x, |x, ^x (reduce multi-bit to single bit)
        if len(expr) > 1:
            if expr[0] == '&' and expr[1] != '&':
                val = self._eval_expr(expr[1:])
                return ('1' if all(c == '1' for c in val) else '0') if val else None
            if expr[0] == '|' and expr[1] != '|':
                val = self._eval_expr(expr[1:])
                return ('1' if '1' in val else '0') if val else None
            if expr[0] == '^':
                val = self._eval_expr(expr[1:])
                return ('1' if val.count('1') % 2 else '0') if val else None
        
        return self._eval_atom(expr)
    
    def _split_concat_parts(self, inner: str) -> List[str]:
        """Split concatenation by commas, respecting nesting."""
        parts, depth, current = [], 0, []
        for c in inner:
            if c in '({':
                depth += 1
                current.append(c)
            elif c in ')}':
                depth -= 1
                current.append(c)
            elif c == ',' and depth == 0:
                parts.append(''.join(current))
                current = []
            else:
                current.append(c)
        if current:
            parts.append(''.join(current))
        return parts
    
    def _parse_ternary(self, expr: str) -> Optional[Tuple[str, str, str]]:
        """Parse ternary operator (cond ? then : else)."""
        depth, q_pos, c_pos = 0, -1, -1
        for i, c in enumerate(expr):
            if c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
            elif c == '?' and depth == 0 and q_pos < 0:
                q_pos = i
            elif c == ':' and depth == 0 and q_pos >= 0:
                c_pos = i
                break
        
        if q_pos > 0 and c_pos > q_pos:
            return expr[:q_pos].strip(), expr[q_pos+1:c_pos].strip(), expr[c_pos+1:].strip()
        return None
    
    def _find_lowest_op(self, expr: str) -> Tuple[int, Optional[str]]:
        """Find lowest precedence binary operator outside parentheses/brackets/braces."""
        depth = 0
        lowest = (999, -1, None)  # (prec, pos, op)
        sorted_ops = sorted(_PRECEDENCE.keys(), key=len, reverse=True)
        i = 0
        while i < len(expr):
            c = expr[i]
            if c in '([{':
                depth += 1
                i += 1
            elif c in ')]}':  
                depth -= 1
                i += 1
            elif depth == 0:
                # Check for operators (longest match first)
                matched = False
                for op in sorted_ops:
                    if expr[i:i+len(op)] == op:
                        if _PRECEDENCE[op] <= lowest[0]:
                            lowest = (_PRECEDENCE[op], i, op)
                        i += len(op)  # Skip past operator to avoid substring matches
                        matched = True
                        break
                if not matched:
                    i += 1
            else:
                i += 1
        return lowest[1], lowest[2]
    
    def _eval_atom(self, expr: str) -> Optional[str]:
        """Evaluate atomic expression (number or signal)."""
        expr = expr.strip()
        
        # Sized number (e.g., 4'b1010, 8'hFF)
        match = self.RE_NUMBER.match(expr)
        if match:
            width, base, value = int(match.group(1)), match.group(2), match.group(3).replace('_', '')
            try:
                int_val = int(value, {'b': 2, 'h': 16, 'd': 10}[base])
                return bin(int_val)[2:].zfill(width)[-width:]
            except:
                return 'x' * width
        
        # Decimal number
        if expr.isdigit():
            n = int(expr)
            return bin(n)[2:] if n else '0'
        
        # Direct signal lookup
        if expr in self.signal_values:
            return self.signal_values[expr]
        
        # Try partial match (for hierarchical signals)
        for sig, val in self.signal_values.items():
            if sig.endswith('.' + expr) or sig.endswith('_' + expr):
                return val
        
        return None
    
    def _is_true(self, val: str) -> bool:
        """Check if a value is logically true (non-zero)."""
        if not val or 'x' in val.lower() or 'z' in val.lower():
            return False
        return '1' in val
    
    def _bitwise_not(self, val: str) -> str:
        """Bitwise NOT: 0->1, 1->0, x->x."""
        return val.translate(str.maketrans('01', '10'))
    
    def _apply_binary_op(self, left: str, right: str, op: str) -> Optional[str]:
        """Apply binary operator to two values."""
        max_len = max(len(left), len(right))
        left, right = left.zfill(max_len), right.zfill(max_len)
        
        # Logical operators (always return single-bit '0' or '1')
        if op == '&&':
            return '1' if self._is_true(left) and self._is_true(right) else '0'
        
        if op == '||':
            return '1' if self._is_true(left) or self._is_true(right) else '0'
        
        # Bitwise AND
        if op == '&':
            result = []
            for l, r in zip(left, right):
                if l == '0' or r == '0':
                    result.append('0')
                elif l == '1' and r == '1':
                    result.append('1')
                else:
                    result.append('x')
            return ''.join(result)
        
        # Bitwise OR
        if op == '|':
            result = []
            for l, r in zip(left, right):
                if l == '1' or r == '1':
                    result.append('1')
                elif l == '0' and r == '0':
                    result.append('0')
                else:
                    result.append('x')
            return ''.join(result)
        
        # Bitwise XOR
        if op == '^':
            result = []
            for l, r in zip(left, right):
                if l in 'xXzZ' or r in 'xXzZ':
                    result.append('x')
                elif l == r:
                    result.append('0')
                else:
                    result.append('1')
            return ''.join(result)
        
        # Bitwise XNOR
        if op in ('~^', '^~'):
            result = []
            for l, r in zip(left, right):
                if l in 'xXzZ' or r in 'xXzZ':
                    result.append('x')
                elif l == r:
                    result.append('1')
                else:
                    result.append('0')
            return ''.join(result)
        
        if op == '==' or op == '===':
            return '1' if left == right else '0'
        
        if op == '!=' or op == '!==':
            return '1' if left != right else '0'
        
        # Arithmetic and shift (requires integer conversion)
        left_int, right_int = parse_binary_value(left), parse_binary_value(right)
        if left_int is not None and right_int is not None:
            mask = (1 << max_len) - 1
            ops = {
                '+': lambda: bin((left_int + right_int) & mask)[2:].zfill(max_len),
                '-': lambda: bin((left_int - right_int) & mask)[2:].zfill(max_len),
                '*': lambda: bin((left_int * right_int) & mask)[2:].zfill(max_len),
                '/': lambda: bin(left_int // right_int)[2:].zfill(max_len) if right_int != 0 else None,
                '%': lambda: bin(left_int % right_int)[2:].zfill(max_len) if right_int != 0 else None,
                '<': lambda: '1' if left_int < right_int else '0',
                '>': lambda: '1' if left_int > right_int else '0',
                '<=': lambda: '1' if left_int <= right_int else '0',
                '>=': lambda: '1' if left_int >= right_int else '0',
                '<<': lambda: bin((left_int << right_int) & mask)[2:].zfill(max_len),
                '>>': lambda: bin(left_int >> right_int)[2:].zfill(max_len),
                '<<<': lambda: bin((left_int << right_int) & mask)[2:].zfill(max_len),
                '>>>': lambda: bin(left_int >> right_int)[2:].zfill(max_len),
            }
            if op in ops:
                result = ops[op]()
                return result
        return None


class BackwardSlicer:
    """Backward slicing engine for building causal DAG.
    
    Performs backward traversal from counterexample endpoint,
    using counterfactual evaluation to determine causality.
    """

    def __init__(self, 
                 verilog_parser: VerilogParser, 
                 waveform: CycleAlignedWaveform,
                 max_depth: int = 20, 
                 max_nodes: int = 200
                 ):
        """Initialize backward slicer with RTL parser and waveform data."""
        self.parser = verilog_parser
        self.waveform = waveform
        self.max_depth = max_depth
        self.max_nodes = max_nodes
        self.dep_graph = verilog_parser.build_dependency_graph()
        self._signal_sources_cache: Dict[str, List[str]] = {}
        
        # DAG state
        self.nodes: Dict[str, CausalNode] = {}
        self.edges: List[CausalEdge] = []
        self.visited: Set[str] = set()
        self._edge_set: Set[Tuple[str, str]] = set()
        
        # SVA pattern for trigger cycle detection
        self._re_sva_implication = re.compile(r'^(.+?)\s*\|->\s*(?:##\[\d+:\d+\]\s*)?(.+)$', re.DOTALL)
        # SVA time window pattern: ##[min:max] or ##N
        self._re_sva_time_window = re.compile(r'##\[(\d+):(\d+)\]|##(\d+)')
        
        self.stats = {
            "nodes_created": 0,
            "edges_created": 0,
            "max_depth_reached": False,
            "max_nodes_reached": False,
            "undetermined_nodes": 0,
            "sva_trigger_cycle": None,
            "sva_time_window": None,  # (min_delay, max_delay) if SVA has time window
            "sva_window_end_cycle": None,  # The cycle when assertion failed (end of window)
            "sva_consequent_signals": None  # Signals in the consequent part
        }
    
    @staticmethod
    def _make_node_id(signal: str, cycle: int, value: str) -> str:
        """Create unique node ID from signal@cycle=value."""
        return hashlib.md5(f"{signal}@{cycle}={value}".encode()).hexdigest()[:12]
    
    @staticmethod
    def _extract_base_signal_name(signal: str) -> str:
        """Extract base signal name (no hierarchy/width)."""
        return re.sub(r'\s*\[\d+:\d+\]$', '', signal).split('.')[-1]
    
    @staticmethod
    def _extract_module_hierarchy(signal: str) -> str:
        """Extract module hierarchy prefix."""
        clean = re.sub(r'\s*\[\d+:\d+\]$', '', signal)
        parts = clean.split('.')
        if len(parts) > 1:
            return '.'.join(parts[:-1])
        return ''

    def _infer_module_name(self, signal: str, hierarchy: str = '') -> Optional[str]:
        """Infer RTL module name for a waveform signal when parser supports it."""
        infer = getattr(self.parser, "infer_module_from_signal", None)
        if callable(infer):
            return infer(signal, hierarchy=hierarchy)
        return None

    def _get_signal_sources_cached(self, signal_name: str, module_name: Optional[str] = None) -> List[str]:
        """Get cached source signals for a target signal name."""
        cache_key = f"{module_name or ''}:{signal_name}"
        if cache_key in self._signal_sources_cache:
            return self._signal_sources_cache[cache_key]

        graph_key = f"{module_name}.{signal_name}" if module_name else signal_name
        sources = self.dep_graph.get(graph_key)
        if sources is None:
            sources = self.dep_graph.get(signal_name)
        if sources is None:
            source_list = [s for s, _ in self.parser.get_signal_sources(signal_name, module_name)]
        else:
            if module_name:
                source_list = [
                    s for s, _, dep in sources
                    if not getattr(dep, "module_name", None) or dep.module_name == module_name
                ]
            else:
                source_list = [s for s, _, _ in sources]

        # De-duplicate while preserving order
        seen: Set[str] = set()
        deduped: List[str] = []
        for src in source_list:
            if src not in seen:
                seen.add(src)
                deduped.append(src)

        self._signal_sources_cache[cache_key] = deduped
        return deduped

    def _resolve_signal_value(self,
                              signal: str,
                              cycle: int,
                              hierarchy: str = '',
                              match_cache: Optional[Dict[str, List[str]]] = None,
                              prefer_hierarchy: bool = True
                              ) -> Tuple[Optional[str], str]:
        """Resolve signal value with hierarchy and partial-match fallbacks."""
        if prefer_hierarchy and hierarchy and not signal.startswith(hierarchy + '.'):
            full_signal = f"{hierarchy}.{signal}"
            value = self.waveform.get_signal_value(full_signal, cycle)
            if value is not None:
                return value, full_signal

        value = self.waveform.get_signal_value(signal, cycle)
        if value is not None:
            return value, signal

        if not prefer_hierarchy and hierarchy and not signal.startswith(hierarchy + '.'):
            full_signal = f"{hierarchy}.{signal}"
            value = self.waveform.get_signal_value(full_signal, cycle)
            if value is not None:
                return value, full_signal

        if match_cache is not None and signal in match_cache:
            matches = match_cache[signal]
        else:
            matches = self.waveform.find_signal(signal, max_results=10)
            if match_cache is not None:
                match_cache[signal] = matches

        for match in matches:
            match_base = re.sub(r'\s*\[\d+:\d+\]$', '', match)
            if match_base.endswith('.' + signal) or match_base.endswith(signal):
                value = self.waveform.get_signal_value(match, cycle)
                if value is not None:
                    return value, match

        return None, signal
    
    def _parse_sva_time_window(self, sva_expr: str) -> Tuple[Optional[int], Optional[int]]:
        """
        Parse SVA time window from expression.
        
        Args:
            sva_expr: SVA expression like "antecedent |-> ##[1:200] consequent"
            
        Returns:
            (min_delay, max_delay) tuple, or (None, None) if no time window
        """
        match = self._re_sva_time_window.search(sva_expr)
        if not match:
            return None, None
        
        if match.group(1) and match.group(2):
            # ##[min:max] format
            return int(match.group(1)), int(match.group(2))
        elif match.group(3):
            # ##N format (fixed delay)
            delay = int(match.group(3))
            return delay, delay
        
        return None, None
    
    def _extract_consequent_signals(self, sva_expr: str, dep_sources: Optional[Set[str]] = None) -> Set[str]:
        """
        Extract signal names from SVA consequent part.
        
        Args:
            sva_expr: Full SVA expression
            dep_sources: Optional set of all source signals from dependencies
            
        Returns:
            Set of signal names in the consequent
        """
        match = self._re_sva_implication.match(sva_expr)
        if not match:
            # If expression is truncated, try to use dep_sources to infer consequent signals
            if dep_sources:
                # Heuristic: consequent signals are often named with "state", "eating", "done", etc.
                consequent_keywords = {'eating', 'done', 'ready', 'valid', 'complete', 'finish', 'state'}
                signals = set()
                for src in dep_sources:
                    src_lower = src.lower()
                    for kw in consequent_keywords:
                        if kw in src_lower:
                            signals.add(src)
                            break
                return signals
            return set()
        
        consequent = match.group(2).strip()
        # Remove time window specification if present
        consequent = re.sub(r'##\[\d+:\d+\]\s*', '', consequent)
        consequent = re.sub(r'##\d+\s*', '', consequent)
        
        # If consequent is empty or just "...", try using dep_sources
        if not consequent or consequent == '...' or consequent.strip() == '':
            if dep_sources:
                consequent_keywords = {'eating', 'done', 'ready', 'valid', 'complete', 'finish', 'state'}
                signals = set()
                for src in dep_sources:
                    src_lower = src.lower()
                    for kw in consequent_keywords:
                        if kw in src_lower:
                            signals.add(src)
                            break
                return signals
            return set()
        
        # Extract signal references
        signals = set()
        for sig_match in re.finditer(r'\b([a-zA-Z_][a-zA-Z0-9_]*)\b', consequent):
            sig = sig_match.group(1)
            # Filter out keywords and numbers
            if sig.lower() not in {'if', 'else', 'and', 'or', 'not', 'h', 'b', 'd', 'o'} and not sig.isdigit():
                signals.add(sig)
        
        return signals
    
    def _find_sva_trigger_cycle(self, endpoint_signal: str, max_cycle: int) -> Optional[int]:
        """
        Find cycle where SVA antecedent becomes true (trigger point).
        
        For assertions like: antecedent |-> ##[min:max] consequent
        The trigger cycle is the first cycle (before failure) where antecedent is true.
        This is typically max_cycle - max_delay for time-windowed assertions.
        """
        base_signal = self._extract_base_signal_name(endpoint_signal)
        hierarchy = self._extract_module_hierarchy(endpoint_signal)
        module_hint = self._infer_module_name(endpoint_signal, hierarchy)
        
        deps = self.parser.get_dependencies_for_signal(base_signal, module_hint)
        if not deps:
            return None
        
        # Find the first dep that has an SVA implication expression
        sva_expr = None
        for dep in deps:
            if '|->' in dep.expression:
                sva_expr = dep.expression
                break
        
        if not sva_expr:
            return None
        
        match = self._re_sva_implication.match(sva_expr)
        if not match:
            return None
        
        antecedent = match.group(1).strip()
        sources = {d.source for d in deps}
        
        # Parse time window
        min_delay, max_delay = self._parse_sva_time_window(sva_expr)
        if min_delay is not None:
            self.stats["sva_time_window"] = (min_delay, max_delay)
            self.stats["sva_window_end_cycle"] = max_cycle
            # Pass dep sources to help extract consequent signals from truncated expressions
            self.stats["sva_consequent_signals"] = self._extract_consequent_signals(sva_expr, sources)
        
        # Build a signal resolution cache for faster lookup
        signal_cache: Dict[str, str] = {}  # short_name -> resolved_full_name
        for src in sources:
            full_src = f"{hierarchy}.{src}" if hierarchy else src
            # Try to find the actual signal in waveform (may have width suffix)
            matches = self.waveform.find_signal(src, max_results=5)
            for m in matches:
                # Prefer signals in the correct hierarchy
                if hierarchy and m.startswith(hierarchy + '.'):
                    signal_cache[src] = m
                    break
                elif not hierarchy and src in m:
                    signal_cache[src] = m
                    break
            if src not in signal_cache:
                signal_cache[src] = full_src  # Fallback to constructed name
        
        # For time-windowed assertions, the trigger is at max_cycle - max_delay
        # We scan from that point backward to find where antecedent became true
        search_start = max_cycle
        if max_delay is not None:
            search_start = max_cycle - max_delay
        
        # First, find the actual trigger cycle by scanning backward from search_start
        # to find where antecedent FIRST became true (before staying true)
        trigger_cycle = None
        last_true_cycle = None
        
        for cycle in range(search_start, -1, -1):
            env = {}
            for src in sources:
                resolved_sig = signal_cache.get(src, src)
                val = self.waveform.get_signal_value(resolved_sig, cycle)
                if val:
                    # Add both short and full name to environment
                    env[src] = val
                    # Also add without underscore prefix if applicable
                    if src.startswith('_'):
                        env[src[1:]] = val
            
            # Evaluate antecedent
            evaluator = ExpressionEvaluator(env)
            result = evaluator.evaluate(antecedent)
            
            if result and evaluator._is_true(result):
                last_true_cycle = cycle
            else:
                # Antecedent is false, so the trigger was at last_true_cycle
                if last_true_cycle is not None:
                    trigger_cycle = last_true_cycle
                    break
        
        # If we never found a false cycle, use the earliest true cycle
        if trigger_cycle is None and last_true_cycle is not None:
            trigger_cycle = last_true_cycle
        
        return trigger_cycle
    
    def _get_or_create_node(self, signal: str, cycle: int, depth: int, 
                            parent_hierarchy: str = '') -> Optional[CausalNode]:
        """Get existing node or create a new one."""
        original_signal = signal
        match_cache: Dict[str, List[str]] = {}
        value, resolved_signal = self._resolve_signal_value(
            signal, cycle, parent_hierarchy, match_cache, prefer_hierarchy=False
        )
        signal = resolved_signal
        
        if value is None:
            value = 'x'  # Unknown value
        
        node_id = self._make_node_id(signal, cycle, value)
        
        if node_id in self.nodes:
            return self.nodes[node_id]
        
        if len(self.nodes) >= self.max_nodes:
            self.stats["max_nodes_reached"] = True
            return None
        
        # Get RTL context using base signal name (without hierarchy prefix)
        base_signal = self._extract_base_signal_name(signal)
        signal_hierarchy = self._extract_module_hierarchy(signal)
        module_hint = self._infer_module_name(signal, signal_hierarchy or parent_hierarchy)
        rtl_context = self.parser.get_rtl_context(base_signal, module_hint)
        
        # If not found, try with original signal name
        if not rtl_context.get("found", False):
            rtl_context = self.parser.get_rtl_context(original_signal, module_hint)
        
        node = CausalNode(
            id=node_id,
            signal=signal,
            cycle=cycle,
            value=value,
            rtl_refs=rtl_context.get("rtl_refs", []),
            rtl_context_missing=not rtl_context.get("found", False),
            depth=depth
        )
        
        self.nodes[node_id] = node
        self.stats["nodes_created"] += 1
        
        if node.rtl_context_missing:
            self.stats["undetermined_nodes"] += 1
        
        return node
    
    def _get_parent_cycle(self, dep: Dependency, target_cycle: int) -> int:
        """
        Determine the parent cycle based on dependency type.
        
        Args:
            dep: Dependency information
            target_cycle: Target signal's cycle
            
        Returns:
            Source signal's relevant cycle
        """
        if dep.dep_type in (
            DependencyType.COMBINATIONAL,
            DependencyType.ASSERTION,
            DependencyType.PORT_INPUT,
            DependencyType.PORT_OUTPUT,
            DependencyType.WIRE,
        ):
            return target_cycle  # Same cycle for combinational
        elif dep.dep_type == DependencyType.SEQUENTIAL:
            return max(0, target_cycle - 1)  # Previous cycle for sequential
        else:
            return max(0, target_cycle - 1)  # Default to previous
    
    def _evaluate_counterfactual(self,
                                  target_signal: str,
                                  target_cycle: int,
                                  source_signal: str,
                                  source_cycle: int,
                                  dep: Dependency) -> Tuple[bool, float, List[Dict]]:
        """
        Evaluate if source causally affects target via counterfactual analysis.
        
        Args:
            target_signal: Target signal name
            target_cycle: Target cycle
            source_signal: Source signal name
            source_cycle: Source cycle
            dep: Dependency information
            
        Returns:
            (is_causal, contribution_score, change_examples)
        """
        target_value = self.waveform.get_signal_value(target_signal, target_cycle)
        source_value = self.waveform.get_signal_value(source_signal, source_cycle)
        if target_value is None or source_value is None:
            return False, 0.0, []

        if not dep.expression.strip():
            return self._simple_toggle_test(source_signal, source_cycle, target_signal, target_cycle)

        target_base = self._extract_base_signal_name(target_signal)
        target_hierarchy = self._extract_module_hierarchy(target_signal)
        source_base = self._extract_base_signal_name(source_signal)
        module_hint = self._infer_module_name(target_signal, target_hierarchy)

        env: Dict[str, str] = {}
        match_cache: Dict[str, List[str]] = {}
        for src in self._get_signal_sources_cached(target_base, module_hint):
            val, _ = self._resolve_signal_value(
                src, source_cycle, target_hierarchy, match_cache, prefer_hierarchy=True
            )
            if val is not None:
                env[src] = val

        if not env:
            return self._simple_toggle_test(source_signal, source_cycle, target_signal, target_cycle)

        evaluator = ExpressionEvaluator(env)
        orig_result = evaluator.evaluate(dep.expression)
        if orig_result is None:
            return self._simple_toggle_test(source_signal, source_cycle, target_signal, target_cycle)

        perturbed_env = dict(env)
        perturb_key = dep.source if dep.source in perturbed_env else source_base if source_base in perturbed_env else dep.source
        perturb_value = invert_value(source_value)
        perturbed_env[perturb_key] = perturb_value

        perturbed_result = ExpressionEvaluator(perturbed_env).evaluate(dep.expression)
        if perturbed_result is None:
            return False, 0.0, []

        is_causal = values_differ(orig_result, perturbed_result)

        if not is_causal and '==' in dep.expression:
            smart_result = self._try_smart_perturbation(
                dep, env, source_value, source_base, orig_result
            )
            if smart_result is not None:
                is_causal, perturbed_result, perturb_value = smart_result

        score = 0.0
        if is_causal:
            max_len = max(len(orig_result), len(perturbed_result))
            left = orig_result.zfill(max_len)
            right = perturbed_result.zfill(max_len)
            diff_bits = sum(
                1 for a, b in zip(left, right)
                if a != b and a not in 'xXzZ' and b not in 'xXzZ'
            )
            score = min(1.0, diff_bits / max(1, max_len) + 0.5)

        examples = []
        if is_causal:
            examples.append({
                "type": "counterfactual",
                "source_original": source_value,
                "source_perturbed": perturb_value,
                "target_original": orig_result,
                "target_perturbed": perturbed_result,
                "expression": dep.expression
            })

        return is_causal, score, examples
    
    def _try_smart_perturbation(self,
                                 dep: Dependency,
                                 env: Dict[str, str],
                                 source_value: str,
                                 source_base: str,
                                 orig_result: str) -> Optional[Tuple[bool, str, str]]:
        """
        Try smarter perturbation for equality comparisons.
        
        For expressions like (X == const), simple inversion may not change the result.
        Instead, try setting X to the const value to flip the comparison.
        
        Args:
            dep: Dependency information
            env: Current signal environment
            source_value: Current source signal value
            source_base: Base name of source signal
            orig_result: Original expression result
            
        Returns:
            (is_causal, perturbed_result, perturbed_source_value) or None if cannot apply
        """
        expr = dep.expression
        
        # Pattern: source == literal
        # Try to extract the comparison value
        patterns = [
            # Match: signal == N'hX or signal == N'bX or signal == N'dX
            re.compile(rf'\b{re.escape(source_base)}\s*==\s*(\d+\'[bhd][0-9a-fA-F_]+)'),
            re.compile(rf'\b{re.escape(dep.source)}\s*==\s*(\d+\'[bhd][0-9a-fA-F_]+)'),
            # Match: N'hX == signal (reversed order)
            re.compile(rf'(\d+\'[bhd][0-9a-fA-F_]+)\s*==\s*{re.escape(source_base)}\b'),
            re.compile(rf'(\d+\'[bhd][0-9a-fA-F_]+)\s*==\s*{re.escape(dep.source)}\b'),
        ]
        
        target_value = None
        for pattern in patterns:
            match = pattern.search(expr)
            if match:
                # Parse the literal value
                lit = match.group(1)
                # Use ExpressionEvaluator to parse the literal
                evaluator = ExpressionEvaluator({})
                target_value = evaluator.evaluate(lit)
                break
        
        if target_value is None:
            return None
        
        # If current value already equals target, try a different value
        if source_value == target_value:
            # The comparison should be true, set to something different
            perturb_val = invert_value(target_value)
        else:
            # The comparison is false, set to target to make it true
            perturb_val = target_value
        
        # Apply perturbation
        perturbed_env = env.copy()
        if dep.source in perturbed_env:
            perturbed_env[dep.source] = perturb_val
        elif source_base in perturbed_env:
            perturbed_env[source_base] = perturb_val
        else:
            perturbed_env[dep.source] = perturb_val
        
        perturbed_evaluator = ExpressionEvaluator(perturbed_env)
        perturbed_result = perturbed_evaluator.evaluate(dep.expression)
        
        if perturbed_result is None:
            return None
        
        is_causal = values_differ(orig_result, perturbed_result)
        return (is_causal, perturbed_result, perturb_val)

    def _simple_toggle_test(self, 
                            source_signal: str, 
                            source_cycle: int,
                            target_signal: str,
                            target_cycle: int) -> Tuple[bool, float, List[Dict]]:
        """
        Simple toggle test: check if source change correlates with target change.
        
        Args:
            source_signal: Source signal name
            source_cycle: Source cycle
            target_signal: Target signal name
            target_cycle: Target cycle
            
        Returns:
            (is_causal, score, examples)
        """
        # Initialize values
        src_prev: Optional[str] = None
        src_curr: Optional[str] = None
        tgt_prev: Optional[str] = None
        tgt_curr: Optional[str] = None
        
        # Check if source changed in source_cycle
        if source_cycle > 0:
            src_prev = self.waveform.get_signal_value(source_signal, source_cycle - 1)
            src_curr = self.waveform.get_signal_value(source_signal, source_cycle)
            source_changed = values_differ(src_prev or 'x', src_curr or 'x')
        else:
            source_changed = False
        
        # Check if target changed in target_cycle
        if target_cycle > 0:
            tgt_prev = self.waveform.get_signal_value(target_signal, target_cycle - 1)
            tgt_curr = self.waveform.get_signal_value(target_signal, target_cycle)
            target_changed = values_differ(tgt_prev or 'x', tgt_curr or 'x')
        else:
            target_changed = False
        
        # Simple heuristic: if both changed, likely causal
        is_causal = source_changed and target_changed
        score = 0.7 if is_causal else 0.0
        
        examples = []
        if is_causal:
            examples.append({
                "type": "toggle_correlation",
                "source_before": src_prev,
                "source_after": src_curr,
                "target_before": tgt_prev,
                "target_after": tgt_curr
            })
        
        return is_causal, score, examples
    
    def _slice_node(self, node: CausalNode, depth: int):
        """
        Perform backward slicing from a node.
        
        Args:
            node: Current node to slice from
            depth: Current depth
        """
        if depth > self.max_depth:
            self.stats["max_depth_reached"] = True
            return
        
        if node.id in self.visited:
            return
        
        self.visited.add(node.id)
        
        # Extract base signal name and hierarchy for lookup
        base_signal = self._extract_base_signal_name(node.signal)
        parent_hierarchy = self._extract_module_hierarchy(node.signal)
        module_hint = self._infer_module_name(node.signal, parent_hierarchy)
        
        # Get dependencies from RTL
        deps = self.parser.get_dependencies_for_signal(base_signal, module_hint)
        
        if not deps:
            # Check if we can find with the full name (no width annotation)
            clean_signal = re.sub(r'\s*\[\d+:\d+\]$', '', node.signal)
            deps = self.parser.get_dependencies_for_signal(clean_signal, module_hint)
        
        if not deps:
            # No RTL dependencies found, mark as potential root
            node.is_root = True
            return
        
        for dep in deps:
            # Determine parent cycle
            parent_cycle = self._get_parent_cycle(dep, node.cycle)
            
            if parent_cycle < 0:
                continue
            
            # Check for self-dependency before creating node
            # A signal depending on itself in the same cycle is not valid causality
            source_base = self._extract_base_signal_name(dep.source)
            target_base = self._extract_base_signal_name(node.signal)
            
            # Skip if source and target are the same signal (avoid self-loops)
            if source_base == target_base:
                continue
            
            # Skip reset-related auxiliary signals (hasBeenReset, hasBeenResetReg, reset)
            if source_base in _IGNORED_SIGNALS:
                continue
            
            # Also check if full signal names match (with hierarchy)
            if dep.source == node.signal:
                continue
            
            # Check if the source (with hierarchy) matches node signal
            full_source = f"{parent_hierarchy}.{dep.source}" if parent_hierarchy else dep.source
            clean_node_signal = re.sub(r'\s*\[\d+:\d+\]$', '', node.signal)
            full_source_base = full_source.rsplit('.', 1)[-1]
            if full_source == clean_node_signal or full_source_base == target_base:
                if dep.dep_type == DependencyType.COMBINATIONAL:
                    # Combinational self-dependency is not allowed
                    continue
            
            # Create parent node with hierarchy context
            parent_node = self._get_or_create_node(
                dep.source, parent_cycle, depth + 1, parent_hierarchy
            )
            if parent_node is None:
                continue
            
            # Final self-loop check using node IDs
            if parent_node.id == node.id:
                continue
            
            # Check for duplicate edges
            edge_key = (parent_node.id, node.id)
            if edge_key in self._edge_set:
                continue  # Skip duplicate edge
            
            # Evaluate causality using full signal names from nodes
            is_causal, score, examples = self._evaluate_counterfactual(
                node.signal, node.cycle,
                parent_node.signal, parent_cycle,
                dep
            )
            
            # For SVA assertions at trigger cycle, if antecedent is true,
            # all signals in antecedent are causally contributing
            if not is_causal and '|->' in dep.expression:
                # Check if this is an SVA trigger cycle (antecedent is true)
                trigger_cycle = self.stats.get("sva_trigger_cycle")
                if trigger_cycle is not None and node.cycle == trigger_cycle:
                    # At trigger cycle, all antecedent signals contribute to assertion
                    is_causal = True
                    score = 0.85  # High score for antecedent signals
                    examples = [{
                        "type": "sva_antecedent",
                        "trigger_cycle": trigger_cycle,
                        "expression": dep.expression,
                        "signal": parent_node.signal,
                        "value": parent_node.value
                    }]
            
            if not is_causal and not node.rtl_context_missing:
                # Counterfactual did not show causality, but we may still want to track
                # this dependency based on RTL structure for deeper exploration
                # Use a lower score to indicate it's structural dependency only
                if depth < self.max_depth // 2:
                    # For shallow depths, still create edges for structural deps
                    # This helps build a more complete causal picture
                    is_causal = True
                    score = 0.3  # Lower score for structural-only dependency
                    examples = [{
                        "type": "structural",
                        "reason": "RTL dependency exists but counterfactual not conclusive"
                    }]
                else:
                    # For deeper levels, skip to avoid graph explosion
                    continue
            
            # Determine contribution type
            if dep.dep_type == DependencyType.SEQUENTIAL:
                contrib_type = ContributionType.STATE
            elif dep.condition:
                contrib_type = ContributionType.CONDITIONAL
            elif examples and examples[0].get("type") == "toggle_correlation":
                contrib_type = ContributionType.TOGGLE
            else:
                contrib_type = ContributionType.EXPR_EVAL
            
            # Create edge
            edge = CausalEdge(
                src_node_id=parent_node.id,
                dst_node_id=node.id,
                reason=f"{dep.source} affects {node.signal} via {dep.dep_type.value}",
                contribution_type=contrib_type,
                contribution_score=score,
                evidence={
                    "file": dep.file_path,
                    "lines": [dep.line_start, dep.line_end],
                    "code_snippet": dep.code_snippet,
                    "expression": dep.expression,
                    "condition": dep.condition
                },
                change_examples=examples
            )
            
            self.edges.append(edge)
            self._edge_set.add(edge_key)  # Track edge to prevent duplicates
            self.stats["edges_created"] += 1
            
            # Update parent node suspect score
            parent_node.suspect_score = max(parent_node.suspect_score, score * 0.9)
            
            # Recurse
            self._slice_node(parent_node, depth + 1)
    
    def _analyze_sva_time_window(self, 
                                  trigger_cycle: int,
                                  window_end_cycle: int,
                                  consequent_signals: Set[str],
                                  hierarchy: str,
                                  depth: int) -> List[CausalNode]:
        """
        Analyze SVA time window to find why consequent never became true.
        
        For assertions like: antecedent |-> ##[1:200] consequent
        We need to analyze why 'consequent' was never true during cycles
        [trigger_cycle + 1, window_end_cycle].
        
        Strategy:
        1. Sample key cycles within the window (start, middle, end)
        2. For each consequent signal, find cycles where it was closest to becoming true
        3. Trace back why it didn't toggle to true
        
        Args:
            trigger_cycle: Cycle where antecedent became true
            window_end_cycle: Cycle where assertion failed (end of window)
            consequent_signals: Set of signal names in the consequent
            hierarchy: Module hierarchy prefix
            depth: Current depth for node creation
            
        Returns:
            List of nodes created for window analysis
        """
        window_nodes = []
        min_delay, max_delay = self.stats.get("sva_time_window", (1, 1))
        
        # Calculate actual window range
        window_start = trigger_cycle + min_delay
        window_end = min(trigger_cycle + max_delay, window_end_cycle)
        
        if window_start > window_end:
            return window_nodes
        
        # Sample cycles: start, a few intermediate points, and end
        sample_cycles = [window_start]
        window_size = window_end - window_start
        if window_size > 10:
            # Add intermediate sample points
            for i in [0.25, 0.5, 0.75]:
                sample_cycles.append(window_start + int(window_size * i))
        sample_cycles.append(window_end)
        sample_cycles = sorted(set(sample_cycles))
        
        # For each consequent signal, analyze at sample cycles
        for sig in consequent_signals:
            full_sig = f"{hierarchy}.{sig}" if hierarchy else sig
            
            # Find if signal ever came close to being true (any 1 bit toggled)
            last_value = None
            interesting_cycles = []
            
            for cycle in range(window_start, window_end + 1):
                val = self.waveform.get_signal_value(full_sig, cycle)
                if val is None:
                    # Try without hierarchy
                    val = self.waveform.get_signal_value(sig, cycle)
                
                if val is not None:
                    if last_value is not None and val != last_value:
                        # Signal changed - this is interesting
                        interesting_cycles.append(cycle)
                    last_value = val
            
            # Combine sample cycles with interesting cycles (limit to avoid explosion)
            cycles_to_analyze = list(set(sample_cycles + interesting_cycles[:5]))
            cycles_to_analyze = sorted(cycles_to_analyze)[:8]  # Limit to 8 cycles
            
            for cycle in cycles_to_analyze:
                # Create node for this signal at this cycle
                node = self._get_or_create_node(sig, cycle, depth, hierarchy)
                if node is not None:
                    # Mark as part of window analysis
                    if "window_analysis" not in [ref.get("type") for ref in node.rtl_refs]:
                        node.rtl_refs.append({
                            "type": "window_analysis",
                            "window_start": window_start,
                            "window_end": window_end,
                            "trigger_cycle": trigger_cycle
                        })
                    window_nodes.append(node)
        
        return window_nodes
    
    def slice_from_endpoint(self, 
                            endpoint_signal: str, 
                            endpoint_cycle: int) -> Tuple[Dict[str, CausalNode], List[CausalEdge]]:
        """
        Perform backward slicing starting from endpoint.
        
        For SVA assertions with implication (|->), automatically finds
        the cycle where the antecedent becomes true (trigger point).
        
        For assertions with time windows (##[min:max]), also analyzes
        why the consequent never became true during the window.
        
        Args:
            endpoint_signal: Signal that triggered the counterexample
            endpoint_cycle: Cycle when counterexample was triggered (assertion failure)
            
        Returns:
            (nodes dict, edges list)
        """
        # Reset state
        self.nodes = {}
        self.edges = []
        self.visited = set()
        self._edge_set = set()  # Track edges to prevent duplicates
        self.stats = {
            "nodes_created": 0,
            "edges_created": 0,
            "max_depth_reached": False,
            "max_nodes_reached": False,
            "undetermined_nodes": 0,
            "sva_trigger_cycle": None,
            "sva_time_window": None,
            "sva_window_end_cycle": None,
            "sva_consequent_signals": None
        }
        
        original_endpoint_cycle = endpoint_cycle  # This is the failure cycle
        hierarchy = self._extract_module_hierarchy(endpoint_signal)
        
        # For SVA assertions, try to find the actual trigger cycle
        # where the antecedent became true
        trigger_cycle = self._find_sva_trigger_cycle(endpoint_signal, endpoint_cycle)
        if trigger_cycle is not None:
            self.stats["sva_trigger_cycle"] = trigger_cycle
        
        # Create endpoint node at the FAILURE cycle (not trigger cycle)
        # This is the assertion failure point - causality flows backward from here
        endpoint_node = self._get_or_create_node(endpoint_signal, original_endpoint_cycle, 0)
        if endpoint_node is None:
            return {}, []
        
        endpoint_node.is_endpoint = True
        endpoint_node.suspect_score = 1.0
        
        # If SVA has time window, analyze the causal chain properly
        time_window = self.stats.get("sva_time_window")
        consequent_signals = self.stats.get("sva_consequent_signals")
        
        if time_window is not None and consequent_signals and trigger_cycle is not None:
            # For time-windowed SVA: antecedent |-> ##[min:max] consequent
            # 
            # The causal chain should be:
            # 1. antecedent_signals@trigger_cycle -> ... -> consequent_signals@window
            # 2. consequent_signals@window (being false) -> assertion_fail@failure_cycle
            #
            # So we need to:
            # a) Analyze consequent signals in the window (they are direct causes of failure)
            # b) Then trace back from consequent signals to find why they stayed false
            # c) Also trace the antecedent signals to understand the trigger condition
            
            # First, analyze consequent signals in the time window
            window_nodes = self._analyze_sva_time_window(
                trigger_cycle=trigger_cycle,
                window_end_cycle=original_endpoint_cycle,
                consequent_signals=consequent_signals,
                hierarchy=hierarchy,
                depth=1
            )
            
            # Create edges from window nodes to endpoint (causality: earlier -> later)
            # Only include nodes that are BEFORE or AT the failure cycle
            for window_node in window_nodes:
                if window_node.cycle <= original_endpoint_cycle:
                    edge_key = (window_node.id, endpoint_node.id)
                    if edge_key not in self._edge_set:
                        edge = CausalEdge(
                            src_node_id=window_node.id,
                            dst_node_id=endpoint_node.id,
                            reason=f"{window_node.signal}@{window_node.cycle} stayed false in window [{time_window[0]}:{time_window[1]}]",
                            contribution_type=ContributionType.STATE,
                            contribution_score=0.8,
                            evidence={
                                "type": "sva_time_window",
                                "trigger_cycle": trigger_cycle,
                                "window": time_window,
                                "window_cycle": window_node.cycle,
                                "failure_cycle": original_endpoint_cycle
                            },
                            change_examples=[{
                                "type": "window_consequent",
                                "signal": window_node.signal,
                                "cycle": window_node.cycle,
                                "value": window_node.value,
                                "expected": "should become true within window"
                            }]
                        )
                        self.edges.append(edge)
                        self._edge_set.add(edge_key)
                        self.stats["edges_created"] += 1
            
            # Slice backward from each window node to find root causes
            for window_node in window_nodes:
                if window_node.id not in self.visited:
                    self._slice_node(window_node, window_node.depth)
            
            # Also create a node for the antecedent at trigger cycle and trace back
            if trigger_cycle is not None and trigger_cycle < original_endpoint_cycle:
                # Create antecedent analysis node at trigger cycle
                trigger_node = self._get_or_create_node(endpoint_signal, trigger_cycle, 1)
                if trigger_node is not None and trigger_node.id != endpoint_node.id:
                    # Edge from trigger to failure (trigger happened before failure)
                    edge_key = (trigger_node.id, endpoint_node.id)
                    if edge_key not in self._edge_set:
                        edge = CausalEdge(
                            src_node_id=trigger_node.id,
                            dst_node_id=endpoint_node.id,
                            reason=f"SVA triggered at cycle {trigger_cycle}, failed at cycle {original_endpoint_cycle}",
                            contribution_type=ContributionType.CONDITIONAL,
                            contribution_score=1.0,
                            evidence={
                                "type": "sva_trigger",
                                "trigger_cycle": trigger_cycle,
                                "failure_cycle": original_endpoint_cycle,
                                "window": time_window
                            },
                            change_examples=[]
                        )
                        self.edges.append(edge)
                        self._edge_set.add(edge_key)
                        self.stats["edges_created"] += 1
                    
                    # Slice backward from trigger node
                    if trigger_node.id not in self.visited:
                        self._slice_node(trigger_node, trigger_node.depth)
        else:
            # No time window - just do normal backward slicing from endpoint
            self._slice_node(endpoint_node, 0)
        
        # Mark leaf nodes as roots
        nodes_with_incoming = set(e.dst_node_id for e in self.edges)
        for node in self.nodes.values():
            if node.id not in nodes_with_incoming and not node.is_endpoint:
                node.is_root = True
        
        return self.nodes, self.edges
    
    def get_statistics(self) -> Dict[str, Any]:
        """Get slicing statistics."""
        return self.stats.copy()
