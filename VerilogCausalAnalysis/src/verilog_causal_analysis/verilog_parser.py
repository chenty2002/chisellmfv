"""
Verilog/SystemVerilog AST Parser using hdlConvertor.

Uses hdlConvertor library for robust parsing of Verilog/SystemVerilog,
extracting signal dependencies for causal graph construction.

Key classes from hdlConvertorAst:
- HdlModuleDef: Module definition with ports and body
- HdlStmProcess: always blocks (sequential/combinational)
- HdlStmAssign: Assignment statements (blocking/non-blocking)
- HdlIdDef: Variable/signal declarations
- HdlOp: Operations and expressions
"""

import os
import re
import tempfile
from dataclasses import dataclass, field
from typing import Dict, List, Set, Tuple, Optional, Any
from enum import Enum

from hdlConvertor import HdlConvertor
from hdlConvertorAst.language import Language
from hdlConvertorAst.hdlAst import (
    HdlContext, HdlModuleDef, HdlModuleDec, HdlIdDef, HdlDirection,
    HdlStmProcess, HdlStmAssign, HdlStmIf, HdlStmCase, HdlStmBlock,
    HdlOp, HdlOpType, HdlValueInt, HdlValueId, HdlCompInst
)


class DependencyType(Enum):
    """Type of dependency between signals."""
    COMBINATIONAL = "combinational"  # Same cycle (assign, always @(*))
    SEQUENTIAL = "sequential"        # Previous cycle (always @(posedge clk))
    STATE = "state"                  # State machine transition
    PORT_INPUT = "port_input"        # Module input port
    PORT_OUTPUT = "port_output"      # Module output port
    WIRE = "wire"                    # Wire declaration
    MEMORY = "memory"                # Memory/array access
    ASSERTION = "assertion"          # SVA assertion dependency


@dataclass
class SignalInfo:
    """Signal metadata in the design."""
    name: str
    signal_type: str  # wire, reg, logic, input, output
    width: int = 1
    is_array: bool = False
    array_size: int = 0
    defined_in_file: str = ""
    defined_at_line: int = 0
    module_name: str = ""

    def __hash__(self):
        return hash((self.name, self.module_name))


@dataclass
class Dependency:
    """Dependency relationship between two signals."""
    source: str
    target: str
    dep_type: DependencyType
    expression: str = ""
    file_path: str = ""
    line_start: int = 0
    line_end: int = 0
    code_snippet: str = ""
    condition: str = ""
    module_name: str = ""
    source_qualified: str = ""
    target_qualified: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return {
            "source": self.source,
            "target": self.target,
            "source_qualified": self.source_qualified,
            "target_qualified": self.target_qualified,
            "type": self.dep_type.value,
            "expression": self.expression,
            "file": self.file_path,
            "lines": [self.line_start, self.line_end],
            "code_snippet": self.code_snippet,
            "condition": self.condition,
            "module": self.module_name
        }


@dataclass
class ModuleInfo:
    """Verilog module information."""
    name: str
    file_path: str
    line_start: int
    line_end: int
    ports: Dict[str, SignalInfo] = field(default_factory=dict)
    signals: Dict[str, SignalInfo] = field(default_factory=dict)
    dependencies: List[Dependency] = field(default_factory=list)
    submodule_instances: List[Tuple[str, str, int]] = field(default_factory=list)


# Operator mapping for expression-to-string conversion
_OP_MAP = {
    HdlOpType.AND: '&', HdlOpType.OR: '|', HdlOpType.XOR: '^',
    HdlOpType.AND_LOG: '&&', HdlOpType.OR_LOG: '||',
    HdlOpType.EQ: '==', HdlOpType.NE: '!=',
    HdlOpType.LT: '<', HdlOpType.GT: '>', HdlOpType.LE: '<=', HdlOpType.GE: '>=',
    HdlOpType.ADD: '+', HdlOpType.SUB: '-', HdlOpType.MUL: '*', HdlOpType.DIV: '/',
    HdlOpType.NEG: '~', HdlOpType.NEG_LOG: '!', HdlOpType.MINUS_UNARY: '-',
    HdlOpType.AND_UNARY: '&', HdlOpType.OR_UNARY: '|', HdlOpType.XOR_UNARY: '^',
    HdlOpType.NAND_UNARY: '~&', HdlOpType.NOR_UNARY: '~|', HdlOpType.XNOR_UNARY: '~^',
    HdlOpType.TERNARY: '?:', HdlOpType.INDEX: '[]',
    HdlOpType.CONCAT: '{}', HdlOpType.SLL: '<<', HdlOpType.SRL: '>>',
    HdlOpType.SLA: '<<<', HdlOpType.SRA: '>>>',
}

_UNARY_OPS = {
    HdlOpType.NEG, HdlOpType.NEG_LOG, HdlOpType.MINUS_UNARY,
    HdlOpType.AND_UNARY, HdlOpType.OR_UNARY, HdlOpType.XOR_UNARY,
    HdlOpType.NAND_UNARY, HdlOpType.NOR_UNARY, HdlOpType.XNOR_UNARY
}

