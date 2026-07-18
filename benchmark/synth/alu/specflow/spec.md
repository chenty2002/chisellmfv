# Public Protocol Specification: 8-bit ALU

## 1. Status and authority

| Field | Value |
|---|---|
| Specification ID | `CHISELLMFV-SYNTH-ALU-S-001` |
| Version | `1.0.0` |
| Review date | 2026-07-18 |
| Status | Reviewed and approved as the normative public contract |
| Reviewer | `codex` |
| Visibility | Public |
| Difficulty | S — local functional behavior |

This reviewed document is the normative authority for the ALU task. Its
operation table, timing rules, and numbered clauses define the abstract golden
model. An implementation is relevant only for checking that the public port
names and widths are compatible; implementation behavior cannot amend this
contract.

The exact text of this version is the authority snapshot. Its SHA-256 digest is
recorded after suite review in `benchmark/synth/SPECIFICATIONS.sha256`; a digest
is not embedded here because that would make the document self-referential.

Source provenance is as follows:

- The behavior below is an independently reviewed public description of a
  fixed 8-bit arithmetic and logic unit.
- `benchmark/synth/alu/README.md` and
  `benchmark/synth/alu/src/main/scala/Alu.scala` were consulted only to confirm
  the family name and public interface compatibility.
- Those implementation-facing files are not normative sources and cannot
  override this specification.

Any semantic change requires a new version, a fresh review record, and a new
suite-level digest. Evaluation outcomes must not be used to rewrite this
version retroactively.

## 2. Public and evaluator boundary

The verification method may receive this document, the declared run
configuration, and the model-visible target source selected for that run. It
may rely on no other functional oracle.

Reference implementations, implementation differences, defect metadata,
private assertions, clause-to-obligation mappings, signal bindings, expected
verdicts, selected traces, and diagnosis answer keys are evaluator-only. None
of them forms part of this public contract.

## 3. Configuration

This specification has one fixed configuration:

- data width: 8 bits;
- opcode width: 4 bits;
- arithmetic result width: 8 bits;
- operands are unsigned for multiplication, division, and shifts;
- addition and subtraction retain only their least-significant 8 bits;
- signed overflow uses two's-complement interpretation of the operand and
  result sign bits;
- left and right shifts are logical; and
- there is no internal state or configurable latency.

## 4. Top-level interface

| Port | Direction | Width | Public meaning |
|---|---:|---:|---|
| `clock` | input | 1 | Compatibility clock; it does not affect the abstract model |
| `reset` | input | 1 | Compatibility reset; it does not affect the abstract model |
| `opcode` | input | 4 | Selects the operation |
| `a` | input | 8 | First operand |
| `b` | input | 8 | Second operand or shift amount |
| `y` | output | 8 | Selected operation result |
| `zero` | output | 1 | High exactly when `y` is zero |
| `overflow` | output | 1 | Signed add/subtract overflow indication |

All functional ports are single-lane signals. No ready/valid handshake is
present.

## 5. Terms, observations, and abstract golden model

An **observation** is a point at which the functional inputs have been stable
long enough for combinational propagation. The **low eight bits** of an
integer are its value modulo 256. Bit 7 is the sign bit when a value is viewed
as an 8-bit two's-complement number.

The abstract golden result is selected by the complete opcode table below.

| `opcode` | Public operation | Value of `y` |
|---|---|---|
| `0000` | addition | low eight bits of `a` plus `b` |
| `0001` | subtraction | low eight bits of `a` minus `b` |
| `0010` | multiplication | low eight bits of unsigned `a` times unsigned `b` |
| `0011` | division | unsigned integer quotient of `a` divided by nonzero `b`, rounded down |
| `0100` | bitwise AND | bitwise AND of `a` and `b` |
| `0101` | bitwise OR | bitwise OR of `a` and `b` |
| `0110` | bitwise XOR | bitwise XOR of `a` and `b` |
| `0111` | bitwise NOT | bitwise complement of `a`; `b` is ignored |
| `1000` | logical left shift | low eight bits after shifting `a` left by the unsigned value of `b` |
| `1001` | logical right shift | zero-filled result after shifting `a` right by the unsigned value of `b` |
| `1010` through `1111` | reserved selection | zero |

For either logical shift, a shift amount of eight or more produces zero. The
`zero` flag is derived from the final `y`, including reserved selections.

For addition, `overflow` is high exactly when `a` and `b` have the same sign
bit and `y` has the opposite sign bit. For subtraction, it is high exactly
when `a` and `b` have different sign bits and `y` has a different sign bit
from `a`. It is low for every other opcode.

## 6. Clock, reset, and initialization

The abstract model is purely combinational and contains no stored state.
`clock` and `reset` exist for interface compatibility only. No clock edge,
reset sequence, or initialization event is required before an observation.
Changing either compatibility input alone must not change a functional output.

The contract specifies logical combinational behavior, not a physical delay.
Outputs need only be sampled after normal combinational settling.

## 7. Legal environment and allowed assumptions

The following assumptions are allowed:

