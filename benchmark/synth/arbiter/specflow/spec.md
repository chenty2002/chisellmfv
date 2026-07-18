# Public Protocol Specification: Four-Request Arbiter

## Status and authority

| Field | Value |
| --- | --- |
| Specification ID | `CHISELLMFV-SYNTH-ARB-M-001` |
| Version | 1.0.0 |
| Review date | 2026-07-18 |
| Reviewer | `codex` |
| Visibility | Public |
| Difficulty | M |
| Status | Reviewed authority snapshot |
| Normative authority | This `spec.md` file |
| Canonical content hash | Recorded after review in `benchmark/synth/SPECIFICATIONS.sha256`; the suite ledger, rather than a self-referential field, binds this exact snapshot |

This document is the public natural-language contract for the `arbiter` family.
The compatibility sources below were inspected only to align port names,
widths, reset-interface semantics, realizability, and contract compatibility.
They are evidence for this review, not independent normative authorities.

| Compatibility source | SHA-256 at review |
| --- | --- |
| `benchmark/synth/arbiter/README.md` | `f09d9671f711bf8a15650579e6d04b4a55c9e5a786c3f63e64cf30bb76d9eaef` |
| `benchmark/synth/arbiter/src/main/scala/Arbiter.scala` | `98c8ed996d0b7898368723d19a99c2aa638d57e5448e8235e52cea41d57dd9c6` |

No external bus or arbitration standard is claimed. In particular, the queue
and output timing below are this benchmark IP's contract, not a generic arbiter
policy.

## Public/evaluator boundary

Everything needed to construct an abstract golden model is public in this
document. An evaluator may translate the clauses into private checkers and may
choose private activation sequences, but those checkers and sequences do not
add requirements, waive requirements, or redefine legal inputs. The expected
properties listed here are verification objectives, not hidden golden
assertions or signal bindings. No evaluator-only behavior is part of this
specification.

## Configuration

The configuration is fixed:

- four one-bit request inputs;
- a four-entry queue whose entries are three-bit requester tags;
- three controller phases: `INIT`, `ANALYZE`, and `ASSIGN`;
- requester tags `REQUEST1=100`, `REQUEST2=010`, `REQUEST3=001`, and
  `REQUEST4=111`;
- four-bit grant encodings `REQUEST1=1000`, `REQUEST2=0100`,
  `REQUEST3=0010`, and `REQUEST4=0001`.

There are no programmable parameters and no ready/accept signal.

## Top-level interface

All values are unsigned unless stated otherwise.

| Port | Direction | Width | Meaning |
| --- | --- | ---: | --- |
| `clock` | input | 1 | State-update clock; behavior is defined at rising edges |
| `reset` | input | 1 | Active-high synchronous reset |
| `REQUEST1` | input | 1 | Requester 1 level |
| `REQUEST2` | input | 1 | Requester 2 level |
| `REQUEST3` | input | 1 | Requester 3 level |
| `REQUEST4` | input | 1 | Requester 4 level |
| `GRANT_O` | output | 4 | Registered grant code |

The capitalization is part of the implementation-compatible interface.

## Terms, events, and abstract golden model

A **sample epoch** is an `INIT` or `ASSIGN` rising edge at which all four
request levels are captured into `sampled`. The vector `prior` stores the
sample used by the preceding `ANALYZE` operation. A **new request** for
requester *i* exists when `sampled[i]` is one and `prior[i]` is zero at an
`ANALYZE` edge.

The abstract state is:

- phase, initially `INIT`;
- `queue[0..3]`, with `queue[0]` as the entry selected next;
- `sampled[1..4]` and `prior[1..4]`;
- an internal four-bit `grant` register;
- the visible four-bit `GRANT_O` register.

At an `ANALYZE` edge, new requests are processed sequentially in numeric order
1, 2, 3, 4. Processing one request shifts the old entries toward index 3,
drops the old index 3 entry, and inserts that requester's tag at index 0.
Consequently, if several requests are new together, the highest-numbered one
among them occupies index 0 after that edge.

At an `ASSIGN` edge, a nonzero `prior` vector enables one dequeue operation.
The pre-edge `queue[0]` tag determines the next internal grant, after which the
queue shifts toward index 0 and zero enters index 3. If `prior` is all zero,
both the queue and internal grant retain their values. The visible output is
updated from the internal grant only at `ANALYZE` edges.

## Clock, reset, and initialization

All state changes occur on `clock` rising edges. When `reset` is high at a
rising edge, phase becomes `INIT`; the queue, both request-history vectors,
the internal grant, and `GRANT_O` all become zero. While reset remains high,
that initialized state is re-established at every rising edge. Reset has no
asynchronous effect between edges.

On the first non-reset edge in `INIT`, the request inputs are sampled and phase
advances to `ANALYZE`. Thus a request already high at that first sample is a
new request relative to the reset-zero `prior` vector.

## Legal environment and allowed assumptions

1. Reset is sampled high on at least one rising edge before post-reset behavior
   is judged. This establishes the public initial state.
2. Request and reset inputs are binary and meet ordinary setup/hold requirements
   at relevant rising edges. This document does not define four-state `X`/`Z`
   interpretation.
