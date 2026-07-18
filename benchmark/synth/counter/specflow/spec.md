# Public Protocol Specification: 4-bit Counter with Overflow State

## 1. Status and authority

| Field | Value |
|---|---|
| Specification ID | `CHISELLMFV-SYNTH-CNT-S-001` |
| Version | `1.0.0` |
| Review date | 2026-07-18 |
| Status | Reviewed and approved as the normative public contract |
| Reviewer | `codex` |
| Visibility | Public |
| Difficulty | S — local sequential behavior |

This reviewed document is the normative authority for the counter task. Its
state-update rules and numbered clauses define the abstract golden model. An
implementation may be consulted only to confirm compatible public port names
and widths; its behavior cannot amend this contract.

The exact text of this version is the authority snapshot. Its SHA-256 digest is
recorded after suite review in `benchmark/synth/SPECIFICATIONS.sha256`; a digest
is not embedded here because that would make the document self-referential.

Source provenance is as follows:

- The behavior below is an independently reviewed public description of a
  four-bit synchronous counter and its overflow state.
- `benchmark/synth/counter/README.md` and
  `benchmark/synth/counter/src/main/scala/FirstCounter.scala` were consulted
  only to confirm the family name and public interface compatibility.
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

- counter width: 4 bits;
- increment: one count per enabled, non-reset rising edge;
- arithmetic: modulo 16;
- reset: synchronous and active high;
- reset has priority over enable for the counter state;
- overflow is a one-bit stored state, not a combinational carry output; and
- all state changes occur on the rising edge of `clock`.

## 4. Top-level interface

| Port | Direction | Width | Public meaning |
|---|---:|---:|---|
| `clock` | input | 1 | Rising-edge state-update clock |
| `reset` | input | 1 | Active-high synchronous reset |
| `enable` | input | 1 | Enables a modulo-16 increment when reset is low |
| `counter_out` | output | 4 | Current counter state |
| `overflow_out` | output | 1 | Current stored overflow state |

There is no request/response handshake. Outputs continuously expose the two
stored states between clock edges.

## 5. Terms, events, and abstract golden model

A **sampling edge** is a rising edge of `clock`. The **pre-edge counter** and
**pre-edge overflow** are the output values immediately before that edge. The
**next counter** and **next overflow** are the values visible after the edge's
normal clock-to-output propagation.

The next counter is determined in this priority order:

| Pre-edge control | Next counter |
|---|---|
| `reset` high, regardless of `enable` | zero |
| `reset` low and `enable` high | pre-edge counter plus one, modulo 16 |
| `reset` low and `enable` low | pre-edge counter unchanged |

The overflow update is a separate rule that reads the pre-edge counter:

| Pre-edge condition | Next overflow |
|---|---|
| pre-edge counter equals 15 | high |
| pre-edge counter is not 15 and `reset` is high | low |
| pre-edge counter is not 15 and `reset` is low | pre-edge overflow unchanged |

The first row of the overflow table has priority over its other rows. Thus, if
the pre-edge counter is 15 while reset is high, the next counter is zero and
the next overflow is high. On a following reset edge, the pre-edge counter is
zero and the next overflow becomes low.

Overflow is therefore stored and may remain high. It is not specified as a
single-cycle pulse and it is not derived directly from the post-edge counter.

## 6. Clock, reset, and initialization

Both states update only on rising clock edges. `reset` is synchronous: changing
it without a rising edge does not immediately change either output. The reset
value of `counter_out` is zero. The reset value of `overflow_out` is zero when
the pre-edge counter is not 15, subject to the explicit priority rule in
Section 5.

Power-on values before a reset sequence are unspecified. From arbitrary binary
power-on state, two consecutive rising edges with reset high guarantee the
fully initialized observation `counter_out` equal to zero and `overflow_out`
low. One reset edge always initializes the counter, but the overflow value
after that first edge depends on the pre-edge counter.

## 7. Legal environment and allowed assumptions

The following assumptions are allowed:

1. `clock` has distinct rising edges suitable for synchronous sampling, and
   `reset` and `enable` have definite binary values stable around each sampled
   edge. This is the ordinary synchronous digital interface contract.
2. A checker that requires a definite initial value may assume two consecutive
   reset-high rising edges before beginning post-initialization claims. This is
   justified by the two state-update tables and must not constrain later
   behavior.
3. A one-step transition property may be guarded until a valid pre-edge sample
   exists. This prevents an uninitialized history register in the checker from
   creating a false requirement.

No assumption may prevent enable from being high, exclude counter value 15,
force overflow low, or keep reset asserted for the whole run. Properties about
pre-reset outputs must respect their unspecified initialization.

## 8. Normative clauses

