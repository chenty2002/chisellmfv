# Public Protocol Specification: LED Traffic Controller

## Status and authority

| Field | Value |
| --- | --- |
| Specification ID | `CHISELLMFV-SYNTH-LED-M-001` |
| Version | 1.0.0 |
| Review date | 2026-07-18 |
| Reviewer | `codex` |
| Visibility | Public |
| Difficulty | M |
| Status | Reviewed authority snapshot |
| Normative authority | This `spec.md` file |
| Canonical content hash | Recorded after review in `benchmark/synth/SPECIFICATIONS.sha256`; the suite ledger binds this exact snapshot without requiring a self-referential hash field |

The following files were used only to align the implementation-compatible
ports, widths, reset interface, realizability, and contract compatibility.
They are not normative authorities.

| Compatibility source | SHA-256 at review |
| --- | --- |
| `benchmark/synth/led_controller/README.md` | `5b90b46bf3fae96746d0e7158a1c503fc133d733216b30d5425e0a836647d2ec` |
| `benchmark/synth/led_controller/src/main/scala/LedController.scala` | `a031fb52c5de9cfce5b44df650b0fe0966bd2bcffe70dbd9c26b6b4b61d9648f` |

No external road-traffic standard is claimed. The state durations, sensor
interpretation, and light encodings are this benchmark IP's contract.

## Public/evaluator boundary

This public document completely defines the abstract golden model. Evaluators
may implement private monitors and select private input sequences, but may not
derive additional obligations from evaluator-only data or relax a public
clause. The expected verification properties below are natural-language
objectives, not hidden golden assertions or bindings. Evaluator-only behavior
does not form part of the protocol.

## Configuration

The controller has four two-bit abstract states and one signed 32-bit global
counter:

- `WAIT=0`, `GO=1`, `WARN=2`, and `STOP=3`;
- light encodings `RED=001`, `YELLOW=010`, and `GREEN=100`;
- the counter runs from 0 through 10 after reset and then wraps to 0;
- decision thresholds are 6 in `GO`, 2 in `WARN`, and 4 in `STOP`.

The counter is global. It is not a per-state residence timer and is not reset
when the state changes.

## Top-level interface

| Port | Direction | Width | Meaning |
| --- | --- | ---: | --- |
| `clock` | input | 1 | State and counter update clock; rising-edge active |
| `reset` | input | 1 | Active-high asynchronous reset for state and counter |
| `pedestrian_button` | input | 1 | Pedestrian request, consulted only in the qualified `WARN` branch |
| `car_sensor` | input | 1 | Vehicle-presence input, consulted only in `WAIT` |
| `lights` | output | 3 | Combinational one-hot light encoding |

## Terms, events, and abstract golden model

A **decision edge** is a rising `clock` edge at which reset is low. Immediately
before that edge, the current state, current counter, and relevant input levels
determine both `lights` and the next state as follows:

| Current state and condition | `lights` | Next state |
| --- | --- | --- |
| `WAIT` and `car_sensor=1` | `RED` | `GO` |
| `WAIT` and `car_sensor=0` | `YELLOW` | `WARN` |
| `GO` and counter less than 6 | `GREEN` | `GO` |
| `GO` and counter at least 6 | `YELLOW` | `WARN` |
| `WARN` and counter less than 2 | `YELLOW` | `WARN` |
| `WARN`, counter at least 2, and `pedestrian_button=1` | `RED` | `STOP` |
| `WARN`, counter at least 2, and `pedestrian_button=0` | `RED` | `GO` |
| `STOP` and counter less than 4 | `RED` | `STOP` |
| `STOP` and counter at least 4 | `GREEN` | `GO` |

At every decision edge the state takes the table's next-state value. In
parallel, a pre-edge counter value of 10 produces post-edge zero; every other
value is incremented by one using signed 32-bit arithmetic. Because `lights`
is combinational, it may respond between edges when a consulted input changes.

For a two-bit state value outside the four listed states, the recovery output
is `YELLOW` and the next state is `WAIT`.

## Clock, reset, and initialization

Asserting `reset` asynchronously forces state to `WAIT` and counter to zero;
no rising edge is required. Deassertion does not itself advance the model. The
next rising edge with reset low is the first decision edge. While reset is
asserted, `lights` is still the combinational `WAIT` output: it is `RED` when
`car_sensor` is high and `YELLOW` when `car_sensor` is low.

## Legal environment and allowed assumptions

1. Reset is asserted long enough for the asynchronous reset to establish
   `WAIT` and counter zero before post-reset properties are judged.
2. All inputs are binary and free of clock/reset setup or recovery/removal
   violations. Four-state and analog behavior are outside this contract.
