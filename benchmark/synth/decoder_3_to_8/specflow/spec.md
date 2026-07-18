# Public Protocol Specification: Active-low 3-to-8 Decoder

## 1. Status and authority

| Field | Value |
|---|---|
| Specification ID | `CHISELLMFV-SYNTH-DEC-S-001` |
| Version | `1.0.0` |
| Review date | 2026-07-18 |
| Status | Reviewed and approved as the normative public contract |
| Reviewer | `codex` |
| Visibility | Public |
| Difficulty | S — local functional behavior |

This reviewed document is the normative authority for the decoder task. The
truth table and numbered clauses below define the abstract golden model. An
implementation may be consulted only to confirm compatible public port names
and widths; its behavior cannot amend this contract.

The exact text of this version is the authority snapshot. Its SHA-256 digest is
recorded after suite review in `benchmark/synth/SPECIFICATIONS.sha256`; a digest
is not embedded here because that would make the document self-referential.

Source provenance is as follows:

- The behavior below is an independently reviewed public description of a
  conventional enabled 3-to-8 decoder with active-low outputs.
- `benchmark/synth/decoder_3_to_8/README.md` and
  `benchmark/synth/decoder_3_to_8/src/main/scala/Decoder3to8.scala` were
  consulted only to confirm the family name and public interface
  compatibility.
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

- three binary address inputs, ordered `A` as the most-significant bit, then
  `B`, then `C` as the least-significant bit;
- one active-high enable input;
- eight individually named active-low outputs;
- no internal state; and
- no configurable latency.

The selected output number is the unsigned value represented by `ABC`, from
zero through seven.

## 4. Top-level interface

| Port | Direction | Width | Public meaning |
|---|---:|---:|---|
| `clock` | input | 1 | Compatibility clock; it does not affect the abstract model |
| `reset` | input | 1 | Compatibility reset; it does not affect the abstract model |
| `A` | input | 1 | Most-significant selector bit |
| `B` | input | 1 | Middle selector bit |
| `C` | input | 1 | Least-significant selector bit |
| `en` | input | 1 | Active-high decode enable |
| `Y7` | output | 1 | Active-low output for selector seven |
| `Y6` | output | 1 | Active-low output for selector six |
| `Y5` | output | 1 | Active-low output for selector five |
| `Y4` | output | 1 | Active-low output for selector four |
| `Y3` | output | 1 | Active-low output for selector three |
| `Y2` | output | 1 | Active-low output for selector two |
| `Y1` | output | 1 | Active-low output for selector one |
| `Y0` | output | 1 | Active-low output for selector zero |

There is no request/response or ready/valid handshake.

## 5. Terms, observations, and abstract golden model

An **observation** is a point at which `A`, `B`, `C`, and `en` have been stable
long enough for combinational propagation. An output is **asserted** when it is
low, because all eight outputs are active-low. The **selected index** is the
unsigned binary value of `ABC` with `A` as the high-order bit.

When `en` is low, all outputs are high. When `en` is high, exactly the selected
output is low and all other outputs are high.

| `A B C` | Selected index | Required low output | Required high outputs |
|---|---:|---|---|
| `0 0 0` | 0 | `Y0` | `Y1` through `Y7` |
| `0 0 1` | 1 | `Y1` | every output except `Y1` |
| `0 1 0` | 2 | `Y2` | every output except `Y2` |
| `0 1 1` | 3 | `Y3` | every output except `Y3` |
| `1 0 0` | 4 | `Y4` | every output except `Y4` |
| `1 0 1` | 5 | `Y5` | every output except `Y5` |
| `1 1 0` | 6 | `Y6` | every output except `Y6` |
| `1 1 1` | 7 | `Y7` | `Y0` through `Y6` |

## 6. Clock, reset, and initialization

The abstract model is purely combinational and contains no stored state.
`clock` and `reset` exist for interface compatibility only. No clock edge,
reset sequence, or initialization event is required before an observation.
Changing either compatibility input alone must not change any `Y` output.

