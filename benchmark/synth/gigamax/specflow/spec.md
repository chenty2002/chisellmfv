# Public Protocol Specification: Gigamax Coherence and Interlock Challenge

## 1. Status and authority

| Field | Value |
| --- | --- |
| Specification ID | `CHISELLMFV-SYNTH-GIGAMAX-L-001` |
| Version | `1.0.0` |
| Review date | 2026-07-18 |
| Status | Reviewed authority snapshot |
| Reviewer | `codex` |
| Visibility | Public |
| Difficulty | L |
| Normative authority | This `spec.md` file; the cited paper is authoritative only for the external architectural and liveness concepts explicitly attributed to it |
| Canonical content hash | Recorded after suite review in `benchmark/synth/SPECIFICATIONS.sha256`; no self-referential digest is embedded here |

This document is the normative public contract for the Gigamax benchmark
family. Its exact text is the reviewed authority snapshot. The family has
**no publicly available clean executable reference model**. Consequently it
is a protocol bug-detection and liveness challenge, not an implementation-
equivalence task, and no target in the family may be designated golden merely
because its file name suggests a correction or because it came from a public
distribution.

The public historical sources are:

- K. L. McMillan and J. Schwalbe, “Formal Verification of the Gigamax Cache
  Consistency Protocol,” *International Symposium on Shared Memory
  Multiprocessors*, 1991, pp. 242–251. Author publication index:
  <https://mcmil.net/pubs/>; paper identifier:
  <https://mcmil.net/pubs/ISSMM91.pdf>.
- Carnegie Mellon SMV release `r2.5.4.3`, archive
  <https://www.cs.cmu.edu/~modelcheck/smv/smv.r2.5.4.3.tar.gz>, archive path
  `smv/examples/gigamax.smv`. The public example preserved by this repository
  has SHA-256
  `930222bb20ce630ffc3317c681b1523aa9280c4175a285dffba09b329ee2fd19`.

The paper describes the external Gigamax/UIC hierarchy, bus watchers,
interlocks, coherence intent, and progress questions. The CMU example is a
smaller public single-bus abstraction; it is not the complete multi-cluster
model used in the paper. The complete paper model has not been located in the
public sources reviewed for this version. The author-hosted paper URL is
recorded as an identifier; this specification does not claim a hash for a
possibly access-dependent download. The suite-level hash binds the reviewed
interpretation in this file.

The following repository files were consulted only for provenance and current
public-interface compatibility. They are not clean references and cannot
override this specification:

| Compatibility evidence | SHA-256 at review |
| --- | --- |
| `benchmark/synth/gigamax/src/main/scala/Gigamax.scala` | `57ae2ea99562adc59d93552e27d85255acd8e8edd3aa3352a3b5f4c8f6341567` |
| `benchmark/synth/gigamax/README.md` | `5bbd950b81f786cc6d34de4d573d2103ff0c2fd5d5c1d395aa863fa2a9b13310` |
| `benchmark/gigamax/README.md` | `f8410c93eb77aadd891a6689f14bef5f5e3219c42d8f994b9e0d73d318f5499d` |
| `benchmark/gigamax/SOURCES.md` | `e2fed33d699b972623beee48c2d61cb5af16c0aa23052d4f0ac8174497a5b557` |

Any semantic change requires a new version, a fresh review, and a new
suite-level digest.

## 2. Public/evaluator boundary

This document exposes the protocol vocabulary, interface profiles, legal
nondeterminism, coherence invariants, request/reply obligations, and progress
intent. An evaluator may privately translate them into checkers, bind ports,
choose schedules, and retain counterexamples. Those private checkers,
bindings, schedules, traces, expected verdicts, and diagnosis material do not
add to or weaken this contract.

No implementation mutation, defect label, private trigger, oracle, formal
wrapper, source diff, or evaluator result supplied a normative clause. The
properties in Section 9 are public verification objectives, not hidden golden
assertions or hidden signal bindings. This document intentionally gives no
specific defect location or failing trace.

## 3. Configuration and profile boundary

