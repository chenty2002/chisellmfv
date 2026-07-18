# Public Protocol Specification: 16-state Synchronous FSM

## 1. Status and authority

| Field | Value |
|---|---|
| Specification ID | `CHISELLMFV-SYNTH-FSM16-S-001` |
| Version | `1.0.0` |
| Review date | 2026-07-18 |
| Status | Reviewed and approved as the normative public contract |
| Reviewer | `codex` |
| Visibility | Public |
| Difficulty | S — local sequential behavior |

This reviewed document is the normative authority for the FSM task. Its
transition table and numbered clauses define the abstract golden model. An
implementation may be consulted only to confirm compatible public port names
and widths; its behavior cannot amend this contract.

The exact text of this version is the authority snapshot. Its SHA-256 digest is
recorded after suite review in `benchmark/synth/SPECIFICATIONS.sha256`; a digest
is not embedded here because that would make the document self-referential.

Source provenance is as follows:

- The behavior below is an independently reviewed public state-machine
  contract with a complete 16-state transition table.
- `benchmark/synth/fsm_16/README.md` and
  `benchmark/synth/fsm_16/src/main/scala/Fsm16.scala` were consulted only to
  confirm the family name and public interface compatibility.
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

- sixteen states named `S0` through `S15`;
- state encoding: `Sn` is the four-bit unsigned binary encoding of integer
  `n`;
- two one-bit transition inputs, ordered as `input1` then `input2`;
- one transition per non-reset rising clock edge;
- reset: synchronous, active high, and directed to `S0`; and
- no configurable latency or additional externally visible state.

## 4. Top-level interface

| Port | Direction | Width | Public meaning |
|---|---:|---:|---|
| `clock` | input | 1 | Rising-edge state-update clock |
| `reset` | input | 1 | Active-high synchronous reset to `S0` |
| `input1` | input | 1 | High-order transition-selection bit |
| `input2` | input | 1 | Low-order transition-selection bit |
| `state` | output | 4 | Current state encoding from zero through fifteen |

There is no request/response handshake. `state` continuously exposes the
stored state between clock edges.

## 5. Terms, events, and abstract golden model

A **sampling edge** is a rising edge of `clock`. The **current state** is the
value immediately before that edge. The **next state** is the value visible
after the edge's normal clock-to-output propagation. The **input pair** is the
two-bit value formed by `input1` as the high-order bit and `input2` as the
low-order bit.

When reset is low, the next state is given by this complete table:

| Current state | input pair `00` | input pair `01` | input pair `10` | input pair `11` |
|---|---|---|---|---|
| `S0` | `S2` | `S2` | `S2` | `S1` |
| `S1` | `S4` | `S3` | `S4` | `S4` |
| `S2` | `S6` | `S6` | `S5` | `S6` |
| `S3` | `S7` | `S8` | `S8` | `S8` |
| `S4` | `S10` | `S9` | `S9` | `S9` |
| `S5` | `S11` | `S11` | `S12` | `S11` |
| `S6` | `S13` | `S14` | `S13` | `S13` |
| `S7` | `S15` | `S15` | `S15` | `S0` |
| `S8` | `S2` | `S2` | `S2` | `S1` |
| `S9` | `S4` | `S3` | `S4` | `S4` |
| `S10` | `S6` | `S6` | `S5` | `S6` |
| `S11` | `S7` | `S8` | `S8` | `S8` |
| `S12` | `S10` | `S9` | `S9` | `S9` |
| `S13` | `S11` | `S11` | `S12` | `S11` |
| `S14` | `S13` | `S14` | `S13` | `S13` |
| `S15` | `S15` | `S15` | `S15` | `S0` |

When reset is high at a sampling edge, the next state is `S0` regardless of
the current state or input pair.

## 6. Clock, reset, and initialization

State updates only on rising clock edges. `reset` is synchronous: changing it
without a rising edge does not immediately change `state`. A reset-high rising
edge sets the next state to `S0` and has priority over the transition table.

Power-on state before a reset edge is unspecified. One rising edge with reset
high establishes the initialized state `S0`. Transition checks that rely on a
prior sample may be guarded until a valid sampled history exists.

## 7. Legal environment and allowed assumptions

The following assumptions are allowed:

1. `clock` has distinct rising edges suitable for synchronous sampling, and
   `reset`, `input1`, and `input2` have definite binary values stable around
   each sampled edge. This is the ordinary synchronous digital interface
   contract.
2. A checker that requires a known initial state may assume one reset-high
   rising edge before beginning post-initialization claims. The assumption must
   not constrain later input pairs or keep reset asserted indefinitely.