The contract specifies logical combinational behavior, not a physical delay.
Outputs need only be sampled after normal combinational settling.

## 7. Legal environment and allowed assumptions

The following assumptions are allowed:

1. `A`, `B`, `C`, and `en` have definite binary values and are stable at the
   observation point. This is required because the public model is a two-state
   digital truth table rather than an electrical unknown-resolution model.
2. A checker may introduce a sampling clock solely to compare combinational
   observations, provided it does not add latency to the required relation.

No assumption may permanently constrain `en`, exclude a selector value, or
force an output. `clock` and `reset` require no functional constraint.

## 8. Normative clauses

- **DEC-N-001 — Combinational dependence.** At every legal observation, all
  eight outputs depend only on the current `en`, `A`, `B`, and `C` values.
- **DEC-N-010 — Disabled value.** When `en` is low, `Y7` through `Y0` are all
  high.
- **DEC-N-020 — Enabled selection.** When `en` is high, the selected index is
  the unsigned value of `ABC`, with `A` most significant and `C` least
  significant.
- **DEC-N-021 — Active-low one-of-eight result.** When enabled, the output
  whose number equals the selected index is low and every other output is
  high.
- **DEC-N-022 — Complete truth table.** Each of the eight enabled selector
  rows has exactly the output assignment listed in Section 5.
- **DEC-N-030 — Clock/reset independence.** Decoder outputs are independent
  of `clock` and `reset`.

## 9. Expected verification properties

The following entries state public verification intent. They are not hidden
golden assertions or hidden signal bindings, and they do not disclose an
evaluator-specific obligation map.

| Property ID | Class | Clause coverage | Expected public check |
|---|---|---|---|
| `DEC-P-SAF-001` | safety/data relation | DEC-N-010 | A disabled decoder drives all eight outputs high |
| `DEC-P-SAF-002` | safety/data relation | DEC-N-020 through DEC-N-022 | Every enabled selector value drives its correspondingly numbered output low and all others high |
| `DEC-P-SAF-003` | safety/cardinality | DEC-N-021 | Exactly one output is low whenever enabled, and no output is low whenever disabled |
| `DEC-P-TIM-001` | timing | DEC-N-001, DEC-N-030 | Current stable inputs determine outputs in the same combinational observation, with no prior-cycle dependence |
| `DEC-P-ACT-001` | activation cover | DEC-N-010 | Reach at least one disabled observation |
| `DEC-P-ACT-002` | activation cover | DEC-N-020 through DEC-N-022 | Reach all eight enabled selector values |
| `DEC-P-OBS-001` | observer cover | DEC-N-021 | Observe every named output low in at least one enabled observation and high in at least one observation |
| `DEC-P-OBS-002` | observer cover | DEC-N-010 | Observe the all-high output pattern |

Scope classification: this combinational decoder has no state or handshake,
so the primary formal-property set requires no sequential progress or liveness
target.

## 10. Optional and undefined behavior

- Electrical X/Z behavior, metastability, hazards while inputs change,
  propagation delay, and timing closure are outside this logical contract.
- There is no undefined binary selector row: all values of `en`, `A`, `B`, and
  `C` are specified.
- Output polarity is not optional. Low means asserted and high means inactive.
- No output latching, output-enable state, side effect, or stored history is
  implied.

## 11. Review record

- **Reviewer:** `codex`
- **Review date:** 2026-07-18
- **Reviewed scope:** interface names and widths, selector bit ordering,
  enable polarity, output polarity, complete truth table, timing, and legal
  assumptions.
- **Compatibility evidence:** the two provenance paths in Section 1 were used
  only for public interface audit.
- **Leakage review:** no private evaluator material was used to formulate a
  normative clause or expected property.
- **Decision:** approved and frozen as public version `1.0.0`; the canonical
  content hash is recorded in `benchmark/synth/SPECIFICATIONS.sha256`.