The family exposes two distinct abstractions. They share vocabulary but are
not interchangeable interfaces.

### 3.1 Public single-bus profile

This profile has three processors `p0`, `p1`, and `p2`, one memory participant
`m`, and one abstract bus. Each processor cache has one of three states:

| Code | State | Permission meaning |
| ---: | --- | --- |
| 0 | invalid | no readable or writable cached copy |
| 1 | shared | readable, not uniquely writable |
| 2 | owned | uniquely writable owner copy |

The bus command domain is:

| Code | Command |
| ---: | --- |
| 0 | idle |
| 1 | read shared |
| 2 | read owned |
| 3 | write invalid |
| 4 | write shared |
| 5 | write response invalid |
| 6 | write response shared |
| 7 | invalidate |
| 8 | response |

Raw command codes 9 through 15 are normalized to idle. At most one participant
is master in a step. The public arbitration order for simultaneous master
requests is `p0`, then `p1`, then `p2`, then memory. Inputs with prefix `nd`
make the abstraction's nondeterministic choices explicit; they are not secret
environment constraints.

The emitted source stems `gigamax` and `gigamax_fixed` both use this interface.
Those historical names do not establish either target as a clean reference.

### 3.2 Paper-derived interlock profile

This profile has three cluster watchers `c1`, `c2`, and `c3`; an owner encoded
as memory, `c1`, `c2`, or `c3`; one local and one global interlock; two directed
single-entry request-presence indicators; and explicit pending-work
indicators. Watcher state codes are the same invalid/shared/owned codes above.

Its action domain is:

| Code | Public abstract action |
| ---: | --- |
| 0 | idle |
| 1 | cluster 1 read miss |
| 2 | cluster 3 read miss |
| 3 | cluster 2 write response |
| 4 | cluster 1 receives response |
| 5 | cluster 2 replacement read miss |
| 6 | complete cluster 1 request |
| 7 | complete cluster 2 request |
| 8 | recover to the ready abstraction state |
| 9, 10 | grant cluster 1 shared or owned |
| 11, 12 | grant cluster 2 shared or owned |
| 13, 14 | grant cluster 3 shared or owned |

Raw action code 15 is normalized to idle. An action whose public enabling
conditions are absent may stutter; selecting an action is not by itself proof
that a request completed.

The emitted source stem `gigamax_paper_deadlock` uses this reduced interface.
It is a paper-derived semantic reconstruction, not the unavailable complete
paper model and not a clean golden implementation.

## 4. Top-level interfaces

### 4.1 Public single-bus top `main`

All ports are one bit unless a width is shown.

| Ports | Direction | Width | Public meaning |
| --- | --- | ---: | --- |
| `clock` | input | 1 | Rising-edge transition clock |
| `reset` | input | 1 | Active-high synchronous initialization |
| `ndP0Master`, `ndP1Master`, `ndP2Master`, `ndMMaster` | input | 1 each | Nondeterministic requests to be the current master, resolved in the priority order in Section 3.1 |
| `ndCmdFallbackRaw` | input | 4 | Legalized fallback bus command when proposals do not identify one unique non-idle command |
| `ndP0ReadOwned`, `ndP1ReadOwned`, `ndP2ReadOwned` | input | 1 each | Choice between a shared-read and owned-read request where both are abstractly permitted |
| `ndP0SharedToInvalid`, `ndP1SharedToInvalid`, `ndP2SharedToInvalid` | input | 1 each | Abstract replacement choice from shared to invalid |
| `ndP0ReplyStall`, `ndP1ReplyStall`, `ndP2ReplyStall` | input | 1 each | Explicit participant reply-stall choices |
| `ndMResponse` | input | 1 | Memory choice to offer a response when enabled |
| `ndMReplyStall` | input | 1 | Memory reply-stall choice when no mandatory busy stall applies |
| `CMD` | output | 4 | Current legalized bus command |
| `REPLY_OWNED`, `REPLY_WAITING`, `REPLY_STALL` | output | 1 each | Global OR summaries of the corresponding participant reply conditions |
| `p0Master`, `p1Master`, `p2Master`, `mMaster` | output | 1 each | Resolved one-hot-or-zero master indicators |
| `p0Cmd`, `p1Cmd`, `p2Cmd`, `mCmd` | output | 4 each | Participant command proposals |
| `p0State`, `p1State`, `p2State` | output | 2 each | Processor cache states |
| `p0Snoop`, `p1Snoop`, `p2Snoop` | output | 2 each | Processor observations of remote cache permission |
| `p0Waiting`, `p1Waiting`, `p2Waiting` | output | 1 each | Outstanding processor-request indicators |
| `mBusy` | output | 1 | Memory has an outstanding abstract transaction |
| `p0ReplyStall`, `p1ReplyStall`, `p2ReplyStall`, `mReplyStall` | output | 1 each | Individual stall contributions |
| `p0Abort`, `p1Abort`, `p2Abort`, `mAbort` | output | 1 each | Current command is blocked/aborted for that participant |
| `p0Readable`, `p1Readable`, `p2Readable` | output | 1 each | Cache is shared or owned and has no outstanding wait |
| `p0Writable`, `p1Writable`, `p2Writable` | output | 1 each | Cache is owned and has no outstanding wait |

