# Public Protocol Specification: Shortened Reed-Solomon Decoder RS(204,188)

## 1. Status and authority

| Field | Value |
| --- | --- |
| Specification ID | `CHISELLMFV-SYNTH-RS204188-L-001` |
| Version | `1.0.0` |
| Review date | 2026-07-18 |
| Status | Reviewed authority snapshot |
| Reviewer | `codex` |
| Visibility | Public |
| Difficulty | L |
| Normative authority | This `spec.md` file, together with the public algorithm source identified below |
| Canonical content hash | Recorded after suite review in `benchmark/synth/SPECIFICATIONS.sha256`; the digest is not embedded in this self-referential document |

This document is the normative public contract for the family. The exact text
of version `1.0.0` is the reviewed authority snapshot. An implementation may be
used to confirm port compatibility and realizability, but observed
implementation behavior cannot amend this contract.

The external algorithm profile is the OpenCores project **Reed Solomon Decoder
(204,188)**, project identifier `reed_solomon_decoder`, public revision 5
(project page updated 2009-11-23):

- project overview: <https://opencores.org/projects/reed_solomon_decoder>;
- project documentation identifier: `Reed_Solomon_Decoder_204_188.pdf` in the
  public OpenCores SVN tree; and
- public profile: 204 input byte symbols, 188 output byte symbols, correction
  capability eight symbols, generator roots alpha through alpha to the
  sixteenth power, and field polynomial
  `x^8 + x^4 + x^3 + x^2 + 1`.

The OpenCores project profile is the primary public source for those algorithm
parameters. This reviewed specification fixes their interpretation and the
streaming contract below. The external web content is not treated as a mutable
runtime oracle.

The following repository files were consulted only for family/interface
compatibility, not as normative algorithm authorities:

| Compatibility evidence | SHA-256 at review |
| --- | --- |
| `benchmark/synth/reed_solomon_decoder/README.md` | `4d0308550aab007d9e4689354c837cd5edcb5df53f3767983c64ef40b1426ce8` |
| `benchmark/synth/reed_solomon_decoder/src/main/scala/RS_dec.scala` | `ebcd96800f19d1e38d2b311ca9cef9dc4a318fa9f44e5d78ec91bec8c50578b9` |
| `benchmark/synth/reed_solomon_decoder/src/main/scala/input_syndromes.scala` | `e700b8efe95e282aaec3fbe7503da0406b11dd4f04e89cc12fc4d819d849b9c2` |
| `benchmark/synth/reed_solomon_decoder/src/main/scala/out_stage.scala` | `5137f0481a7786721efbafa3d0c33a82b8839b6469f0c6f55a4fcde812df52af` |

The README records translation and workflow compatibility only. Its
implementation-variant material is outside this specification and supplied no
normative requirement. Any semantic change requires a new version, a fresh
review record, and a new suite-level digest.

## 2. Public/evaluator boundary

Everything needed to construct the abstract decoder model is public in this
document. An evaluator may privately compile these clauses into properties,
bind ports, choose codewords and error patterns, and choose proof bounds. Such
checkers, bindings, vectors, traces, expected verdicts, and diagnosis material
do not add to or weaken the public contract.

No implementation variant, mutation description, private trigger, oracle
file, formal wrapper, implementation difference, or evaluator result was used
to formulate a normative clause. The expected properties in Section 9 are
public verification intent, not hidden golden assertions or hidden bindings.

## 3. Fixed configuration

- Code: shortened systematic RS(204,188), derived from the parent
  RS(255,239) code by shortening 51 leading information symbols.
- Symbol width: 8 bits.
- Field: GF(2^8) in polynomial basis modulo
  `p(x) = x^8 + x^4 + x^3 + x^2 + 1`.
- Primitive element: alpha is the residue class of `x`, represented by byte
  `0x02`.
- Generator polynomial:
  `g(x) = product from i=1 through 16 of (x + alpha^i)`.
- External codeword order: 188 systematic data symbols followed by 16 parity
  symbols.
- Minimum symbol distance: 17.
- Guaranteed correction radius: at most eight erroneous byte symbols in one
  204-symbol received word.
- Erasures: unsupported; there is no erasure input.
- Successful output: the 188 corrected systematic data symbols, in their
  original order.

All additions in the field are bitwise exclusive-or. A symbol error is one
received byte that differs from the corresponding codeword byte, irrespective
of how many bits differ inside that byte.

## 4. Top-level interface

| Port | Direction | Width | Public meaning |
| --- | --- | ---: | --- |
| `clock` | input | 1 | State-update clock; input and output events are defined at rising edges |
| `reset` | input | 1 | Active-high asynchronous reset for the public streaming episode |
| `CE` | input | 1 | One-cycle input-symbol acceptance strobe |
| `input_byte` | input | 8 | Received codeword symbol sampled on an input acceptance event |
| `Out_byte` | output | 8 | Corrected data symbol, meaningful on an output transfer event |
| `Valid_out` | output | 1 | Output-phase qualifier |
| `CEO` | output | 1 | Per-symbol output strobe; an output transfer requires both `CEO` and `Valid_out` |

