# Waveform Diagnosis

Classify each counterexample as DUT bug, assertion error, harness/setup error,
or inconclusive. Tie each conclusion to concrete trace or source evidence.

## Evidence Order

1. Locate the failed assertion or property label from the verification result or
   waveform filename.
2. Read the assertion and surrounding source before interpreting the trace.
3. Use waveform search tools to resolve exact signal names. Do not guess names
   from Chisel source alone.
4. Check prior causal-analysis output when present, but verify each candidate
   against waveform values and source logic.
5. Write `counterexample_analysis.md` with `write_report`, then finish with
   `complete_stage`.

## Classification

- `dut_bug`: the original design logic violates a valid property.
- `assertion_error`: the property is wrong, over-constrained, has bad timing, or
  samples the wrong signal.
- `setup_error`: the harness, reset, assumptions, or verification environment
  creates an unrealistic or invalid trace.
- `inconclusive`: evidence is insufficient or the solver result cannot support a
  stronger claim.

## Signal Lookup Rules

- Use the exact signal name returned by waveform tools, including bit ranges.
- If one lookup fails, switch to signal search instead of repeating the same
  query.
- Treat generated temporary names as supporting evidence only when source
  correlation is clear.
- All waveform time values are in nanoseconds unless the tool result says
  otherwise.

## Report Structure

The report should contain:

- Verification environment and relevant top structure.
- Violated assertion label, source location, and property meaning.
- Waveform range and critical signal values.
- Root-cause classification.
- Concrete source/trace evidence for the classification.
- Remaining uncertainty when the result is inconclusive.