### 4.2 Paper-derived interlock top `main`

| Port | Direction | Width | Public meaning |
| --- | --- | ---: | --- |
| `clock` | input | 1 | Rising-edge transition clock |
| `reset` | input | 1 | Active-high synchronous initialization |
| `actionRaw` | input | 4 | Candidate abstract action, legalized as in Section 3.2 |
| `action` | output | 4 | Legalized current action |
| `c1Watcher`, `c2Watcher`, `c3Watcher` | output | 2 each | Cluster cache-permission observations |
| `owner` | output | 2 | `0=memory`, `1=c1`, `2=c2`, `3=c3` |
| `c1LocalInterlock`, `c2GlobalInterlock` | output | 1 each | Local/global serialization resources are held |
| `globalQueueC1ReadPublic` | output | 1 | A cluster 1 read is present on the global request path |
| `cluster1QueueC2ReadPublic` | output | 1 | A cluster 2 read is present on the cluster 1 request path |
| `flushC2Pending`, `c3ReadPending`, `responseToC1Pending` | output | 1 each | Named pending protocol work |
| `c1DataInMemory` | output | 1 | The reduced abstraction records cluster 1 data at memory |
| `c1RequestBlocked`, `c2RequestBlocked` | output | 1 each | The corresponding queued request is blocked by the other path's interlock |
| `deadlockCycle` | output | 1 | Both directed requests are blocked in a circular interlock dependency |
| `normalReady` | output | 1 | No interlock, queued request, flush, read, or response remains pending |

## 5. Terms, events, and abstract golden relation

A **protocol step** is a rising edge with `reset=0`. Inputs and current state
determine combinational proposals and observers before the edge; all state
updates at the edge are simultaneous and use that pre-edge state.

A cache permission is **coherent** when at most one cache is owned. An owned
cache is the unique writable/data-owning copy, but it may coexist with
read-only shared copies; every non-owner valid copy must be shared. Multiple
shared copies are permitted and no shared copy is writable. Because these
abstractions contain no address or data payload, coherence means permission
coherence only; they do not prove equality of concrete data values.

A **pending request** has been accepted into a waiting bit, busy bit, or named
queue/pending indicator and has not yet received its matching completion or
explicit abort. A **stall** prevents the affected transition from partially
advancing. A **reply** may retire only work that is already pending.

An **interlock** serializes conflicting protocol work. Acquiring one must be
associated with a request that can eventually release it. A **closed circular
wait** is a reachable state or closed set of states in which requests remain
pending, each is blocked by an interlock held for the other request, and no
legal continuation can complete either request or return to `normalReady`.

There is no deterministic executable golden machine for this family. The
abstract golden model is therefore a **trace relation**: a target trace is
acceptable only if it satisfies the domains, coherence invariants,
request/reply ordering, interlock discipline, and progress clauses below.
Cycle-by-cycle equality with any one historical SMV or Chisel target is neither
necessary nor sufficient for conformance.