There is no input-ready signal, output-ready signal, uncorrectable indication,
error count, or frame identifier. The environment therefore obeys the cadence
and framing rules in Section 7.

## 5. Terms, events, and abstract golden model

An **input acceptance event** is a rising edge with `reset=0` and `CE=1`.
`input_byte` at that edge is the next received symbol. Exactly 204 consecutive
input acceptance events, counted independently of idle clock cycles, form one
**received word** `R = R[0] ... R[203]`.

An **output transfer event** is a rising edge with `reset=0`, `Valid_out=1`,
and `CEO=1`. `Out_byte` at that edge is the next corrected data symbol. Output
events are ordered by occurrence; idle cycles do not create symbols.

Let `C` be the set of 204-symbol systematic codewords in the fixed
configuration. For a received word `R`, the abstract golden decoder is defined
when there is a codeword `Cword` in `C` whose symbol Hamming distance from `R`
is at most eight. The distance-17 code makes that codeword unique. The golden
output sequence is then
`Cword[0], Cword[1], ..., Cword[187]`.

Equivalently, a reference model may prepend 51 zero information symbols,
decode in the parent RS(255,239) code with the stated field and generator,
discard the shortened prefix, and return the 188 systematic symbols. The
abstract relation, not any particular Berlekamp-Massey, Chien-search, Forney,
memory, or pipeline architecture, is authoritative.

For a stream of complete received words, the golden model applies independently
to each 204-event group. Corresponding 188-event output groups must appear in
the same frame order; internal overlap between frames is permitted but mixing
or reordering their symbols is not.

## 6. Clock, reset, and initialization

Functional state advances on rising `clock` edges. Assertion of `reset` is
active high and asynchronous for the compatibility interface: it cancels the
current partial episode and returns the visible output protocol to idle without
waiting for a rising edge. A verification episode begins only after reset has
been observed asserted and is then deasserted cleanly.

No input or output event occurs while reset is high. After reset release,
`CEO` and `Valid_out` must remain inactive until a complete received word has
been accepted and decoded. Internal memories need not expose a value after
reset; a complete legal word overwrites all storage relevant to that frame.

Reasserting reset abandons any partially accepted word and any partially
emitted output group. The next post-reset input event starts a new frame.

## 7. Legal environment and allowed assumptions

1. **Reset establishment.** Reset is asserted long enough to be observed by
   the asynchronous state elements, then deasserted away from an active clock
   edge. This establishes a deterministic episode and avoids analog recovery
   and removal questions outside the digital model.
2. **Definite symbols.** `CE` and `input_byte` are binary and meet ordinary
   setup/hold requirements at each input event. Four-state electrical behavior
   is outside this contract.
3. **Serialized cadence.** `CE` is high for one rising edge only, and any two
   input acceptance edges are separated by at least eight rising-edge
   intervals. This compatibility assumption gives the serialized syndrome
   datapath time to process all 16 syndromes; it does not constrain symbol
   values.
4. **Complete framing.** Post-reset input events are supplied in complete
   groups of 204. A final incomplete group has no required result.
5. **No output backpressure.** The environment samples every output transfer
   event. The interface has no mechanism to pause or replay output.
6. **Progress interval.** A progress check may assume reset remains low after
   a complete word is accepted. No other environmental fairness assumption is
   required because decoding then proceeds internally.
7. **Correctability premise.** Exact data correctness may be checked under the
   explicit premise of at most eight symbol errors. This premise is the public
   capability boundary, not a way to exclude any pattern within that radius.

The public frame has 204 symbols. A raw translation may contain internal
pipeline priming or sentinel storage cycles, but such implementation details
must be handled by an interface adapter and must not be counted as a 205th
codeword symbol.

## 8. Normative clauses

- **RS204-N-001 — Field and code.** The decoder MUST implement the exact
  GF(2^8), generator polynomial, shortening, and systematic codeword order in
  Section 3.
- **RS204-N-002 — Input event.** Only an edge qualified by `CE` after reset
  release consumes `input_byte`; all other input cycles MUST NOT add symbols to
  the current received word.
- **RS204-N-003 — Frame size.** Every complete received word MUST contain
  exactly 204 accepted symbols in occurrence order.
- **RS204-N-010 — Unique bounded-distance result.** For every received word at
  symbol distance at most eight from a codeword, the selected correction MUST
  be that unique codeword.
- **RS204-N-011 — Error-free identity.** A valid codeword with no errors MUST
  decode to its own 188 systematic data symbols.
- **RS204-N-012 — Full correction radius.** The guarantee in RS204-N-010 MUST
  include every error location set and every nonzero error magnitude from zero
  through eight erroneous symbols.
- **RS204-N-020 — Output event.** `Out_byte` has protocol meaning only when
  both `Valid_out` and `CEO` are high at a rising edge.
- **RS204-N-021 — Output count.** Every defined successful frame MUST produce
  exactly 188 output transfer events, neither fewer nor more.
- **RS204-N-022 — Output order.** Output event number `j`, for `j` from 0
  through 187, MUST carry corrected systematic symbol `Cword[j]`.
