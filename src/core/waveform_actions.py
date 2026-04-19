"""
Waveform action wrappers for pylibfst API integration.
Provides focused debugging actions for LLM to analyze FST traces.

Note: pylibfst is lazily imported to avoid import errors when not in waveform mode.
The library must be built before use: cd pylibfst-cache && python setup.py build_ext --inplace
"""

import sys
import os
import re
import difflib
from typing import Dict, Any, List, Optional

# # Lazy import pylibfst - only import when WaveformActions is instantiated
# # pylibfst type hints will show errors in IDE but will work at runtime
# pylibfst = None  # type: ignore

# def _ensure_pylibfst():
#     """Ensure pylibfst is imported. Raises ImportError if not available."""
#     global pylibfst
#     if pylibfst is None:
#         try:
#             import pylibfst as _pylibfst  # type: ignore
#             pylibfst = _pylibfst
#         except ImportError as e:
#             raise ImportError(
#                 "pylibfst is not available. Please build it first:\n"
#                 "  cd pylibfst-cache && python setup.py build_ext --inplace\n"
#                 f"Original error: {e}"
#             )
import pylibfst


class WaveformActions:
    """Wrapper class for pylibfst operations tailored for LLM debugging workflows."""
    
    def __init__(self, fst_path: str):
        """
        Initialize waveform actions with an FST file.
        
        Args:
            fst_path: Path to the FST waveform file
        """
        # _ensure_pylibfst()  # Ensure pylibfst is loaded
        
        # Validate FST file path
        if not os.path.exists(fst_path):
            raise FileNotFoundError(f"FST file does not exist: {fst_path}")
        if not os.path.isfile(fst_path):
            raise ValueError(f"FST path is not a file: {fst_path}")
        
        self.fst_path = fst_path
        self._open_fst()
        # Cache metadata on initialization
        self.metadata = self.get_metadata()
    
    def _open_fst(self):
        """Open the FST file and cache hierarchy."""
        self.fst = pylibfst.lib.fstReaderOpen(self.fst_path.encode("UTF-8"))
        if self.fst == pylibfst.ffi.NULL:
            raise RuntimeError(f"Failed to open FST file: {self.fst_path}")
        
        # Cache hierarchy for quick lookups
        self.scopes, self.signals = pylibfst.get_scopes_signals2(self.fst)
        self._signal_names = list(self.signals.by_name.keys())
        self._normalized_signal_map: Dict[str, List[str]] = {}
        for name in self._signal_names:
            key = self._normalize_signal_name(name)
            self._normalized_signal_map.setdefault(key, []).append(name)

    @staticmethod
    def _normalize_signal_name(name: str) -> str:
        """Normalize signal names for robust lookup and alias matching."""
        normalized = name.strip()
        normalized = re.sub(r"\s*\[\d+:\d+\]$", "", normalized)
        normalized = normalized.replace("/", ".")
        normalized = normalized.replace(" ", "")
        normalized = normalized.lower()
        return normalized

    def _suggest_signal_names(self, query: str, max_results: int = 5) -> List[str]:
        """Return likely signal name candidates for an unresolved query."""
        query_norm = self._normalize_signal_name(query)
        query_simple = query.strip().lower().replace(" ", "")

        substring_matches = [
            name for name in self._signal_names
            if query_norm in self._normalize_signal_name(name)
            or query_simple in name.lower().replace(" ", "")
        ]

        normalized_names = [self._normalize_signal_name(name) for name in self._signal_names]
        close_norm = difflib.get_close_matches(query_norm, normalized_names, n=max_results, cutoff=0.6)
        close_matches: List[str] = []
        close_norm_set = set(close_norm)
        for name in self._signal_names:
            if self._normalize_signal_name(name) in close_norm_set:
                close_matches.append(name)

        combined = []
        seen = set()
        for name in substring_matches + close_matches:
            if name not in seen:
                seen.add(name)
                combined.append(name)
            if len(combined) >= max_results:
                break

        return combined

    def _resolve_signal(self, signal_name: str) -> Dict[str, Any]:
        """Resolve potentially inexact signal names to exact waveform signal names."""
        if signal_name in self.signals.by_name:
            return {
                "signal": self.signals.by_name[signal_name],
                "resolved_name": signal_name,
                "suggestions": []
            }

        stripped = signal_name.strip()
        if stripped in self.signals.by_name:
            return {
                "signal": self.signals.by_name[stripped],
                "resolved_name": stripped,
                "suggestions": []
            }

        # Common waveform naming style: bus suffix appears as " signal [msb:lsb]"
        bus_style_matches = [
            name for name in self._signal_names
            if name.startswith(f"{stripped} [")
        ]
        if len(bus_style_matches) == 1:
            resolved_name = bus_style_matches[0]
            return {
                "signal": self.signals.by_name[resolved_name],
                "resolved_name": resolved_name,
                "suggestions": []
            }

        # Normalized exact match (ignore bus suffix, spaces, slash/dot variations)
        normalized_query = self._normalize_signal_name(stripped)
        normalized_matches = self._normalized_signal_map.get(normalized_query, [])
        if len(normalized_matches) == 1:
            resolved_name = normalized_matches[0]
            return {
                "signal": self.signals.by_name[resolved_name],
                "resolved_name": resolved_name,
                "suggestions": []
            }

        suggestions = self._suggest_signal_names(stripped, max_results=5)

        if len(normalized_matches) > 1 or len(bus_style_matches) > 1:
            return {
                "signal": None,
                "resolved_name": None,
                "suggestions": suggestions,
                "error": (
                    f"Ambiguous signal name: {signal_name}. "
                    f"Multiple candidates exist; use exact name returned by waveform_find_signals."
                )
            }

        return {
            "signal": None,
            "resolved_name": None,
            "suggestions": suggestions,
            "error": f"Signal not found: {signal_name}"
        }
    
    def close(self):
        """Close the FST file."""
        if self.fst:
            pylibfst.lib.fstReaderClose(self.fst)
            self.fst = None
    
    def __del__(self):
        """Cleanup on deletion."""
        self.close()
    
    def get_metadata(self) -> Dict[str, Any]:
        """
        Get basic metadata about the waveform.
        
        Returns:
            Dictionary with start_time, end_time, var_count, scope_count, timescale
        """
        return {
            "start_time": pylibfst.lib.fstReaderGetStartTime(self.fst),
            "end_time": pylibfst.lib.fstReaderGetEndTime(self.fst),
            "var_count": pylibfst.lib.fstReaderGetVarCount(self.fst),
            "scope_count": pylibfst.lib.fstReaderGetScopeCount(self.fst),
            "timescale": pylibfst.lib.fstReaderGetTimescale(self.fst),
            "date": pylibfst.helpers.string(pylibfst.lib.fstReaderGetDateString(self.fst))
        }
    
    def find_signals(self, pattern: str, regex: bool = False, max_results: int = 50) -> Dict[str, Any]:
        """
        Find signals matching a pattern.
        
        Args:
            pattern: String pattern or regex to match signal names
            regex: If True, treat pattern as regex
            max_results: Maximum number of signals to return (default: 50)
            
        Returns:
            Dictionary with 'signals' list, 'count' (total found), and 'truncated' flag
        """
        matches = []
        total_matches = 0
        for sig in self.signals.by_name.values():
            is_match = False
            if regex:
                if re.search(pattern, sig.name):
                    is_match = True
            else:
                if pattern in sig.name:
                    is_match = True
            
            if is_match:
                total_matches += 1
                if len(matches) < max_results:
                    matches.append({
                        "name": sig.name,
                        "length": sig.length,
                        "handle": sig.handle
                    })
        
        return {
            "signals": matches,
            "count": total_matches,
            "truncated": total_matches > max_results
        }
    
    def get_signal_value_at_time(self, signal_name: str, time: int) -> Optional[str]:
        """
        Get the value of a signal at a specific time.
        
        Args:
            signal_name: Full hierarchical signal name
            time: Simulation time
            
        Returns:
            Signal value as string, or None if signal not found
        """
        resolved = self._resolve_signal(signal_name)
        signal = resolved.get("signal")
        if not signal:
            return None
        
        buf = pylibfst.ffi.new("char[256]")
        value = pylibfst.helpers.string(
            pylibfst.lib.fstReaderGetValueFromHandleAtTime(
                self.fst, time, signal.handle, buf
            )
        )
        return value

    def get_signal_values_at_times(self, signal_names: List[str], times: List[int]) -> Dict[str, Any]:
        """
        Get the values of multiple signals at their corresponding time points.
        signal_names[i] is queried at times[i].
        
        Args:
            signal_names: List of full hierarchical signal names
            times: List of time points, one per signal (same length as signal_names)
            
        Returns:
            Dictionary with 'results' list of {signal_name, time, value} dicts
        """
        if len(signal_names) != len(times):
            return {"error": f"signal_names length ({len(signal_names)}) != times length ({len(times)})"}
        
        buf = pylibfst.ffi.new("char[256]")
        results = []
        failed_count = 0
        for sig_name, t in zip(signal_names, times):
            resolved = self._resolve_signal(sig_name)
            signal = resolved.get("signal")
            resolved_name = resolved.get("resolved_name")
            if signal is None:
                failed_count += 1
                result_item = {
                    "signal_name": sig_name,
                    "time": int(t),
                    "value": None,
                    "error": resolved.get("error", f"Signal not found: {sig_name}")
                }
                suggestions = resolved.get("suggestions") or []
                if suggestions:
                    result_item["suggestions"] = suggestions
                results.append(result_item)
            else:
                value = pylibfst.helpers.string(
                    pylibfst.lib.fstReaderGetValueFromHandleAtTime(
                        self.fst, t, signal.handle, buf
                    )
                )
                result_item = {
                    "signal_name": sig_name,
                    "time": int(t),
                    "value": value
                }
                if resolved_name and resolved_name != sig_name:
                    result_item["resolved_name"] = resolved_name
                results.append(result_item)
        
        return {
            "results": results,
            "count": len(results),
            "failed_count": failed_count,
            "resolved_count": len(results) - failed_count
        }
    
    def trace_signal(self, signal_name: str, start_time: Optional[int] = None, 
                     end_time: Optional[int] = None, max_changes: int = 100) -> Dict[str, Any]:
        """
        Trace a signal's value changes over time.
        
        Args:
            signal_name: Full hierarchical signal name
            start_time: Optional start time (defaults to waveform start)
            end_time: Optional end time (defaults to waveform end)
            max_changes: Maximum number of changes to return
            
        Returns:
            Dictionary with signal info and list of (time, value) changes
        """
        resolved = self._resolve_signal(signal_name)
        signal = resolved.get("signal")
        if not signal:
            result = {"error": resolved.get("error", f"Signal not found: {signal_name}")}
            suggestions = resolved.get("suggestions") or []
            if suggestions:
                result["suggestions"] = suggestions
            return result
        resolved_name = resolved.get("resolved_name", signal_name)
        
        # Set process mask for this signal only
        pylibfst.lib.fstReaderClrFacProcessMaskAll(self.fst)
        pylibfst.lib.fstReaderSetFacProcessMask(self.fst, signal.handle)
        
        # Get timestamps
        timestamps = pylibfst.lib.fstReaderGetTimestamps(self.fst)
        if timestamps.nvals == 0:
            return {"error": "No timestamps found"}
        
        # Filter timestamps
        if start_time is None:
            start_time = timestamps.val[0]
        if end_time is None:
            end_time = timestamps.val[timestamps.nvals - 1]
        
        # Collect value changes
        buf = pylibfst.ffi.new("char[256]")
        changes = []
        total_changes = 0
        prev_value = None
        for ts in range(timestamps.nvals):
            time = timestamps.val[ts]
            if time < start_time:
                continue
            if time > end_time:
                break
            
            value = pylibfst.helpers.string(
                pylibfst.lib.fstReaderGetValueFromHandleAtTime(
                    self.fst, time, signal.handle, buf
                )
            )
            
            # Only count actual changes
            if value != prev_value:
                total_changes += 1
                if len(changes) < max_changes:
                    changes.append({"time": int(time), "value": value})
                prev_value = value
        
        pylibfst.lib.fstReaderFreeTimestamps(timestamps)
        
        return {
            "signal_name": signal_name,
            "resolved_name": resolved_name,
            "length": signal.length,
            "changes": changes,
            "total_changes": total_changes,
            "truncated": total_changes > max_changes
        }
    
    def get_active_signals_at_time(self, time: int, scope_pattern: Optional[str] = None, max_results: int = 100) -> Dict[str, Any]:
        """
        Get all signals that are high (non-zero) at a specific time.
        
        Args:
            time: Simulation time
            scope_pattern: Optional pattern to filter by scope
            max_results: Maximum number of active signals to return (default: 100)
            
        Returns:
            Dictionary with 'active_signals' list, 'count' (total found), and 'truncated' flag
        """
        active = []
        total_active = 0
        buf = pylibfst.ffi.new("char[256]")
        
        # Filter signals first to avoid processing all signals
        signals_to_check = []
        for sig in self.signals.by_name.values():
            if scope_pattern and scope_pattern not in sig.name:
                continue
            signals_to_check.append(sig)
            # Early exit if we have checked enough for counting
            if len(signals_to_check) > max_results * 10:  # Check at most 10x more than max_results
                break
        
        for sig in signals_to_check:
            # Early exit if we have enough results
            if len(active) >= max_results and total_active >= max_results * 2:
                # Estimate total based on what we've seen
                total_active = int(total_active * len(self.signals.by_name) / len(signals_to_check))
                break
            
            try:
                value_str = pylibfst.helpers.string(
                    pylibfst.lib.fstReaderGetValueFromHandleAtTime(
                        self.fst, time, sig.handle, buf
                    )
                )
                
                # Check if signal is active (non-zero)
                if value_str not in ('x', 'z', '', 'X', 'Z'):
                    is_active = False
                    if sig.length == 1:
                        if value_str == '1':
                            is_active = True
                    else:
                        # Multi-bit: check if any bit is 1
                        if '1' in value_str:
                            is_active = True
                    
                    if is_active:
                        total_active += 1
                        if len(active) < max_results:
                            # Make a copy of the value string to avoid memory issues
                            active.append({
                                "name": str(sig.name),
                                "value": str(value_str),
                                "length": int(sig.length)
                            })
            except Exception:
                # Silently skip signals that cause errors
                pass
        
        return {
            "active_signals": active,
            "count": total_active,
            "truncated": total_active > max_results
        }
    
    def compare_signals_at_times(self, signal_names: List[str], 
                                  times: List[int]) -> Dict[str, Any]:
        """
        Compare multiple signals at multiple time points.
        
        Args:
            signal_names: List of signal names
            times: List of time points
            
        Returns:
            Dictionary mapping times to signal values
        """
        results = {}
        buf = pylibfst.ffi.new("char[256]")
        
        for time in times:
            time_results = {}
            for sig_name in signal_names:
                resolved = self._resolve_signal(sig_name)
                signal = resolved.get("signal")
                if signal:
                    value = pylibfst.helpers.string(
                        pylibfst.lib.fstReaderGetValueFromHandleAtTime(
                            self.fst, time, signal.handle, buf
                        )
                    )
                    time_results[sig_name] = value
                else:
                    suggestions = resolved.get("suggestions") or []
                    if suggestions:
                        time_results[sig_name] = f"NOT_FOUND (suggestions: {', '.join(suggestions[:3])})"
                    else:
                        time_results[sig_name] = "NOT_FOUND"
            results[int(time)] = time_results
        
        return results
    
    def find_signal_transitions(self, signal_name: str, from_value: str, 
                                to_value: str, max_matches: int = 50) -> Dict[str, Any]:
        """
        Find times when a signal transitions from one value to another.
        
        Args:
            signal_name: Signal name
            from_value: Value to transition from (e.g., "0")
            to_value: Value to transition to (e.g., "1")
            max_matches: Maximum number of transitions to return
            
        Returns:
            Dictionary with 'transitions' list, 'count' (total found), and 'truncated' flag
        """
        resolved = self._resolve_signal(signal_name)
        signal = resolved.get("signal")
        if not signal:
            result = {
                "transitions": [],
                "count": 0,
                "truncated": False,
                "error": resolved.get("error", f"Signal not found: {signal_name}")
            }
            suggestions = resolved.get("suggestions") or []
            if suggestions:
                result["suggestions"] = suggestions
            return result
        
        pylibfst.lib.fstReaderClrFacProcessMaskAll(self.fst)
        pylibfst.lib.fstReaderSetFacProcessMask(self.fst, signal.handle)
        
        timestamps = pylibfst.lib.fstReaderGetTimestamps(self.fst)
        if timestamps.nvals == 0:
            return {"transitions": [], "count": 0, "truncated": False}
        
        buf = pylibfst.ffi.new("char[256]")
        transitions = []
        total_transitions = 0
        prev_value = None
        
        for ts in range(timestamps.nvals):
            time = timestamps.val[ts]
            value = pylibfst.helpers.string(
                pylibfst.lib.fstReaderGetValueFromHandleAtTime(
                    self.fst, time, signal.handle, buf
                )
            )
            
            if prev_value == from_value and value == to_value:
                total_transitions += 1
                if len(transitions) < max_matches:
                    transitions.append(int(time))
            
            prev_value = value
        
        pylibfst.lib.fstReaderFreeTimestamps(timestamps)
        return {
            "transitions": transitions,
            "count": total_transitions,
            "truncated": total_transitions > max_matches,
            "resolved_name": resolved.get("resolved_name", signal_name)
        }