For liveness, this specification distinguishes two claims:

- **branching availability:** from each reachable state, there exists a finite
  legal continuation that makes the named progress; and
- **conditional response:** if an explicit finite sequence of enabling choices
  is supplied, the enabled request completes.

Neither claim silently assumes weak or strong fairness over an infinite
nondeterministic schedule.

## 6. Clock, reset, and initialization

Reset is active high and synchronous. A rising edge with `reset=1` establishes
the initial state for the selected interface.

For the single-bus profile, all processor cache and snoop states are invalid,
all processor waiting indicators are false, and memory is not busy. The
resolved master and command outputs remain combinational functions of current
inputs and initialized state.

For the paper-derived profile, `c1` and `c3` watchers are invalid, the `c2`
watcher is owned, the owner is `c2`, both interlocks and both queue indicators
are clear, all pending-work indicators are clear, and `normalReady` is true.

No post-reset property is judged before a qualifying reset edge. Reasserting
reset starts a new independent protocol episode and may cancel pending work;
progress obligations therefore apply only while reset remains low.

## 7. Legal environment and allowed assumptions

1. **Reset establishment.** Reset is sampled high on at least one rising edge
   before either profile is checked.
2. **Definite choices.** All inputs are binary and stable at relevant edges.
   Four-state electrical behavior is outside this abstract transition system.
3. **Choice freedom.** Unless a particular conditional-progress scenario says
   otherwise, every `nd` input and every legal action code remains
   unconstrained. Safety proofs may not suppress a master, command, stall,
   replacement, grant, queue direction, or response merely to avoid a bad
   state.
4. **No invented fairness.** No weak-fairness, strong-fairness, round-robin,
   bounded-stall, or eventual-response assumption is globally allowed. The
   public sources do not justify one. Universal response properties must state
   their finite enabling premise explicitly; otherwise the required liveness
   form is branching availability.
5. **Profile separation.** A run selects exactly one top-level interface. Ports
   or initial states from the other profile may not be imported as assumptions.
6. **Abstract scope.** All activity refers to one abstract cache line. No
   address aliasing, concrete data value, eviction policy, or physical network
   latency may be assumed or inferred.
7. **Progress interval.** Reset may be assumed low over the finite path used to
   witness or refute progress. This prevents reset itself from vacuously
   canceling the request and is not scheduler fairness.

Raw single-bus command codes 9 through 15 and paper action code 15 are legal
inputs with the defined normalization to idle; they need not be excluded.

## 8. Normative clauses

- **GIGA-N-001 — Relational authority.** Conformance MUST be judged against
  this specification's trace relation, not by treating any family target as a
  clean reference implementation.
- **GIGA-N-002 — Profile separation.** Each run MUST use exactly one interface,
  domain, and reset state from Section 3 and Section 6.
- **GIGA-N-010 — Legal domains.** Visible commands, cache/watcher states,
  actions, and owners MUST remain in their listed encodings; out-of-range raw
  inputs MUST normalize to idle as specified.
- **GIGA-N-011 — Single master.** In the single-bus profile, at most one of
  `p0Master`, `p1Master`, `p2Master`, and `mMaster` may be high, with the public
  priority order applied to simultaneous requests.
- **GIGA-N-012 — Unique proposal preservation.** If exactly one participant
  proposes a non-idle command, `CMD` MUST equal that proposal. A fallback
  choice may be used only when the proposals do not identify one unique
  non-idle command.
- **GIGA-N-013 — Reply summaries.** `REPLY_OWNED`, `REPLY_WAITING`, and
  `REPLY_STALL` MUST faithfully summarize their current individual
  contributions and MUST NOT hide an asserted contributor.
- **GIGA-N-020 — Permission coherence.** In every reachable non-reset state,
  owned/shared/invalid permissions MUST satisfy the coherence definition in
  Section 5. In particular, at most one processor or watcher may be owned; any
  other valid copies must be shared and read-only.