1. `opcode`, `a`, and `b` have definite binary values and are stable at the
   observation point. This is required because the public model is a two-state
   digital contract rather than an electrical unknown-resolution model.
2. When `opcode` selects division and the quotient relation is checked, `b` is
   nonzero. Division by zero is deliberately outside the defined result
   relation.
3. A checker may introduce a sampling clock solely to compare combinational
   observations, provided it does not add latency to the required relation.

No assumption may exclude any defined opcode, suppress signed-overflow cases,
or constrain operands merely to make a result property easier to prove.
`clock` and `reset` require no functional constraint.

## 8. Normative clauses

- **ALU-N-001 — Combinational dependence.** At every legal observation,
  `y`, `zero`, and `overflow` depend only on the current `opcode`, `a`, and
  `b` values.
- **ALU-N-010 — Addition.** Opcode `0000` produces the modulo-256 sum.
- **ALU-N-011 — Subtraction.** Opcode `0001` produces the modulo-256
  difference with `a` as the minuend.
- **ALU-N-012 — Multiplication.** Opcode `0010` produces the low eight bits of
  the unsigned product.
- **ALU-N-013 — Division.** Opcode `0011` with nonzero `b` produces the
  floor of the unsigned quotient.
- **ALU-N-014 — Boolean operations.** Opcodes `0100`, `0101`, and `0110`
  produce bitwise AND, OR, and XOR, respectively.
- **ALU-N-015 — Complement.** Opcode `0111` complements all eight bits of
  `a` and does not depend on `b`.
- **ALU-N-016 — Logical shifts.** Opcodes `1000` and `1001` perform the
  logical left and logical right shifts defined in the operation table.
- **ALU-N-017 — Reserved selections.** Opcodes `1010` through `1111`
  produce zero.
- **ALU-N-020 — Zero flag.** `zero` is high if and only if the final `y` is
  eight-bit zero.
- **ALU-N-030 — Addition overflow.** On opcode `0000`, `overflow` follows
  the signed-addition sign rule defined above.
- **ALU-N-031 — Subtraction overflow.** On opcode `0001`, `overflow` follows
  the signed-subtraction sign rule defined above.
- **ALU-N-032 — Other overflow values.** On every opcode other than `0000`
  and `0001`, `overflow` is low.
- **ALU-N-040 — Clock/reset independence.** Functional outputs are
  independent of `clock` and `reset`.

## 9. Expected verification properties

The following entries state public verification intent. They are not hidden
golden assertions or hidden signal bindings, and they do not disclose an
evaluator-specific obligation map.

| Property ID | Class | Clause coverage | Expected public check |
|---|---|---|---|
| `ALU-P-SAF-001` | safety/data relation | ALU-N-010 through ALU-N-017 | For every defined operation domain, `y` matches the selected table row |
| `ALU-P-SAF-002` | safety/flag relation | ALU-N-020 | `zero` agrees with whether the final result is zero |
| `ALU-P-SAF-003` | safety/flag relation | ALU-N-030 through ALU-N-032 | `overflow` follows the two signed rules and is otherwise low |
| `ALU-P-TIM-001` | timing | ALU-N-001, ALU-N-040 | Current stable inputs determine outputs in the same combinational observation, with no prior-cycle dependence |
| `ALU-P-ACT-001` | activation cover | ALU-N-010 through ALU-N-017 | Exercise every listed opcode and at least one reserved opcode; exercise division with a nonzero divisor |
| `ALU-P-ACT-002` | activation cover | ALU-N-030, ALU-N-031 | Reach both overflowing and non-overflowing addition and subtraction observations |
| `ALU-P-OBS-001` | observer cover | ALU-N-020 | Observe both values of `zero` and at least two distinct values of `y` |
| `ALU-P-OBS-002` | observer cover | ALU-N-030 through ALU-N-032 | Observe both values of `overflow` without constraining away either arithmetic operation |

Scope classification: this combinational unit has no state or handshake, so
the primary formal-property set requires no sequential progress or liveness
target.

## 10. Optional and undefined behavior

- Division by zero has no specified `y` value. Consequently, `zero` is also
  unspecified for that observation because it is derived from `y`.
  `overflow` remains low because division is not an add/subtract operation.
- Electrical X/Z behavior, metastability, glitches during input transitions,
  propagation delay, and timing closure are outside this logical contract.
- Reserved selections are not undefined; they are explicitly required to
  produce zero with `zero` high and `overflow` low.
- No side effect, stored history, exception, saturation, signed
  multiplication, or signed division is implied.

## 11. Review record

- **Reviewer:** `codex`
- **Review date:** 2026-07-18
- **Reviewed scope:** interface names and widths, complete operation table,
  flag semantics, combinational timing, legal assumptions, and undefined
  division behavior.
- **Compatibility evidence:** the two provenance paths in Section 1 were used
  only for public interface audit.
- **Leakage review:** no private evaluator material was used to formulate a
  normative clause or expected property.
- **Decision:** approved and frozen as public version `1.0.0`; the canonical
  content hash is recorded in `benchmark/synth/SPECIFICATIONS.sha256`.