# Keywords to exclude from SVA signal matching
_SVA_KEYWORDS = frozenset({
    'assert', 'property', 'disable', 'iff', 'posedge', 'negedge',
    'if', 'else', 'always', 'assign', 'begin', 'end', 'wire', 'reg',
    'logic', 'input', 'output', 'inout', 'module', 'endmodule',
    'integer', 'parameter', 'localparam', 'genvar', 'for', 'while',
    'case', 'endcase', 'default', 'initial', 'final', 'always_comb',
    'always_ff', 'always_latch', 'unique', 'priority', 'h', 'b', 'd', 'o'
})


class VerilogParser:
    """Verilog/SystemVerilog parser using hdlConvertor.
    
    Extracts module definitions, signal declarations, dependencies,
    and module instantiations for causal graph construction.
    """

    def __init__(self):
        self.converter = HdlConvertor()
        self.modules: Dict[str, ModuleInfo] = {}
        self.all_signals: Dict[str, SignalInfo] = {}
        self.all_dependencies: List[Dependency] = []
        self.instance_module_map: Dict[str, Set[str]] = {}
        self.file_contents: Dict[str, str] = {}
        self.file_lines: Dict[str, List[str]] = {}
        # SVA assertion pattern (fallback when hdlConvertor skips)
        self._re_assert_label = re.compile(
            r'^\s*(\w+)\s*:\s*assert\s+property\s*\(@\([^)]+\)\s*'
            r'(?:disable\s+iff\s*\([^)]+\)\s*)?(.+?)\)',
            re.MULTILINE | re.DOTALL
        )
        self._re_signal_ref = re.compile(
            r'\b([a-zA-Z_$][a-zA-Z0-9_$]*(?:\.[a-zA-Z_$][a-zA-Z0-9_$]*)*)\b'
        )

    def _looks_like_file_ref(self, line: str) -> bool:
        """Detect stray include/resource lines such as "ResetCounter.sv"."""
        stripped = line.strip()
        if not stripped or stripped.startswith('//'):
            return False
        return bool(re.match(r'^[\w./\\-]+\.(sv|v|f)$', stripped))

    def _sanitize_verilog_content(self, content: str) -> str:
        """Strip firtool resource trailers that break hdlConvertor parsing."""
        lines = content.splitlines()
        sanitized: List[str] = []

        for line in lines:
            if 'firrtl_black_box_resource_files.f' in line:
                break  # Drop the marker and everything after it
            sanitized.append(line)

        if not sanitized:
            sanitized = lines  # Fallback: nothing matched

        # Remove trailing resource filenames (e.g., "ResetCounter.sv")
        while sanitized and self._looks_like_file_ref(sanitized[-1]):
            sanitized.pop()

        # Trim trailing blank lines for cleaner snippets
        while sanitized and sanitized[-1].strip() == '':
            sanitized.pop()

        return '\n'.join(sanitized) + '\n'
    
    def _get_position(self, node) -> Tuple[int, int]:
        """Extract line numbers from position attribute (CodePosition or tuple)."""
        if not hasattr(node, 'position') or node.position is None:
            return 0, 0
        pos = node.position
        # CodePosition object has start_line/stop_line attributes
        if hasattr(pos, 'start_line'):
            return pos.start_line, pos.stop_line
        # Tuple format (old style)
        if isinstance(pos, (list, tuple)) and len(pos) >= 3:
            return pos[0], pos[2]
        return 0, 0
    
    def _get_code_snippet(self, file_path: str, line_start: int, line_end: int, max_lines: int = 5) -> str:
        """Get code snippet from file."""
        if file_path not in self.file_lines:
            return ""
        lines = self.file_lines[file_path]
        start = max(0, line_start - 1)
        end = min(len(lines), line_end)
        if end - start > max_lines:
            end = start + max_lines
        return '\n'.join(lines[start:end])
    
    def _get_width_from_type(self, type_node) -> int:
        """Extract bit width from type node."""
        if type_node is None:
            return 1
        if isinstance(type_node, str):
            return 1
        if isinstance(type_node, HdlOp):
            if type_node.fn == HdlOpType.PARAMETRIZATION:
                # Look for DOWNTO in ops
                for op in type_node.ops:
                    if isinstance(op, HdlOp) and op.fn == HdlOpType.DOWNTO:
                        if len(op.ops) >= 2:
                            high = self._get_int_value(op.ops[0])
                            low = self._get_int_value(op.ops[1])
                            if high is not None and low is not None:
                                return abs(high - low) + 1
            elif type_node.fn == HdlOpType.DOWNTO:
                if len(type_node.ops) >= 2:
                    high = self._get_int_value(type_node.ops[0])
                    low = self._get_int_value(type_node.ops[1])
                    if high is not None and low is not None:
                        return abs(high - low) + 1
        return 1
    
    def _get_int_value(self, node) -> Optional[int]:
        """Get integer value from AST node."""
        if isinstance(node, HdlValueInt):
            return int(node.val)
        if isinstance(node, int):
            return node
        return None
    
    def _get_signal_name(self, node) -> Optional[str]:
        """Extract signal name from AST node."""
        if isinstance(node, str):
            return node
        if isinstance(node, HdlValueId):
            return node.val
        if isinstance(node, HdlOp):
            # For indexed access like sig[0], return base signal
            if node.fn == HdlOpType.INDEX and node.ops:
                return self._get_signal_name(node.ops[0])
        return None

    @staticmethod
    def _strip_width(signal_name: str) -> str:
        """Remove FST-style trailing width suffix from a signal name."""
        return re.sub(r'\s*\[\d+:\d+\]$', '', signal_name.strip())

    @classmethod
    def _base_signal_name(cls, signal_name: str) -> str:
        """Return the final signal component from a hierarchical name."""
        return cls._strip_width(signal_name).split('.')[-1]

    @classmethod
    def _hierarchy_parts(cls, signal_name: str) -> List[str]:
        clean = cls._strip_width(signal_name)
        parts = [p for p in clean.split('.') if p]
        return parts[:-1] if len(parts) > 1 else []

    def _qualify_signal(self, module: ModuleInfo, signal_name: str) -> str:
        """Qualify a local signal with its defining module unless already qualified."""
        clean = self._strip_width(signal_name)
        if not clean:
            return clean
        if clean.startswith(module.name + '.'):
            return clean
        if '.' in clean:
            return clean
        return f"{module.name}.{clean}"

    def infer_module_from_signal(self, signal_name: str, hierarchy: Optional[str] = None) -> Optional[str]:
        """
        Infer the RTL module that owns a waveform signal.

        FST names often use instance hierarchy rather than module names. We use
        parsed instance->module mappings and exact top-level module prefixes to
        choose a narrower dependency set than a global short-name lookup.
        """
        candidates: List[str] = []
        if hierarchy:
            candidates.extend([p for p in hierarchy.split('.') if p])
        candidates.extend(self._hierarchy_parts(signal_name))

        for part in reversed(candidates):
            if part in self.modules:
                return part
            mapped = self.instance_module_map.get(part)
            if mapped and len(mapped) == 1:
                return next(iter(mapped))

        clean = self._strip_width(signal_name)
        if '.' in clean:
            first = clean.split('.', 1)[0]
            if first in self.modules:
                return first
        return None

    def _make_dependency(
        self,
        module: ModuleInfo,
        source: str,
        target: str,
        dep_type: DependencyType,
        expression: str = "",
        file_path: str = "",
        line_start: int = 0,
        line_end: int = 0,
        code_snippet: str = "",
        condition: str = "",
    ) -> Dependency:
        """Create a dependency with both short and module-qualified names."""
        return Dependency(
            source=source,
            target=target,
            dep_type=dep_type,
            expression=expression,
            file_path=file_path,
            line_start=line_start,
            line_end=line_end,
            code_snippet=code_snippet,
            condition=condition,
            module_name=module.name,
            source_qualified=self._qualify_signal(module, source),
            target_qualified=self._qualify_signal(module, target),
        )

    def _add_dependency(self, module: ModuleInfo, dep: Dependency) -> None:
        """Add a dependency to module-local and global registries."""
        module.dependencies.append(dep)
        self.all_dependencies.append(dep)

    def _get_port_direction(self, module_name: str, port_name: str) -> Optional[str]:
        """Look up a submodule port direction if that module has been parsed."""
        mod = self.modules.get(module_name)
        if not mod:
            return None
        port = mod.ports.get(port_name)
        return port.signal_type if port else None

    @staticmethod
    def _is_input_direction(direction: Optional[str]) -> bool:
        return direction in {"in", "input"}

    @staticmethod
    def _is_output_direction(direction: Optional[str]) -> bool:
        return direction in {"out", "output"}

    def _extract_signals_from_text(self, text: str) -> Set[str]:
        """Extract likely signal references from a Verilog/SVA expression string."""
        signals: Set[str] = set()
        if not text:
            return signals
        text = re.sub(r'//.*', '', text)
        text = re.sub(r'/\*.*?\*/', '', text, flags=re.DOTALL)
        for sig_match in self._re_signal_ref.finditer(text):
            sig = sig_match.group(1)
            if sig.lower() in _SVA_KEYWORDS:
                continue
            if sig.isdigit() or re.fullmatch(r'[0-9]+', sig):
                continue
            if re.fullmatch(r'[0-9]+[bhdoBHDO]?[0-9a-fA-F_xXzZ]*', sig):
                continue
            if re.fullmatch(r'[bhdoxzBHDOXZ][0-9a-fA-F_xXzZ]*', sig):
                continue
            signals.add(sig)
        return signals
    
    def _extract_signals_from_expr(self, node, signals: Optional[Set[str]] = None) -> Set[str]:
        """Recursively extract signal names from expression."""
        if signals is None:
            signals = set()
        
        if isinstance(node, str):
            signals.add(node)
        elif isinstance(node, HdlValueId):
            signals.add(node.val)
        elif isinstance(node, HdlOp):
            for op in node.ops:
                self._extract_signals_from_expr(op, signals)
        elif isinstance(node, (list, tuple)):
            for item in node:
                self._extract_signals_from_expr(item, signals)
        
        return signals
    
    def _expr_to_string(self, node, depth: int = 0) -> str:
        """Convert AST expression to readable string."""
        if depth > 10:  # Prevent infinite recursion
            return "..."
        
        if node is None:
            return ""
        if isinstance(node, str):
            return node
        if isinstance(node, HdlValueId):
            return node.val
        if isinstance(node, HdlValueInt):
            if hasattr(node, 'bits') and node.bits:
                return f"{node.bits}'h{node.val}"
            return str(node.val)
        if isinstance(node, HdlOp):
            fn_name = _OP_MAP.get(node.fn, str(node.fn).split('.')[-1])
            ops = node.ops
            
            if node.fn == HdlOpType.TERNARY and len(ops) >= 3:
                return f"({self._expr_to_string(ops[0], depth+1)} ? {self._expr_to_string(ops[1], depth+1)} : {self._expr_to_string(ops[2], depth+1)})"
            if node.fn == HdlOpType.INDEX and len(ops) >= 2:
                return f"{self._expr_to_string(ops[0], depth+1)}[{self._expr_to_string(ops[1], depth+1)}]"
            if node.fn == HdlOpType.CONCAT:
                return "{" + ", ".join(self._expr_to_string(op, depth+1) for op in ops) + "}"
            if node.fn in _UNARY_OPS and ops:
                return f"{fn_name}{self._expr_to_string(ops[0], depth+1)}"
            if len(ops) == 2:
                return f"({self._expr_to_string(ops[0], depth+1)} {fn_name} {self._expr_to_string(ops[1], depth+1)})"
            return f"{fn_name}({', '.join(self._expr_to_string(op, depth+1) for op in ops)})"
        return str(type(node).__name__)
    
    def _is_sequential_process(self, process: HdlStmProcess) -> bool:
        """Check if process is sequential (posedge/negedge triggered)."""
        if not hasattr(process, 'sensitivity') or not process.sensitivity:
            return False
        
        for sens in process.sensitivity:
            if isinstance(sens, HdlOp):
                if sens.fn in (HdlOpType.RISING, HdlOpType.FALLING):
                    return True
        return False
    
    def _get_clock_signal(self, process: HdlStmProcess) -> Optional[str]:
        """Get clock signal name from sequential process."""
        if not hasattr(process, 'sensitivity') or not process.sensitivity:
            return None
        
        for sens in process.sensitivity:
            if isinstance(sens, HdlOp):
                if sens.fn in (HdlOpType.RISING, HdlOpType.FALLING) and sens.ops:
                    return self._get_signal_name(sens.ops[0])
        return None
    
    def _process_assignment(self, assign, module: ModuleInfo, file_path: str,
                            is_sequential: bool, condition: str = "",
                            condition_sources: Optional[Set[str]] = None):
        """Process an assignment statement and extract dependencies."""
        condition_sources = condition_sources or set()
        if isinstance(assign, HdlStmAssign):
            target = self._get_signal_name(assign.dst)
            if not target:
                return
            
            sources = self._extract_signals_from_expr(assign.src)
            expr_str = self._expr_to_string(assign.src)
            
            line_start, line_end = self._get_position(assign)
            
            dep_type = DependencyType.SEQUENTIAL if is_sequential else DependencyType.COMBINATIONAL
            
            for source in sources:
                if source != target:  # Avoid self-loops
                    dep = self._make_dependency(
                        module=module,
                        source=source,
                        target=target,
                        dep_type=dep_type,
                        expression=expr_str,
                        file_path=file_path,
                        line_start=line_start,
                        line_end=line_end,
                        code_snippet=self._get_code_snippet(file_path, line_start, line_end),
                        condition=condition
                    )
                    self._add_dependency(module, dep)

            for source in condition_sources - sources:
                if source != target:
                    dep = self._make_dependency(
                        module=module,
                        source=source,
                        target=target,
                        dep_type=dep_type,
                        expression=condition or expr_str,
                        file_path=file_path,
                        line_start=line_start,
                        line_end=line_end,
                        code_snippet=self._get_code_snippet(file_path, line_start, line_end),
                        condition=condition
                    )
                    self._add_dependency(module, dep)
        
        elif isinstance(assign, HdlOp) and assign.fn == HdlOpType.ASSIGN:
            # Blocking assignment as HdlOp
            if len(assign.ops) >= 2:
                target = self._get_signal_name(assign.ops[0])
                if not target:
                    return
                
                sources = self._extract_signals_from_expr(assign.ops[1])
                expr_str = self._expr_to_string(assign.ops[1])
                
                dep_type = DependencyType.SEQUENTIAL if is_sequential else DependencyType.COMBINATIONAL
                
                for source in sources:
                    if source != target:
                        dep = self._make_dependency(
                            module=module,
                            source=source,
                            target=target,
                            dep_type=dep_type,
                            expression=expr_str,
                            file_path=file_path,
                            line_start=0,
                            line_end=0,
                            condition=condition
                        )
                        self._add_dependency(module, dep)

                for source in condition_sources - sources:
                    if source != target:
                        dep = self._make_dependency(
                            module=module,
                            source=source,
                            target=target,
                            dep_type=dep_type,
                            expression=condition or expr_str,
                            file_path=file_path,
                            line_start=0,
                            line_end=0,
                            condition=condition
                        )
                        self._add_dependency(module, dep)
    
    def _process_statement(self, stmt, module: ModuleInfo, file_path: str,
                           is_sequential: bool, condition: str = "",
                           condition_sources: Optional[Set[str]] = None):
        """Recursively process statements to extract dependencies."""
        condition_sources = condition_sources or set()
        if isinstance(stmt, HdlStmAssign):
            self._process_assignment(stmt, module, file_path, is_sequential, condition, condition_sources)
        
        elif isinstance(stmt, HdlOp) and stmt.fn == HdlOpType.ASSIGN:
            self._process_assignment(stmt, module, file_path, is_sequential, condition, condition_sources)
        
        elif isinstance(stmt, HdlStmIf):
            # Extract condition
            cond_str = self._expr_to_string(stmt.cond) if stmt.cond else ""
            new_condition = f"{condition} && {cond_str}" if condition else cond_str
            new_condition_sources = set(condition_sources)
            if stmt.cond is not None:
                new_condition_sources.update(self._extract_signals_from_expr(stmt.cond))
            
            # Process if-true branch
            if stmt.if_true:
                self._process_statement(
                    stmt.if_true, module, file_path, is_sequential,
                    new_condition, new_condition_sources
                )
            
            # Process elif branches
            if hasattr(stmt, 'elifs') and stmt.elifs:
                for elif_cond, elif_body in stmt.elifs:
                    elif_cond_str = self._expr_to_string(elif_cond)
                    elif_sources = set(condition_sources)
                    elif_sources.update(self._extract_signals_from_expr(elif_cond))
                    self._process_statement(
                        elif_body, module, file_path, is_sequential,
                        f"{condition} && {elif_cond_str}" if condition else elif_cond_str,
                        elif_sources
                    )
            
            # Process else branch
            if stmt.if_false:
                neg_condition = f"!({cond_str})" if cond_str else ""
                self._process_statement(
                    stmt.if_false, module, file_path, is_sequential,
                    f"{condition} && {neg_condition}" if condition else neg_condition,
                    new_condition_sources
                )
        
        elif isinstance(stmt, HdlStmBlock):
            for sub_stmt in stmt.body:
                self._process_statement(
                    sub_stmt, module, file_path, is_sequential,
                    condition, condition_sources
                )
        
        elif isinstance(stmt, HdlStmCase):
            # Process case statement
            case_expr = getattr(stmt, 'switch_on', None)
            case_expr_str = self._expr_to_string(case_expr) if case_expr is not None else "case"
            case_sources = set(condition_sources)
            if case_expr is not None:
                case_sources.update(self._extract_signals_from_expr(case_expr))
            if hasattr(stmt, 'cases'):
                for case_val, case_body in stmt.cases:
                    case_cond = self._expr_to_string(case_val) if case_val else "default"
                    full_case_cond = f"{case_expr_str}=={case_cond}" if case_cond != "default" else "default"
                    self._process_statement(
                        case_body, module, file_path, is_sequential,
                        f"{condition} && {full_case_cond}" if condition else full_case_cond,
                        case_sources
                    )
        
        elif isinstance(stmt, HdlIdDef):
            # Local variable declaration, might have initial value
            pass
        
        elif isinstance(stmt, (list, tuple)):
            for sub_stmt in stmt:
                self._process_statement(
                    sub_stmt, module, file_path, is_sequential,
                    condition, condition_sources
                )
    
    def parse_file(self, file_path: str) -> List[ModuleInfo]:
        """
        Parse a Verilog/SystemVerilog file.
        
        Args:
            file_path: Path to file
            
        Returns:
            List of ModuleInfo for each module
        """
        if not os.path.exists(file_path):
            raise FileNotFoundError(f"File not found: {file_path}")
        
        # Read and sanitize file content for parsing/snippets
        with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
            original_content = f.read()
        sanitized_content = self._sanitize_verilog_content(original_content)
        content = sanitized_content

        self.file_contents[file_path] = content
        self.file_lines[file_path] = content.split('\n')

        parse_path = file_path
        temp_path = None
        if sanitized_content != original_content:
            # Write a temporary sanitized copy to keep parser happy while
            # preserving original file paths in the metadata we emit.
            fd, temp_path = tempfile.mkstemp(
                suffix=os.path.splitext(file_path)[1],
                dir=os.path.dirname(file_path)
            )
            with os.fdopen(fd, 'w', encoding='utf-8') as tmp:
                tmp.write(sanitized_content)
            parse_path = temp_path
        
        # Determine language
        lang = Language.SYSTEM_VERILOG if file_path.endswith('.sv') else Language.VERILOG
        
        # Parse with hdlConvertor
        try:
            context = self.converter.parse([parse_path], lang, [os.path.dirname(file_path)], debug=False)
        except Exception as e:
            print(f"Warning: Failed to parse {file_path}: {e}")
            return []
        finally:
            if temp_path and os.path.exists(temp_path):
                try:
                    os.remove(temp_path)
                except OSError:
                    pass
        
        modules = []
        
        for obj in context.objs:
            if isinstance(obj, HdlModuleDef):
                module_name = obj.module_name.val if hasattr(obj.module_name, 'val') else str(obj.module_name)
                
                # Get position
                line_start, line_end = self._get_position(obj.dec) if obj.dec else (0, 0)
                
                module = ModuleInfo(
                    name=module_name,
                    file_path=file_path,
                    line_start=line_start,
                    line_end=line_end
                )
                
                # Extract ports
                if obj.dec and hasattr(obj.dec, 'ports'):
                    for port in obj.dec.ports:
                        port_name = port.name.val if hasattr(port.name, 'val') else str(port.name)
                        direction = str(port.direction).split('.')[-1].lower()
                        width = self._get_width_from_type(port.type)
                        
                        port_line, _ = self._get_position(port)
                        
                        sig = SignalInfo(
                            name=port_name,
                            signal_type=direction,
                            width=width,
                            defined_in_file=file_path,
                            defined_at_line=port_line,
                            module_name=module_name
                        )
                        module.ports[port_name] = sig
                        module.signals[port_name] = sig
                
                # Process body
                for body_obj in obj.objs:
                    if isinstance(body_obj, HdlIdDef):
                        # Signal declaration
                        sig_name = body_obj.name.val if hasattr(body_obj.name, 'val') else str(body_obj.name)
                        sig_type = "reg" if body_obj.direction == HdlDirection.INTERNAL else "wire"
                        width = self._get_width_from_type(body_obj.type)
                        
                        sig_line, _ = self._get_position(body_obj)
                        
                        sig = SignalInfo(
                            name=sig_name,
                            signal_type=sig_type,
                            width=width,
                            defined_in_file=file_path,
                            defined_at_line=sig_line,
                            module_name=module_name
                        )
                        module.signals[sig_name] = sig
                        
                        # Handle wire/reg declarations with initialization expressions
                        # e.g., wire _eating_count_T = _ph0_io_out == 2'h2;
                        if body_obj.value is not None:
                            sources = self._extract_signals_from_expr(body_obj.value)
                            expr_str = self._expr_to_string(body_obj.value)
                            
                            for source in sources:
                                if source != sig_name:
                                    dep = self._make_dependency(
                                        module=module,
                                        source=source,
                                        target=sig_name,
                                        dep_type=DependencyType.COMBINATIONAL,
                                        expression=expr_str,
                                        file_path=file_path,
                                        line_start=sig_line,
                                        line_end=sig_line,
                                        code_snippet=self._get_code_snippet(file_path, sig_line, sig_line)
                                    )
                                    self._add_dependency(module, dep)
                    
                    elif isinstance(body_obj, HdlStmProcess):
                        # Always block
                        is_seq = self._is_sequential_process(body_obj)
                        self._process_statement(body_obj.body, module, file_path, is_seq)
                    
                    elif isinstance(body_obj, HdlStmAssign):
                        # Continuous assignment
                        self._process_assignment(body_obj, module, file_path, is_sequential=False)
                    
                    elif isinstance(body_obj, HdlCompInst):
                        # Module instantiation
                        inst_name = str(getattr(body_obj.name, 'val', body_obj.name))
                        mod_name = str(getattr(body_obj.module_name, 'val', body_obj.module_name))
                        inst_line, _ = self._get_position(body_obj)
                        module.submodule_instances.append((inst_name, mod_name, inst_line))
                        self.instance_module_map.setdefault(inst_name, set()).add(mod_name)
                        
                        # Process port connections to extract dependencies
                        # For output ports: submodule.io_out -> connected_wire
                        if hasattr(body_obj, 'port_map') and body_obj.port_map:
                            for port_conn in body_obj.port_map:
                                # Port connection: (port_name, connected_signal)
                                if isinstance(port_conn, HdlOp):
                                    # Named port connection: .port_name(signal)
                                    port_name = None
                                    connected_signal = None
                                    if len(port_conn.ops) >= 2:
                                        port_name = self._get_signal_name(port_conn.ops[0])
                                        connected_signal = self._get_signal_name(port_conn.ops[1])
                                    
                                    if port_name and connected_signal:
                                        # Create direction-aware hierarchical dependency:
                                        # - input ports:  parent signal -> child instance port
                                        # - output ports: child instance port -> parent signal
                                        inst_port = f"{inst_name}.{port_name}"
                                        direction = self._get_port_direction(mod_name, port_name)
                                        port_deps: List[Tuple[str, str, DependencyType]] = []
                                        if self._is_input_direction(direction):
                                            port_deps.append((connected_signal, inst_port, DependencyType.PORT_INPUT))
                                        elif self._is_output_direction(direction):
                                            port_deps.append((inst_port, connected_signal, DependencyType.PORT_OUTPUT))
                                        else:
                                            # Unknown or inout: keep both directions so downstream waveform
                                            # evidence can disambiguate rather than dropping a possible cause.
                                            port_deps.append((connected_signal, inst_port, DependencyType.PORT_INPUT))
                                            port_deps.append((inst_port, connected_signal, DependencyType.PORT_OUTPUT))

                                        for source, target, dep_type in port_deps:
                                            dep = self._make_dependency(
                                                module=module,
                                                source=source,
                                                target=target,
                                                dep_type=dep_type,
                                                expression=f".{port_name}({connected_signal})",
                                                file_path=file_path,
                                                line_start=inst_line,
                                                line_end=inst_line,
                                                code_snippet=self._get_code_snippet(file_path, inst_line, inst_line)
                                            )
                                            self._add_dependency(module, dep)
                
                modules.append(module)
                self.modules[module_name] = module
                
                # Add to global registry
                for sig_name, sig in module.signals.items():
                    full_name = f"{module_name}.{sig_name}"
                    self.all_signals[full_name] = sig
        
        # Parse SVA assertions (hdlConvertor may skip these)
        self._parse_sva_assertions(file_path, modules)
        
        return modules
    
    @staticmethod
    def _find_matching_paren(text: str, open_idx: int) -> int:
        """Find the matching ')' for text[open_idx] == '(' while respecting nesting."""
        depth = 0
        i = open_idx
        in_line_comment = False
        in_block_comment = False
        while i < len(text):
            ch = text[i]
            nxt = text[i + 1] if i + 1 < len(text) else ''

            if in_line_comment:
                if ch == '\n':
                    in_line_comment = False
                i += 1
                continue
            if in_block_comment:
                if ch == '*' and nxt == '/':
                    in_block_comment = False
                    i += 2
                else:
                    i += 1
                continue
            if ch == '/' and nxt == '/':
                in_line_comment = True
                i += 2
                continue
            if ch == '/' and nxt == '*':
                in_block_comment = True
                i += 2
                continue

            if ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
                if depth == 0:
                    return i
            i += 1
        return -1

    def _iter_sva_assertions(self, content: str) -> List[Dict[str, Any]]:
        """Return labeled assert-property blocks using balanced parentheses."""
        assertions: List[Dict[str, Any]] = []
        pattern = re.compile(
            r'(?m)^\s*(?:(?P<label>[a-zA-Z_$][a-zA-Z0-9_$]*)\s*:\s*)?assert\s+property\s*\('
        )
        for match in pattern.finditer(content):
            open_idx = content.find('(', match.end() - 1)
            if open_idx < 0:
                continue
            close_idx = self._find_matching_paren(content, open_idx)
            if close_idx < 0:
                continue
            label = match.group('label') or f"assert_{len(assertions)}"
            inner = content[open_idx + 1:close_idx].strip()
            line_start = content[:match.start()].count('\n') + 1
            line_end = content[:close_idx].count('\n') + 1
            assertions.append({
                "label": label,
                "inner": inner,
                "line_start": line_start,
                "line_end": line_end,
                "start_pos": match.start(),
            })
        return assertions

    def _parse_sva_inner(self, inner: str) -> Tuple[str, str, Optional[str], str]:
        """
        Split assert-property inner text into clock edge, clock signal,
        optional disable condition, and property body.
        """
        text = inner.strip()
        clock_edge = ""
        clock_signal = ""
        disable_cond: Optional[str] = None

        if text.startswith('@'):
            at_open = text.find('(')
            if at_open >= 0:
                at_close = self._find_matching_paren(text, at_open)
                if at_close >= 0:
                    event = text[at_open + 1:at_close].strip()
                    event_match = re.search(
                        r'\b(posedge|negedge)\s+([a-zA-Z_$][a-zA-Z0-9_.$]*)\b',
                        event
                    )
                    if event_match:
                        clock_edge = event_match.group(1)
                        clock_signal = event_match.group(2)
                    text = text[at_close + 1:].strip()

        disable_match = re.match(r'disable\s+iff\s*\(', text, flags=re.IGNORECASE)
        if disable_match:
            open_idx = text.find('(', disable_match.start())
            close_idx = self._find_matching_paren(text, open_idx)
            if close_idx >= 0:
                disable_cond = text[open_idx + 1:close_idx].strip()
                text = text[close_idx + 1:].strip()

        return clock_edge, clock_signal, disable_cond, text

    def _parse_sva_assertions(self, file_path: str, modules: List[ModuleInfo]):
        """
        Parse SVA (SystemVerilog Assertion) statements from file.
        
        hdlConvertor may skip SVA, so we use regex to supplement.
        Creates dependencies from assertion antecedent signals to assertion label.
        
        Args:
            file_path: Path to Verilog file
            modules: List of modules parsed from file
        """
        if file_path not in self.file_contents:
            return
        
        content = self.file_contents[file_path]

        for assertion in self._iter_sva_assertions(content):
            label = assertion["label"]
            line_num = assertion["line_start"]
            line_end = assertion["line_end"]
            clock_edge, clock_signal, _disable_cond, property_body = self._parse_sva_inner(
                assertion["inner"]
            )
            
            # Signals to ignore (assertion-helper signals for reset)
            _ignored_sva_signals = {'hasBeenReset', 'hasBeenResetReg', 'reset'}
            
            # Extract signal references from property body
            all_signals = {
                sig for sig in self._extract_signals_from_text(property_body)
                if self._base_signal_name(sig) not in _ignored_sva_signals
            }
            
            # Skip disable condition signals (they are typically reset-related
            # and not causal to the assertion failure)
            
            # Find which module this assertion belongs to
            target_module = None
            for module in modules:
                if module.line_start <= line_num <= module.line_end:
                    target_module = module
                    break
            
            if target_module is None and modules:
                # Default to last module if can't determine
                target_module = modules[-1]
            
            if target_module is None:
                continue
            
            # Add assertion as a signal
            assertion_sig = SignalInfo(
                name=label,
                signal_type="assertion",
                width=1,
                defined_in_file=file_path,
                defined_at_line=line_num,
                module_name=target_module.name
            )
            target_module.signals[label] = assertion_sig
            self.all_signals[f"{target_module.name}.{label}"] = assertion_sig
            
            # Create dependencies from antecedent signals to assertion
            for sig in all_signals:
                dep = self._make_dependency(
                    module=target_module,
                    source=sig,
                    target=label,
                    dep_type=DependencyType.ASSERTION,
                    expression=' '.join(property_body.split()),
                    file_path=file_path,
                    line_start=line_num,
                    line_end=line_end,
                    code_snippet=self._get_code_snippet(file_path, line_num, line_end),
                    condition=f"@({clock_edge} {clock_signal})"
                )
                self._add_dependency(target_module, dep)
    
    def parse_files(self, file_paths: List[str]) -> Dict[str, ModuleInfo]:
        """Parse multiple files."""
        for file_path in file_paths:
            try:
                self.parse_file(file_path)
            except Exception as e:
                print(f"Warning: Failed to parse {file_path}: {e}")
        return self.modules
    
    def _dependency_matches_target(
        self,
        dep: Dependency,
        signal_name: str,
        module_name: Optional[str],
    ) -> bool:
        clean = self._strip_width(signal_name)
        base = self._base_signal_name(clean)
        dep_target_clean = self._strip_width(dep.target)
        dep_target_base = self._base_signal_name(dep.target)
        dep_target_qualified = self._strip_width(dep.target_qualified)

        if clean in {dep_target_clean, dep_target_qualified}:
            return True

        if '.' in clean and clean.endswith('.' + dep_target_clean):
            return True

        if module_name and dep.module_name and dep.module_name != module_name:
            return False

        if module_name and dep_target_base == base:
            return True

        return dep_target_base == base and not module_name

    def get_dependencies_for_signal(self, signal_name: str, module_name: Optional[str] = None) -> List[Dependency]:
        """Get all dependencies where signal is the target."""
        inferred_module = module_name or self.infer_module_from_signal(signal_name)
        deps = []
        for dep in self.all_dependencies:
            if self._dependency_matches_target(dep, signal_name, inferred_module):
                deps.append(dep)
        return deps
    
    def get_signal_sources(self, signal_name: str, module_name: Optional[str] = None) -> List[Tuple[str, DependencyType]]:
        """Get all signals that are sources for a given signal."""
        sources = []
        for dep in self.get_dependencies_for_signal(signal_name, module_name):
            sources.append((dep.source, dep.dep_type))
        return sources
    
    def build_dependency_graph(self) -> Dict[str, List[Tuple[str, DependencyType, Dependency]]]:
        """Build complete target -> [(source, type, dep)] graph."""
        graph: Dict[str, List[Tuple[str, DependencyType, Dependency]]] = {}
        for dep in self.all_dependencies:
            for target_key in {dep.target, dep.target_qualified}:
                if not target_key:
                    continue
                if target_key not in graph:
                    graph[target_key] = []
                graph[target_key].append((dep.source, dep.dep_type, dep))
        return graph
    
    def get_rtl_context(self, signal_name: str, module_name: Optional[str] = None) -> Dict[str, Any]:
        """Get RTL context for a signal."""
        inferred_module = module_name or self.infer_module_from_signal(signal_name)
        base_signal = self._base_signal_name(signal_name)
        context = {
            "signal_name": signal_name,
            "found": False,
            "definition": None,
            "dependencies": [],
            "rtl_refs": []
        }
        
        # Find signal in modules
        for mod_name, mod_info in self.modules.items():
            if inferred_module and mod_name != inferred_module:
                continue
            
            local_name = signal_name if signal_name in mod_info.signals else base_signal
            if local_name in mod_info.signals:
                sig = mod_info.signals[local_name]
                context["found"] = True
                context["definition"] = {
                    "type": sig.signal_type,
                    "width": sig.width,
                    "module": mod_name,
                    "file": sig.defined_in_file,
                    "line": sig.defined_at_line
                }
                context["rtl_refs"].append({
                    "file": sig.defined_in_file,
                    "line": sig.defined_at_line,
                    "type": "definition"
                })
        
        # Get dependencies
        deps = self.get_dependencies_for_signal(signal_name, inferred_module)
        for dep in deps:
            context["dependencies"].append(dep.to_dict())
            context["rtl_refs"].append({
                "file": dep.file_path,
                "line": dep.line_start,
                "type": dep.dep_type.value
            })
        
        return context