- **GIGA-N-021 — Access observers.** A single-bus processor may be readable
  only in shared or owned state with no pending wait, and writable only in
  owned state with no pending wait. No two processors may be writable together.
- **GIGA-N-022 — Owner agreement.** In the paper-derived profile, an owned
  watcher MUST agree with the named owner; a grant or response MUST update
  watcher and owner permissions coherently as one protocol transition.
- **GIGA-N-030 — Request before reply.** A response or completion MUST retire
  only a previously pending matching request. It MUST NOT clear unrelated
  waiting, queue, or interlock state.
- **GIGA-N-031 — Pending preservation.** A request that has been accepted but
  neither completed nor explicitly aborted MUST remain represented by its
  waiting, busy, queue, or pending indicator.
- **GIGA-N-032 — Stall atomicity.** A stalled or aborted transfer MUST NOT
  perform a partial permission, owner, queue, or waiting-state update.
- **GIGA-N-033 — Command/completion ordering.** A write response,
  invalidation, or ordinary response MUST follow the request or ownership
  event that enables it; completion cannot precede activation.
- **GIGA-N-040 — Interlock ownership.** Each asserted interlock MUST correspond
  to outstanding work on its path and MUST be released when that work
  completes or an explicit legal recovery returns the abstraction to ready.
- **GIGA-N-041 — Queue/interlock discipline.** A blocked request MUST remain
  queued exactly once, and clearing a queue entry MUST coincide with its own
  completion or legal recovery, not the other path's unrelated event.
- **GIGA-N-042 — No closed circular wait.** No reachable closed state or closed
  set of states may keep both directed requests blocked solely by interlocks
  whose release depends on those same blocked requests.
- **GIGA-N-043 — Ready-state reachability.** From every reachable state of the
  paper-derived profile, there MUST exist a finite legal continuation to
  `normalReady` without reset.
- **GIGA-N-044 — Watcher availability.** From every reachable paper-derived
  state and for each watcher, there MUST exist a legal continuation to shared
  and a legal continuation to owned. This is branching availability, not a
  claim that every arbitrary schedule eventually grants both states.
- **GIGA-N-050 — Conditional request progress.** When a pending request is
  supplied the finite non-stalling master/response/completion choices needed
  by its current path, it MUST complete and clear only its own pending state.
- **GIGA-N-060 — Simultaneous transition.** State changes at a protocol step
  MUST be computed from the pre-edge state and current inputs; one participant
  may not observe another participant's post-edge state in the same step.
- **GIGA-N-061 — Reset state.** A rising edge with reset high MUST establish
  the selected profile's complete initial state from Section 6.

## 9. Expected verification properties

These objectives are natural-language public verification intent. They are not
hidden golden assertions, private signal bindings, secret schedules, or an
evaluator-only obligation map. A violation is reported as a protocol-property
failure; there is no clean target whose output can automatically settle the
dispute by comparison.

