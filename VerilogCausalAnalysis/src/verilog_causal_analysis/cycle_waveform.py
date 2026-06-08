"""
Cycle-aligned Waveform Parser for Causal Graph Construction.

Parses FST waveform files and aligns signal values to clock cycles.
Provides discrete value(signal, cycle) table for causal analysis.

Key Features:
- Clock edge detection for cycle boundary identification
- Efficient signal value caching with O(1) lookup after first access
- Binary search for time-to-cycle conversion
"""

import os
from bisect import bisect_right
from dataclasses import dataclass, field
from typing import Dict, List, Tuple, Optional, Any
import pylibfst


@dataclass
class SignalTransition:
    """A signal value transition."""
    signal_name: str
    time: int
    cycle: int
    old_value: str
    new_value: str


@dataclass
class CycleSnapshot:
    """Snapshot of all signal values at a specific cycle."""
    cycle: int
    time_start: int
    time_end: int
    values: Dict[str, str] = field(default_factory=dict)


class CycleAlignedWaveform:
    """
    Waveform parser that aligns signal values to clock cycles.
    
    Uses rising edges of a specified clock signal to define cycle boundaries.
    Provides discrete value(signal, cycle) lookups for causal analysis.
    """
    
    def __init__(self, fst_path: str, clock_signal: str = "clock"):
        """
        Initialize cycle-aligned waveform parser.
        
        Args:
            fst_path: Path to FST waveform file
            clock_signal: Full hierarchical name of clock signal (e.g., "TestTop.clock")
        """
        if not os.path.exists(fst_path):
            raise FileNotFoundError(f"FST file not found: {fst_path}")
        
        self.fst_path = fst_path
        self.clock_signal = clock_signal
        
        # Open FST file
        self.fst = pylibfst.lib.fstReaderOpen(fst_path.encode("UTF-8"))
        if self.fst == pylibfst.ffi.NULL:
            raise RuntimeError(f"Failed to open FST file: {fst_path}")
        
        # Get scopes and signals
        self.scopes, self.signals = pylibfst.get_scopes_signals2(self.fst)
        
        # Get metadata
        self.start_time = pylibfst.lib.fstReaderGetStartTime(self.fst)
        self.end_time = pylibfst.lib.fstReaderGetEndTime(self.fst)
        self.timescale = pylibfst.lib.fstReaderGetTimescale(self.fst)
        
        # Cycle boundaries (list of rising edge times)
        self._cycle_boundaries: List[int] = []
        self._cycle_count: int = 0
        
        # Cached values: (signal_name, cycle) -> value
        self._value_cache: Dict[Tuple[str, int], str] = {}
        
        # Initialize cycle boundaries
        self._build_cycle_boundaries()
    
    def _build_cycle_boundaries(self):
        """Build cycle boundaries from clock rising edges."""
        clock = self.signals.by_name.get(self.clock_signal)
        if not clock:
            # Try to find clock with partial match
            for sig_name in self.signals.by_name.keys():
                if 'clock' in sig_name.lower() or 'clk' in sig_name.lower():
                    clock = self.signals.by_name[sig_name]
                    self.clock_signal = sig_name
                    break
        
        if not clock:
            raise ValueError(f"Clock signal not found: {self.clock_signal}")
        
        # Set process mask for clock
        pylibfst.lib.fstReaderClrFacProcessMaskAll(self.fst)
        pylibfst.lib.fstReaderSetFacProcessMask(self.fst, clock.handle)
        
        # Get timestamps
        timestamps = pylibfst.lib.fstReaderGetTimestamps(self.fst)
        if timestamps.nvals == 0:
            pylibfst.lib.fstReaderFreeTimestamps(timestamps)
            raise RuntimeError("No timestamps found in waveform")
        
        buf = pylibfst.ffi.new("char[256]")
        prev_value = None
        
        for ts in range(timestamps.nvals):
            time = timestamps.val[ts]
            value = pylibfst.helpers.string(
                pylibfst.lib.fstReaderGetValueFromHandleAtTime(
                    self.fst, time, clock.handle, buf
                )
            )
            
            # Detect rising edge (0 -> 1 or x -> 1)
            if value == '1' and prev_value in ('0', 'x', 'X', None):
                self._cycle_boundaries.append(int(time))
            
            prev_value = value
        
        pylibfst.lib.fstReaderFreeTimestamps(timestamps)
        self._cycle_count = len(self._cycle_boundaries)
    
    def get_cycle_count(self) -> int:
        """Get total number of clock cycles."""
        return self._cycle_count
    
    def time_to_cycle(self, time: int) -> int:
        """Convert simulation time to cycle number (0-indexed)."""
        if not self._cycle_boundaries:
            return 0
        idx = bisect_right(self._cycle_boundaries, time) - 1
        return max(0, idx)
    
    def cycle_to_time(self, cycle: int) -> int:
        """Get the start time of a cycle (rising edge time)."""
        if cycle < 0:
            return self.start_time
        if cycle >= self._cycle_count:
            return self.end_time
        return self._cycle_boundaries[cycle]
    
    def get_cycle_time_range(self, cycle: int) -> Tuple[int, int]:
        """Get (start_time, end_time) for a cycle."""
        start = self.cycle_to_time(cycle)
        if cycle + 1 < self._cycle_count:
            end = self._cycle_boundaries[cycle + 1] - 1
        else:
            end = self.end_time
        return start, end
    
    def get_signal_value(self, signal_name: str, cycle: int) -> Optional[str]:
        """
        Get the value of a signal at a specific cycle.
        
        Uses the value at the end of the cycle (just before next rising edge).
        
        Args:
            signal_name: Full hierarchical signal name
            cycle: Cycle number
            
        Returns:
            Signal value as string, or None if not found
        """
        cache_key = (signal_name, cycle)
        if cache_key in self._value_cache:
            return self._value_cache[cache_key]
        
        signal = self.signals.by_name.get(signal_name)
        if not signal:
            return None
        
        # Get time at end of cycle
        if cycle + 1 < self._cycle_count:
            sample_time = self._cycle_boundaries[cycle + 1] - 1
        else:
            sample_time = self.end_time
        
        buf = pylibfst.ffi.new("char[256]")
        value = pylibfst.helpers.string(
            pylibfst.lib.fstReaderGetValueFromHandleAtTime(
                self.fst, sample_time, signal.handle, buf
            )
        )
        
        self._value_cache[cache_key] = value
        return value
    
    def get_signal_value_at_cycle_start(self, signal_name: str, cycle: int) -> Optional[str]:
        """
        Get the value of a signal at the start of a cycle (just after rising edge).
        
        Args:
            signal_name: Full hierarchical signal name
            cycle: Cycle number
            
        Returns:
            Signal value as string, or None if not found
        """
        signal = self.signals.by_name.get(signal_name)
        if not signal:
            return None
        
        sample_time = self.cycle_to_time(cycle)
        
        buf = pylibfst.ffi.new("char[256]")
        value = pylibfst.helpers.string(
            pylibfst.lib.fstReaderGetValueFromHandleAtTime(
                self.fst, sample_time, signal.handle, buf
            )
        )
        
        return value
    
    def get_signal_transitions_in_cycle(self, signal_name: str, cycle: int) -> List[SignalTransition]:
        """
        Get all value transitions of a signal within a cycle.
        
        Args:
            signal_name: Signal name
            cycle: Cycle number
            
        Returns:
            List of SignalTransition objects
        """
        signal = self.signals.by_name.get(signal_name)
        if not signal:
            return []
        
        start_time, end_time = self.get_cycle_time_range(cycle)
        
        # Set process mask
        pylibfst.lib.fstReaderClrFacProcessMaskAll(self.fst)
        pylibfst.lib.fstReaderSetFacProcessMask(self.fst, signal.handle)
        
        timestamps = pylibfst.lib.fstReaderGetTimestamps(self.fst)
        if timestamps.nvals == 0:
            return []
        
        transitions = []
        buf = pylibfst.ffi.new("char[256]")
        prev_value = None
        
        for ts in range(timestamps.nvals):
            time = timestamps.val[ts]
            if time < start_time:
                prev_value = pylibfst.helpers.string(
                    pylibfst.lib.fstReaderGetValueFromHandleAtTime(
                        self.fst, time, signal.handle, buf
                    )
                )
                continue
            if time > end_time:
                break
            
            value = pylibfst.helpers.string(
                pylibfst.lib.fstReaderGetValueFromHandleAtTime(
                    self.fst, time, signal.handle, buf
                )
            )
            
            if prev_value is not None and value != prev_value:
                transitions.append(SignalTransition(
                    signal_name=signal_name,
                    time=int(time),
                    cycle=cycle,
                    old_value=prev_value,
                    new_value=value
                ))
            prev_value = value
        
        pylibfst.lib.fstReaderFreeTimestamps(timestamps)
        return transitions
    
    def find_signal(self, pattern: str, max_results: int = 100) -> List[str]:
        """
        Find signals matching a pattern.
        
        Args:
            pattern: Substring to match
            max_results: Maximum number of results
            
        Returns:
            List of matching signal names
        """
        matches = []
        for sig_name in self.signals.by_name.keys():
            if pattern.lower() in sig_name.lower():
                matches.append(sig_name)
                if len(matches) >= max_results:
                    break
        return matches
    
    def get_all_signals(self) -> List[str]:
        """Get list of all signal names."""
        return list(self.signals.by_name.keys())
    
    def get_cycle_snapshot(self, cycle: int, signals: Optional[List[str]] = None) -> CycleSnapshot:
        """
        Get snapshot of signal values at a cycle.
        
        Args:
            cycle: Cycle number
            signals: Optional list of signals to include (all if None)
            
        Returns:
            CycleSnapshot with values for specified signals
        """
        start_time, end_time = self.get_cycle_time_range(cycle)
        snapshot = CycleSnapshot(
            cycle=cycle,
            time_start=start_time,
            time_end=end_time
        )
        
        if signals is None:
            signals = list(self.signals.by_name.keys())
        
        buf = pylibfst.ffi.new("char[256]")
        for sig_name in signals:
            signal = self.signals.by_name.get(sig_name)
            if signal:
                value = pylibfst.helpers.string(
                    pylibfst.lib.fstReaderGetValueFromHandleAtTime(
                        self.fst, end_time, signal.handle, buf
                    )
                )
                snapshot.values[sig_name] = value
        
        return snapshot
    
    def get_value_changes(self, signal_name: str, 
                          start_cycle: int = 0, 
                          end_cycle: Optional[int] = None) -> List[Tuple[int, str, str]]:
        """
        Get all value changes for a signal between cycles.
        
        Args:
            signal_name: Signal name
            start_cycle: Starting cycle
            end_cycle: Ending cycle (inclusive), or None for end
            
        Returns:
            List of (cycle, old_value, new_value) tuples
        """
        if end_cycle is None:
            end_cycle = self._cycle_count - 1
        
        changes = []
        prev_value = self.get_signal_value(signal_name, max(0, start_cycle - 1))
        
        for cycle in range(start_cycle, min(end_cycle + 1, self._cycle_count)):
            value = self.get_signal_value(signal_name, cycle)
            if value != prev_value:
                changes.append((cycle, prev_value or 'x', value or 'x'))
            prev_value = value
        
        return changes
    
    def get_metadata(self) -> Dict[str, Any]:
        """Get waveform metadata."""
        return {
            "fst_path": self.fst_path,
            "clock_signal": self.clock_signal,
            "start_time": self.start_time,
            "end_time": self.end_time,
            "timescale": self.timescale,
            "cycle_count": self._cycle_count,
            "signal_count": len(self.signals.by_name)
        }
    
    def build_value_table(self, signals: List[str], 
                          start_cycle: int = 0,
                          end_cycle: Optional[int] = None) -> Dict[str, Dict[int, str]]:
        """
        Build a complete value table for signals over cycles.
        
        Args:
            signals: List of signal names
            start_cycle: Starting cycle
            end_cycle: Ending cycle, or None for end
            
        Returns:
            Dictionary: signal_name -> {cycle: value}
        """
        if end_cycle is None:
            end_cycle = self._cycle_count - 1
        
        table = {}
        for sig_name in signals:
            table[sig_name] = {}
            for cycle in range(start_cycle, min(end_cycle + 1, self._cycle_count)):
                value = self.get_signal_value(sig_name, cycle)
                if value is not None:
                    table[sig_name][cycle] = value
        
        return table
    
    def close(self):
        """Close the FST file."""
        if self.fst:
            pylibfst.lib.fstReaderClose(self.fst)
            self.fst = None
    
    def __del__(self):
        """Cleanup on deletion."""
        self.close()
    
    def __enter__(self):
        return self
    
    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()
        return False


def parse_binary_value(value: str) -> Optional[int]:
    """Parse binary string to int; returns None if contains x/z."""
    if not value or 'x' in value.lower() or 'z' in value.lower():
        return None
    try:
        return int(value, 2)
    except ValueError:
        return None


def invert_value(value: str) -> str:
    """Bitwise invert binary value (preserves x/z)."""
    if not value:
        return value

    return value.translate(str.maketrans('01', '10'))


def values_differ(val1: str, val2: str) -> bool:
    """Check if two binary values differ (ignoring x/z bits)."""
    if not val1 or not val2:
        return False
    
    # Pad to same length
    max_len = max(len(val1), len(val2))
    val1 = val1.zfill(max_len)
    val2 = val2.zfill(max_len)
    
    for c1, c2 in zip(val1, val2):
        if c1 in 'xXzZ' or c2 in 'xXzZ':
            continue  # Can't determine
        if c1 != c2:
            return True
    
    return False