- **RS204-N-023 — Frame ordering.** Output groups MUST correspond to complete
  input groups in their acceptance order; symbols from different frames MUST
  NOT be interleaved within one 188-event group.
- **RS204-N-030 — Causality.** No output event for a frame may occur before all
  204 symbols of that frame have been accepted.
- **RS204-N-031 — Output cadence.** `CEO` is a one-clock symbol strobe during a
  valid output group, and successive output strobes in that group are separated
  by eight rising-edge intervals.
- **RS204-N-032 — Completion.** With a legal complete, correctable frame and
  reset held low, the decoder MUST eventually emit its complete 188-event
  output group.
- **RS204-N-040 — Reset cancellation.** While reset is asserted there are no
  input or output events; reset abandons partial framing and returns the output
  protocol to idle.

## 9. Expected verification properties

These are public verification objectives stated in natural language. They are
not hidden golden assertions, private bindings, private vectors, or an
evaluator-specific obligation map.

| Property ID | Class | Clause coverage | Expected public check |
| --- | --- | --- | --- |
| `RS204-P-REL-001` | algorithm/reference relation | RS204-N-001, RS204-N-010 through RS204-N-012 | Compare each correctable received word with an independent GF(2^8) bounded-distance RS(204,188) model, not with another implementation instance |
| `RS204-P-SAF-001` | safety/data | RS204-N-011, RS204-N-021, RS204-N-022 | An error-free codeword yields exactly its 188 systematic symbols with no extra transfer |
| `RS204-P-SAF-002` | safety/correction | RS204-N-010, RS204-N-012 | Every tested pattern of up to eight nonzero symbol errors is corrected to the unique codeword |
| `RS204-P-SAF-003` | safety/protocol | RS204-N-020, RS204-N-021, RS204-N-040 | `CEO` never creates a semantic transfer without `Valid_out`; reset and idle periods create no transfers |
| `RS204-P-TIM-001` | timing | RS204-N-002, RS204-N-003, RS204-N-030 | Input counting follows CE-qualified edges and output never precedes acceptance of symbol 204 |
| `RS204-P-TIM-002` | timing | RS204-N-031 | Output strobes are single-cycle events at the public eight-clock cadence |
| `RS204-P-ORD-001` | ordering | RS204-N-022, RS204-N-023 | Symbols and frames are emitted in the same ordinal order as the corrected systematic messages |
| `RS204-P-PRG-001` | progress | RS204-N-032 | A legal correctable frame eventually produces all 188 output events when reset is not reasserted |
| `RS204-P-ACT-001` | activation cover | RS204-N-003, RS204-N-011 | Reach reset release, 204 accepted symbols, first output, and final output for an error-free frame |
| `RS204-P-ACT-002` | activation cover | RS204-N-012 | Exercise at least one one-error frame and one eight-error frame, with errors in data and parity positions across the suite |
| `RS204-P-OBS-001` | observer cover | RS204-N-020 through RS204-N-023 | Observe at least two distinct `Out_byte` values, the first and last transfer of a group, and two ordered frame completions |

## 10. Optional and undefined behavior

- A received word farther than eight symbols from every codeword has no
  specified decoded data. The interface has no failure flag, so a checker must
  not infer success merely from output activity in that domain.
- A partial 204-symbol frame, a cadence violation, or input sampled during
  reset has no required result.
- `Out_byte` outside an output transfer event is unspecified and may retain a
  previous value or carry pipeline data.
- The contract does not expose corrected-error count, error locations,
  syndromes, locator/evaluator polynomials, or internal algorithm latency.
- Electrical X/Z values, metastability, clock glitches, and asynchronous-reset
  recovery/removal violations are outside scope.
- Inter-frame overlap is optional. If implemented, it must preserve
  RS204-N-023 and may not change the per-frame functional result.

## 11. Review record

- **Reviewer:** `codex`
- **Review date:** 2026-07-18
- **Reviewed scope:** public RS parameters, byte-symbol bounded-distance
  relation, shortened systematic ordering, top-level ports, CE/CEO event
  semantics, reset, legal assumptions, progress, and undefined uncorrectable
  behavior.
- **Compatibility evidence:** only the files and hashes in Section 1 were used
  to check interface names, widths, cadence feasibility, and storage shape.
- **Leakage review:** no implementation-variant semantics, defect metadata,
  private vector/oracle, evaluator artifact, or formal wrapper supplied a
  normative requirement.
- **Known interface limitation:** the public codeword contract is exactly 204
  CE-qualified symbols. The translated source contains internal 204/205
  priming and sentinel boundaries whose raw cycle alignment is not documented
  by a public timing manual available in this checkout. Before a cycle-exact
  proof, the suite must review the adapter that maps those internal cycles to
  the 204 public events. This uncertainty does not change the RS algorithm or
  permit a 205-symbol codeword.
- **Decision:** approved and frozen as public version `1.0.0`; the canonical
  content hash is recorded in `benchmark/synth/SPECIFICATIONS.sha256`.