| Property ID | Class | Clause coverage | Expected public check |
| --- | --- | --- | --- |
| `GIGA-P-REL-001` | algorithm/reference relation | GIGA-N-001, GIGA-N-002 | Check trace refinement against the public relational clauses; do not use cycle equality with any historical family target as a golden oracle |
| `GIGA-P-SAF-001` | safety/domain | GIGA-N-010, GIGA-N-011, GIGA-N-013 | Encodings stay legal, master resolution is one-hot-or-zero, and reply summaries equal their contributors |
| `GIGA-P-SAF-002` | safety/coherence | GIGA-N-020 through GIGA-N-022 | Every reachable permission state preserves unique ownership, shared-read-only behavior, access observers, and owner agreement |
| `GIGA-P-SAF-003` | safety/request integrity | GIGA-N-012, GIGA-N-030 through GIGA-N-033 | A unique request reaches the bus, replies retire only matching pending work, and stalls cannot cause partial updates |
| `GIGA-P-SAF-004` | safety/interlock | GIGA-N-040, GIGA-N-041 | Interlocks and queue-presence indicators remain paired with their own outstanding work and release events |
| `GIGA-P-TIM-001` | timing | GIGA-N-060, GIGA-N-061 | Reset and every simultaneous transition use the specified edge and pre-edge state semantics |
| `GIGA-P-ORD-001` | ordering | GIGA-N-030, GIGA-N-033, GIGA-N-041 | Activation precedes response, response precedes retirement, and independent queue paths do not clear or complete one another's request |
| `GIGA-P-PRG-001` | progress/no deadlock | GIGA-N-042, GIGA-N-043 | No reachable closed circular interlock wait exists, and every reachable paper-derived state has a finite path back to `normalReady` |
| `GIGA-P-PRG-002` | progress/request-reply | GIGA-N-050 | Under an explicitly supplied finite non-stalling completion sequence, each pending request completes without requiring an unstated fairness premise |
| `GIGA-P-PRG-003` | progress/branching availability | GIGA-N-044 | From every reachable paper-derived state, each watcher retains some finite path to shared and some finite path to owned |
| `GIGA-P-ACT-001` | activation cover | GIGA-N-011, GIGA-N-012, GIGA-N-030 | Exercise every single-bus master, every command class, a pending request, a stall/abort, and a later matching response |
| `GIGA-P-ACT-002` | activation cover | GIGA-N-020 through GIGA-N-022 | Exercise invalid-to-shared, invalid-to-owned, owned-to-shared, and invalidation transitions without assuming they always occur |
| `GIGA-P-ACT-003` | activation cover | GIGA-N-040, GIGA-N-041 | Exercise each interlock, each directed request-presence indicator, each completion path, and legal recovery |
| `GIGA-P-OBS-001` | observer cover | GIGA-N-021, GIGA-N-030 through GIGA-N-032 | Observe readable and writable both true and false, waiting/busy assertion and clearing, and each global reply summary both low and high |
| `GIGA-P-OBS-002` | observer cover | GIGA-N-042 through GIGA-N-044 | Observe `normalReady`, each blocked-request observer, and any simultaneous-blocking state; classify the latter by whether a finite escape exists rather than treating reachability alone as deadlock |

## 10. Optional and undefined behavior

- When several participants propose non-idle commands together, the precise
  fallback command is nondeterministic within the legal command domain. The
  unique-proposal case is not nondeterministic.
- A disabled paper-derived action may stutter. The `action` output reports the
  legalized selection, not proof of state change or completion.
- No fixed request latency is specified. Universal eventual response under an
  adversarial scheduler is not promised, and no fairness condition may be
  inferred from the word “progress.”
- Full data coherence, memory consistency, address routing, queue depth beyond
  the exposed presence flags, replacement policy, and physical network timing
  are outside these abstractions.
- The reduced paper-derived interface is not a claim to reproduce every
  transition or component of the unpublished complete paper model.
- Four-state inputs, metastability, clock glitches, and setup/hold violations
  are outside scope.
- Historical source names, provenance, or a successful self-equivalence check
  do not make a target a clean reference.

## 11. Review record

- **Reviewer:** `codex`
- **Review date:** 2026-07-18
- **Reviewed scope:** public paper/source boundary, both top-level interfaces,
  command/action/state domains, reset states, permission coherence,
  request/reply and stall ordering, interlock ownership, closed-wait freedom,
  branching availability, legal nondeterminism, and fairness limits.
- **Compatibility evidence:** the files and hashes in Section 1 were used only
  to align the public interface and provenance boundary. They were not treated
  as clean executable specifications.
- **Leakage review:** no defect label, implementation mutation, private
  trigger, oracle, formal wrapper, source diff, evaluator trace, or expected
  verdict supplied a normative clause or property.
- **Semantic limitation:** no complete clean paper model is publicly available,
  the CMU single-bus example is structurally smaller than the paper system,
  and the current paper-derived interface is a reduced reconstruction. This
  version therefore supports bug detection, invariant checking, and liveness
  analysis only; it does not authorize “matches reference” claims.
- **Decision:** approved and frozen as public version `1.0.0`; the canonical
  content hash is recorded in `benchmark/synth/SPECIFICATIONS.sha256`.