def execute_waveform_action(action: Dict[str, Any], waveform_actions: WaveformActions) -> Dict[str, Any]:
    """
    Execute a waveform-specific action.
    
    Args:
        action: Action dictionary with type and parameters
        waveform_actions: WaveformActions instance
        
    Returns:
        Result dictionary
    """
    action_type = action.get("type", "")
    result = {"type": action_type}
    
    try:
        if action_type == "waveform_find_signals":
            pattern = action.get("pattern", "")
            regex = action.get("regex", False)
            max_results = action.get("max_results", 50)
            find_result = waveform_actions.find_signals(pattern, regex, max_results)
            result.update(find_result)
            result["success"] = True
        
        elif action_type == "waveform_get_signal_value":
            signal_names = action.get("signal_names", [])
            times = action.get("times", [])
            # Backward compatibility: accept legacy single-signal format
            if not signal_names and "signal_name" in action:
                signal_names = [action["signal_name"]]
                times = [action.get("time", 0)]
            batch_result = waveform_actions.get_signal_values_at_times(signal_names, times)
            if "error" in batch_result:
                result["error"] = batch_result["error"]
                result["success"] = False
            else:
                result["results"] = batch_result["results"]
                result["count"] = batch_result["count"]
                result["failed_count"] = batch_result.get("failed_count", 0)
                result["resolved_count"] = batch_result.get("resolved_count", batch_result["count"])

                if batch_result["count"] == 1:
                    single = batch_result["results"][0]
                    result["signal_name"] = single.get("signal_name")
                    result["time"] = single.get("time")
                    result["value"] = single.get("value")
                    if "resolved_name" in single:
                        result["resolved_name"] = single["resolved_name"]
                    if "suggestions" in single:
                        result["suggestions"] = single["suggestions"]

                if batch_result.get("failed_count", 0) > 0:
                    failed_items = [
                        item for item in batch_result["results"]
                        if item.get("error")
                    ]
                    failed_signals = [item.get("signal_name") for item in failed_items]
                    result["failed_signals"] = failed_signals
                    first_error = failed_items[0].get("error") if failed_items else "Signal lookup failed"
                    result["error"] = (
                        f"{first_error}. Use waveform_find_signals first and then pass exact signal name, "
                        f"including bit range suffix like ' [15:0]' when present."
                    )
                    result["success"] = False
                else:
                    result["success"] = True
        
        elif action_type == "waveform_trace_signal":
            signal_name = action.get("signal_name", "")
            start_time = action.get("start_time")
            end_time = action.get("end_time")
            max_changes = action.get("max_changes", 100)
            trace = waveform_actions.trace_signal(signal_name, start_time, end_time, max_changes)
            result.update(trace)
            result["success"] = "error" not in trace
        
        elif action_type == "waveform_get_active_signals":
            time = action.get("time", 0)
            scope_pattern = action.get("scope_pattern")
            max_results = action.get("max_results", 100)
            active_result = waveform_actions.get_active_signals_at_time(time, scope_pattern, max_results)
            result.update(active_result)
            result["success"] = True
        
        elif action_type == "waveform_compare_signals":
            signal_names = action.get("signal_names", [])
            times = action.get("times", [])
            comparison = waveform_actions.compare_signals_at_times(signal_names, times)
            result["comparison"] = comparison
            result["success"] = True
        
        elif action_type == "waveform_find_transitions":
            signal_name = action.get("signal_name", "")
            from_value = action.get("from_value", "0")
            to_value = action.get("to_value", "1")
            max_matches = action.get("max_matches", 50)
            trans_result = waveform_actions.find_signal_transitions(
                signal_name, from_value, to_value, max_matches
            )
            result.update(trans_result)
            result["success"] = True
        
        else:
            result["error"] = f"Unknown waveform action type: {action_type}"
            result["success"] = False
    
    except Exception as e:
        result["error"] = str(e)
        result["success"] = False
    
    return result