3. Inputs may otherwise change freely. Only levels present at sample epochs
   participate in the model; pulses absent from those epochs need not be seen.
4. No queue-capacity assumption is allowed for safety checking. Overflow by a
   new insertion has the defined drop-oldest-at-index-3 behavior above.
5. A progress objective may assume that reset is not reasserted for the finite
   interval being measured. Without that fairness condition, reset can always
   prevent progress.

## Normative clauses

- **ARB-001 — Reset state.** A rising edge with `reset=1` MUST establish the
  complete initialized state described above and MUST drive `GRANT_O` to zero.
- **ARB-002 — Phase schedule.** In the absence of reset, `INIT` MUST advance to
  `ANALYZE`; `ANALYZE` MUST advance to `ASSIGN`; and `ASSIGN` MUST advance to
  `ANALYZE`.
- **ARB-003 — Sampling points.** Request inputs MUST be captured in `INIT` and
  `ASSIGN`, and MUST NOT be recaptured in `ANALYZE`.
- **ARB-004 — New-request test.** At an `ANALYZE` edge, requester *i* MUST be
  eligible for insertion exactly when its captured current level is one and
  its stored prior level is zero. The prior vector MUST then become the
  captured vector.
- **ARB-005 — Ordered insertion.** Eligible requesters MUST be inserted in the
  order 1, 2, 3, 4 using the shift-and-insert operation defined by the abstract
  model. Every inserted tag MUST identify the same requester.
- **ARB-006 — Bounded queue.** Each insertion MUST preserve exactly four queue
  slots; an entry shifted beyond index 3 MUST be discarded.
- **ARB-007 — Assignment enable.** An `ASSIGN` edge MUST select and remove one
  pre-edge head entry when any bit of `prior` is one. If all bits of `prior`
  are zero, the queue and internal grant MUST hold.
- **ARB-008 — Grant decoding.** A selected requester tag MUST map to the grant
  encodings in Configuration. A zero or otherwise unmapped head tag MUST map
  to internal grant zero.
- **ARB-009 — Visible grant timing.** `GRANT_O` MUST load the pre-edge internal
  grant at every `ANALYZE` edge and MUST hold at other non-reset edges.
- **ARB-010 — Simultaneous arrivals.** When multiple new requests are detected
  together, their post-edge queue order MUST be the reverse of requester
  number among those arrivals, interleaved ahead of retained older entries as
  implied by ARB-005.
- **ARB-011 — Level-sensitive dequeue enable.** The dequeue enable in ARB-007
  depends on the stored request levels, not on queue occupancy and not on a new
  edge. A held-high sampled request MAY therefore enable later dequeues even
  when it is not inserted again.

## Expected verification properties

These objectives are written in natural language and cite only public
clauses.

| Property ID | Class | Expected property |
| --- | --- | --- |
| `ARB-P001` | Safety | Reset always yields zero queue/history/grants and `INIT` phase (ARB-001). |
| `ARB-P002` | Ordering | Every subset of simultaneous new requests is inserted in numeric processing order, producing the post-edge order required by ARB-005 and ARB-010. |
| `ARB-P003` | Safety | No requester is inserted without its own zero-to-one sampled transition, and every inserted tag decodes to that requester (ARB-004, ARB-005). |
| `ARB-P004` | Timing | Sampling, analysis, assignment, and visible output updates occur only on their specified phases (ARB-002, ARB-003, ARB-009). |
| `ARB-P005` | Safety | Queue updates never create a fifth retained entry; full-queue insertion discards exactly the former tail (ARB-006). |
| `ARB-P006` | Ordering | A qualifying assignment uses the pre-edge head before shifting it out (ARB-007, ARB-008). |
| `ARB-P007` | Progress | With reset absent, a new request that becomes the selected head and encounters an enabled assignment reaches the internal grant, then reaches `GRANT_O` at the next `ANALYZE` edge (ARB-002, ARB-007 through ARB-009). |
| `ARB-C001` | Activation cover | Exercise each requester as a lone new arrival and exercise at least one all-four simultaneous arrival (ARB-004, ARB-010). |
| `ARB-C002` | Activation cover | Exercise insertion into a full queue and an `ASSIGN` edge with `prior=0` (ARB-006, ARB-007). |
| `ARB-C003` | Observer cover | Observe all four nonzero grant encodings on `GRANT_O`, as well as a return to zero through selection of an empty tag (ARB-008, ARB-009). |

## Optional and undefined behavior

- Power-on behavior before a qualifying reset edge is outside this contract.
- Four-state inputs, clock glitches, and setup/hold violations are undefined.
- Requests that rise and fall entirely between sample epochs may be ignored.
- Queue overflow is not undefined; ARB-006 defines it.
- This circuit provides no cancellation, acknowledgement, fairness, or
  ready/backpressure protocol beyond the exact state machine above.

## Review record

Reviewer `codex` checked the public contract for port/interface compatibility
and realizability against the two files at the recorded hashes, then reviewed
clause and property identifiers for family-local uniqueness. No external
standard was used. The principal semantic caveat is intentional and public:
the structure named `queue` is not a conventional fairness queue; insertion is
at its selected head and dequeue is enabled by stored request levels. This
document makes that behavior explicit rather than inferring a generic arbiter
policy.
