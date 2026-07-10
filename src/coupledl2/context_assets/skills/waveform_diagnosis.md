# Waveform Diagnosis

Classify each counterexample using exactly one workflow category and tie each
conclusion to concrete trace, property, binding, or source evidence.

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

- `design_bug`: the original design logic violates a valid property.
- `property_schema_error`: the protocol/property meaning, trigger, expectation,
  bound, or required preconditions are wrong.
- `template_error`: the repository-owned assertion template has incorrect timing
  or assertion semantics.
- `binding_error`: the selected observation point samples the wrong design state
  or does not implement the declared schema slots.
- `environment_error`: the harness or formal setup creates an unrealistic or
  invalid trace.
- `assumption_error`: a missing, incorrect, or contradictory assumption is the
  direct cause.
- `inconclusive`: evidence is insufficient or the solver result cannot support a
  stronger claim.

Only `design_bug` permits Stage 5 design repair. Every other classification must
identify the asset or environment layer that should be revised.

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
