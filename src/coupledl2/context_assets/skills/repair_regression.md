# Repair Regression

Preserve original failing assertion labels or record a migration map. Do not
delete failed assertions to create a passing result.

## Repair Scope

- Make one focused repair round.
- Choose whether the fix belongs to DUT logic, assertion logic, or setup logic
  from the latest diagnosis.
- Keep the change minimal and readable.
- Do not weaken, disable, delete, or rename the target assertion to create a
  passing result.

## Assertion Repair Rules

- For `assertion_error`, inspect the source for structurally identical
  assertions and repair them consistently.
- Preserve the original failing assertion label unless the diagnosis proves the
  label itself is the setup error.
- For timing mistakes, snapshot antecedent-cycle combinational values explicitly,
  for example with `RegNext`.

## DUT Repair Rules

- Explain why the source change addresses the causal path, not just the observed
  waveform value.
- Avoid broad behavior changes outside the diagnosed path.
- Preserve existing interfaces and test harness structure unless setup evidence
  requires otherwise.

## Completion Evidence

When calling `complete_stage`, include:

- `error_type`
- `target_assertion_label`
- `homologous_assertions` for assertion repairs
- Modified workspace-relative files
- The diagnosis or trace evidence that justifies the change