3. `car_sensor` and `pedestrian_button` may otherwise change independently and
   need not be pulses. Their irrelevance outside the branches named in the
   transition table is a property, not an assumption.
4. Progress bounds may assume reset stays low for the measured interval.
   Repeated reset can legitimately restart the controller indefinitely.
5. No physical-road mutual exclusion, minimum real-time duration, or relation
   between the two sensors may be assumed; only clock-cycle behavior is in
   scope.

## Normative clauses

- **LED-001 — Asynchronous reset.** While `reset=1`, state MUST be `WAIT` and
  counter MUST be zero independently of `clock` edges.
- **LED-002 — Global counter.** On every rising edge with reset low, counter
  MUST become zero when its pre-edge value is 10 and otherwise MUST increment
  by one. State transitions MUST NOT restart it.
- **LED-003 — WAIT behavior.** In `WAIT`, `car_sensor=1` MUST select `RED` and
  next `GO`; `car_sensor=0` MUST select `YELLOW` and next `WARN`.
- **LED-004 — GO behavior.** In `GO`, counter values below 6 MUST select
  `GREEN` and remain in `GO`; values at least 6 MUST select `YELLOW` and next
  `WARN`.
- **LED-005 — WARN timing priority.** In `WARN`, a counter value below 2 MUST
  select `YELLOW` and remain in `WARN`, regardless of `pedestrian_button`.
- **LED-006 — WARN qualified decision.** In `WARN` with counter at least 2,
  `pedestrian_button=1` MUST select `RED` and next `STOP`; otherwise the output
  MUST be `RED` and the next state MUST be `GO`.
- **LED-007 — STOP behavior.** In `STOP`, counter values below 4 MUST select
  `RED` and remain in `STOP`; values at least 4 MUST select `GREEN` and next
  `GO`.
- **LED-008 — Input locality.** `car_sensor` MUST NOT affect behavior outside
  `WAIT`; `pedestrian_button` MUST NOT affect behavior outside the qualified
  branch of LED-006.
- **LED-009 — Output encoding.** For every two-bit state and binary inputs,
  `lights` MUST be exactly one of `RED`, `YELLOW`, or `GREEN` as defined in
  Configuration.
- **LED-010 — Invalid-state recovery.** An unlisted state encoding MUST produce
  `YELLOW` combinationally and MUST enter `WAIT` at the next decision edge.

## Expected verification properties

| Property ID | Class | Expected property |
| --- | --- | --- |
| `LED-P001` | Safety | Asynchronous reset establishes the public initial state, and the reset-time output follows the `WAIT` combinational rule (LED-001, LED-003). |
| `LED-P002` | Timing | The counter follows the 0-through-10 global sequence and is unaffected by state transitions (LED-002). |
| `LED-P003` | Safety | `lights` is always a valid one-hot color and matches the current branch (LED-003 through LED-009). |
| `LED-P004` | Ordering | Threshold comparison has priority over the pedestrian input in early `WARN`, while the pedestrian choice is honored only after qualification (LED-005, LED-006). |
| `LED-P005` | Progress | With reset low, `GO` cannot remain past a decision edge whose pre-edge counter is at least 6; `WARN` cannot remain past a qualified decision edge; and `STOP` cannot remain past a decision edge whose counter is at least 4 (LED-004 through LED-007). |
| `LED-P006` | Safety | Changes on an irrelevant sensor cannot alter either output or next state (LED-008). |
| `LED-C001` | Activation cover | Exercise both `WAIT` branches, both `GO` threshold regions, the early `WARN` branch, both qualified `WARN` branches, and both `STOP` threshold regions (LED-003 through LED-007). |
| `LED-C002` | Observer cover | Observe all three light encodings and the state paths `WAIT→GO`, `WAIT→WARN`, `WARN→STOP`, `WARN→GO`, and `STOP→GO`. |
| `LED-C003` | Observer cover | Observe counter wrap from 10 to 0 while state transition logic continues to use the pre-edge value (LED-002). |

## Optional and undefined behavior

- Behavior before reset has established known state and counter values is not a
  post-reset requirement.
- `X`/`Z` inputs, metastability, clock glitches, and reset recovery/removal
  violations are undefined.
- The controller does not define blinking, an all-lights-off encoding, a
  multi-light encoding, or physical time units.
- An invalid two-bit state has defined one-edge recovery under LED-010; no
  other corrupted internal state behavior is promised.

## Review record

Reviewer `codex` checked the public contract for port/interface compatibility
and realizability against the two files at the recorded hashes and checked
family-local clause/property identifier uniqueness. The main semantic caveat
is the global free-running counter: durations depend on the counter phase at
state entry, so this specification deliberately does not reinterpret
thresholds as fixed per-state residence times.
