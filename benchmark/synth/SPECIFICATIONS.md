# Natural-Language Public Specification Suite

This directory contains the public circuit specifications used by
ChiselSpecFlow.  Each circuit family owns one English natural-language
contract at `benchmark/synth/<family>/specflow/spec.md`.  The contract states
the intended behavior (the golden model) and the normative properties that a
candidate implementation is expected to satisfy.  It is deliberately not an
assertion file, a signal binding, or an implementation transcript.

## Suite contract

The reviewed `spec.md` text is the functional authority for a circuit--spec
task.  External standards and papers cited by a contract are upstream semantic
authorities; the local Chisel source and translation README may be consulted
only to make the public interface and elaboration configuration compatible.
They do not override the contract's normative behavior.

The verification method may read a circuit's public `spec.md`, the selected
Chisel source, and its declared elaboration configuration.  It must not read
reference RTL or SMV, implementation-to-reference equivalence material,
defect descriptions or diffs, directed triggers, expected diagnoses, golden
assertions, clause-to-binding maps, or evaluator outcomes.  Those artifacts
belong to a separately controlled evaluator package.

Normative clauses use stable family-specific identifiers and the words
**shall**, **must**, **shall not**, and **must not**.  Descriptive text,
examples, review notes, and the expected-property section do not silently add
requirements.  An expected property is a natural-language verification target
linked to normative clause IDs; it is not a hidden checker implementation.
Only rows classified as safety, timing, ordering, data, or progress belong to
the primary formal-property denominator.  Scope statements and checks that are
true solely because of elaborated port widths are interface-review notes, not
proof targets.  Activation and observation entries are covers and are reported
separately from proved properties.

An environment assumption is admissible only when the public interface or an
upstream standard requires it.  It may constrain inputs or fairness supplied
by the environment, but it may not constrain a DUT output or internal state,
exclude a legal request, force the expected answer, or make a required
activation unreachable.  Progress claims are conditional on their explicitly
listed fairness assumptions.

## Consumption prerequisite

Several current Scala translation files still colocate the selected generator
with configuration registries and evaluator-oriented annotations.  Before a
real model-visible run, the project/configuration layer must materialize a
single-configuration Chisel source view that excludes those registries and
annotations.  Pointing an authoring model at an entire multi-configuration
translation file would violate the boundary above even though the public
`spec.md` itself is clean.  This specification suite records that prerequisite;
it does not claim to sanitize the Chisel inputs.

## Required document structure

Every family contract records:

1. authority, version, date, scope, provenance, and reviewer;
2. the public/evaluator boundary;
3. configuration and top-level interface;
4. abstract state, events, and natural-language golden model;
5. clock, reset, initialization, and legal environment;
6. numbered normative functional, timing, ordering, data, and progress clauses;
7. allowed assumptions and undefined or optional behavior;
8. expected safety, activation, observation, and progress properties; and
9. a review record with unresolved limitations.

## Family index

| Difficulty | Family | Public contract | Primary semantic shape |
| --- | --- | --- | --- |
| S | `alu` | [`alu/specflow/spec.md`](alu/specflow/spec.md) | combinational arithmetic/logic and flags |
| S | `decoder_3_to_8` | [`decoder_3_to_8/specflow/spec.md`](decoder_3_to_8/specflow/spec.md) | enabled active-low truth table |
| S | `counter` | [`counter/specflow/spec.md`](counter/specflow/spec.md) | reset, enabled next-state, wrap indication |
| S | `fsm_16` | [`fsm_16/specflow/spec.md`](fsm_16/specflow/spec.md) | complete 16-state transition relation |
| M | `arbiter` | [`arbiter/specflow/spec.md`](arbiter/specflow/spec.md) | request capture, ordering, and grant sequencing |
| M | `led_controller` | [`led_controller/specflow/spec.md`](led_controller/specflow/spec.md) | timed traffic/pedestrian control FSM |
| M | `i2c` | [`i2c/specflow/spec.md`](i2c/specflow/spec.md) | WISHBONE register protocol and I2C transfers |
| M | `sdram_controller` | [`sdram_controller/specflow/spec.md`](sdram_controller/specflow/spec.md) | initialization, refresh, read/write command sequencing |
| L | `reed_solomon_decoder` | [`reed_solomon_decoder/specflow/spec.md`](reed_solomon_decoder/specflow/spec.md) | GF(2^8) block-decoding reference relation |
| L | `sha3` | [`sha3/specflow/spec.md`](sha3/specflow/spec.md) | padded Keccak-f[1600] sponge computation |
| L | `gigamax` | [`gigamax/specflow/spec.md`](gigamax/specflow/spec.md) | abstract coherence safety and conditional progress |

`SPECIFICATIONS.sha256` freezes the exact bytes of every public contract after
review.  A semantic change requires a new per-contract version, a new checksum,
and a review note; a failed baseline or evaluation run is never a reason to
rewrite the authority in place.

## Review and use

The current suite is reviewed by `codex` for interface completeness,
cross-clause consistency, explicit reset/environment boundaries, and absence
of evaluator-only content.  Formalization remains a separate Stage 1 activity:
the authoring workflow must derive obligations, bindings, monitors, assumptions,
and covers from these texts and preserve each clause reference through proof or
counterexample reporting.