3. A one-step transition property may be guarded until a valid pre-edge sample
   exists, preventing checker history from creating a false first-cycle
   requirement.

No assumption may exclude a state or input pair, force `state` to a selected
value, or remove legal transition rows from consideration.

## 8. Normative clauses

- **FSM16-N-001 — State encoding.** `state` exposes the current four-bit
  encoding of `S0` through `S15`, with `Sn` encoded as unsigned integer `n`.
- **FSM16-N-010 — Synchronous reset.** A sampling edge with reset high sets
  the next state to `S0`, regardless of the current state and inputs.
- **FSM16-N-011 — Reset priority.** The reset transition takes priority over
  every non-reset row of the transition table.
- **FSM16-N-020 — Input ordering.** The transition-table column is selected
  by `input1` as the high-order bit and `input2` as the low-order bit.
- **FSM16-N-021 — Complete next-state relation.** On every sampling edge with
  reset low, the next state is exactly the entry at the current-state row and
  current input-pair column in Section 5.
- **FSM16-N-022 — Closed state space.** Every table target is one of `S0`
  through `S15`; no additional abstract state is permitted.
- **FSM16-N-030 — State timing.** `state` changes only as the result of a
  rising clock edge and normal clock-to-output propagation.
- **FSM16-N-040 — Initialization boundary.** Before a reset edge, `state` may
  have any binary value of its declared width; one reset-high edge establishes
  `S0`.

## 9. Expected verification properties

The following entries state public verification intent. They are not hidden
golden assertions or hidden signal bindings, and they do not disclose an
evaluator-specific obligation map.

| Property ID | Class | Clause coverage | Expected public check |
|---|---|---|---|
| `FSM16-P-SAF-002` | safety/reset | FSM16-N-010, FSM16-N-011 | Every reset-high sampled edge leads to `S0` regardless of inputs |
| `FSM16-P-TIM-001` | timing/next state | FSM16-N-020, FSM16-N-021 | Every non-reset sampled transition matches the exact table row and input-pair column |
| `FSM16-P-TIM-002` | timing | FSM16-N-030 | Inputs and reset have no asynchronous effect on `state` |
| `FSM16-P-SAF-003` | safety/initialization | FSM16-N-040 | One reset-high edge establishes `S0` before post-initialization checks |
| `FSM16-P-PRG-001` | bounded existential progress | FSM16-N-021, FSM16-N-022 | From initialized `S0`, every declared state is reachable by some legal sequence of at most four non-reset input pairs |
| `FSM16-P-ACT-001` | activation cover | FSM16-N-021 | Exercise every current-state row with each of the four input pairs |
| `FSM16-P-ACT-002` | activation cover | FSM16-N-010, FSM16-N-011 | Exercise reset from at least one nonzero state and with both values represented across the input ports |
| `FSM16-P-OBS-001` | observer cover | FSM16-N-001, FSM16-N-022 | Observe all sixteen state encodings after initialization |
| `FSM16-P-OBS-002` | observer cover | FSM16-N-030, FSM16-N-040 | Establish a valid prior-edge state sample before evaluating a next-state relation |

Interface-review note: membership of a four-bit port in the sixteen possible
four-bit encodings is guaranteed by elaboration alone and is not counted as a
primary formal property.  The normative closed-state-space requirement remains
relevant to the explicit transition-table checks.

`FSM16-P-PRG-001` is an existential reachability expectation, not a claim that
arbitrary input streams must reach every state. No unconditional fairness or
eventual-state requirement is imposed on the environment.

## 10. Optional and undefined behavior

- Power-on state before the initialization edge is undefined but remains a
  four-bit binary value, which corresponds to one of the sixteen state
  encodings.
- Electrical X/Z values, metastability, clock duty cycle, exact clock-to-output
  delay, and setup/hold timing are outside this logical contract.
- A reset level that does not overlap a rising clock edge has no required
  effect.
- There are no illegal binary state encodings and no unspecified binary
  transition-table entries.
- No output action other than the exposed state, no terminal state, and no
  automatic progress under an unconstrained input stream is implied.

## 11. Review record

- **Reviewer:** `codex`
- **Review date:** 2026-07-18
- **Reviewed scope:** interface names and widths, state encoding, all 64 table
  entries, input ordering, synchronous reset and priority, initialization,
  timing, assumptions, and reachability covers.
- **Compatibility evidence:** the two provenance paths in Section 1 were used
  only for public interface audit.
- **Leakage review:** no private evaluator material was used to formulate a
  normative clause or expected property.
- **Decision:** approved and frozen as public version `1.0.0`; the canonical
  content hash is recorded in `benchmark/synth/SPECIFICATIONS.sha256`.