- **CTR-N-001 — State exposure.** `counter_out` and `overflow_out`
  continuously expose the current counter and overflow states.
- **CTR-N-010 — Reset counter update.** On a sampling edge with reset high,
  the next counter is zero, regardless of enable.
- **CTR-N-011 — Enabled counter update.** On a sampling edge with reset low
  and enable high, the next counter is the pre-edge counter plus one modulo 16.
- **CTR-N-012 — Counter hold.** On a sampling edge with both reset and enable
  low, the next counter equals the pre-edge counter.
- **CTR-N-020 — Overflow set condition.** If the pre-edge counter is 15, the
  next overflow is high regardless of reset and enable.
- **CTR-N-021 — Overflow reset condition.** If the pre-edge counter is not 15
  and reset is high, the next overflow is low.
- **CTR-N-022 — Overflow hold.** If the pre-edge counter is not 15 and reset
  is low, the next overflow equals the pre-edge overflow.
- **CTR-N-023 — Pre-edge sampling.** The overflow decision uses the counter
  value before the sampling edge, not the newly computed counter value.
- **CTR-N-030 — Synchronous timing.** Neither output changes in response to
  reset or enable except as a result of a rising clock edge and normal
  clock-to-output propagation.
- **CTR-N-040 — Initialization boundary.** Before the reset procedure in
  Section 6, either state may have any binary value of its declared width.

## 9. Expected verification properties

The following entries state public verification intent. They are not hidden
golden assertions or hidden signal bindings, and they do not disclose an
evaluator-specific obligation map.

| Property ID | Class | Clause coverage | Expected public check |
|---|---|---|---|
| `CTR-P-TIM-001` | timing/next state | CTR-N-010 through CTR-N-012 | Every sampled counter transition follows the reset, increment, and hold priority table |
| `CTR-P-TIM-002` | timing/next state | CTR-N-020 through CTR-N-023 | Every sampled overflow transition follows the pre-edge counter rule and its priority |
| `CTR-P-TIM-003` | timing | CTR-N-030 | Reset and enable have no asynchronous effect on outputs |
| `CTR-P-SAF-002` | safety/initialization | CTR-N-010, CTR-N-021, CTR-N-040 | After two consecutive reset-high edges, the counter is zero and overflow is low |
| `CTR-P-PRG-001` | bounded progress | CTR-N-011, CTR-N-020, CTR-N-023 | Starting from initialized zero state, sixteen consecutive enabled, non-reset edges return the counter to zero and make overflow high |
| `CTR-P-ACT-001` | activation cover | CTR-N-011 | Reach an edge with reset low and enable high |
| `CTR-P-ACT-002` | activation cover | CTR-N-012 | Reach an edge with reset and enable both low |
| `CTR-P-ACT-003` | activation cover | CTR-N-020 through CTR-N-023 | Reach a sampled pre-edge counter value of 15 and observe the separate overflow update |
| `CTR-P-ACT-004` | activation cover | CTR-N-010, CTR-N-020 | Reach a reset-high edge whose pre-edge counter is 15 so both priority tables are exercised |
| `CTR-P-OBS-001` | observer cover | CTR-N-001 | Observe counter values zero and 15 and observe overflow both low and high |
| `CTR-P-OBS-002` | observer cover | CTR-N-023, CTR-N-040 | Establish a valid prior-edge sample before evaluating a next-state relation |

Interface-review note: the declared four-bit `counter_out` and one-bit
`overflow` widths are checked at elaboration and are not counted as primary
formal properties.

## 10. Optional and undefined behavior

- Power-on values before the initialization procedure are undefined but remain
  within the declared binary widths.
- Electrical X/Z values, metastability, clock duty cycle, exact clock-to-output
  delay, and setup/hold timing are outside this logical contract.
- A reset level that does not overlap a rising clock edge has no required
  effect.
- Overflow is not required to pulse, clear on increment, or equal the carry
  bit of the current arithmetic operation; only the table in Section 5 defines
  it.
- No saturation, decrement, load, or asynchronous clear behavior is implied.

## 11. Review record

- **Reviewer:** `codex`
- **Review date:** 2026-07-18
- **Reviewed scope:** interface names and widths, counter update priority,
  pre-edge overflow semantics, synchronous reset, initialization, timing,
  assumptions, and non-vacuity covers.
- **Compatibility evidence:** the two provenance paths in Section 1 were used
  only for public interface audit.
- **Leakage review:** no private evaluator material was used to formulate a
  normative clause or expected property.
- **Decision:** approved and frozen as public version `1.0.0`; the canonical
  content hash is recorded in `benchmark/synth/SPECIFICATIONS.sha256`.
