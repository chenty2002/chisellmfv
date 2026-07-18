# Public Protocol Specification: Legacy-Padded Keccak-512 Streaming Core

## 1. Status and authority

| Field | Value |
| --- | --- |
| Specification ID | `CHISELLMFV-SYNTH-SHA3-L-001` |
| Version | `1.0.0` |
| Review date | 2026-07-18 |
| Status | Reviewed authority snapshot |
| Reviewer | `codex` |
| Visibility | Public |
| Difficulty | L |
| Normative authority | This `spec.md` file plus FIPS 202 for the cited Keccak primitive and sponge definitions |
| Canonical content hash | Recorded after suite review in `benchmark/synth/SPECIFICATIONS.sha256`; it is not embedded in this self-referential document |

This document is the normative public contract for the family. Its exact text
is the reviewed authority snapshot. The family name is `sha3`, but the fixed
function below deliberately uses the legacy Keccak byte padding `0x01 ...
0x80`; it is therefore **not** the standardized FIPS 202 SHA3-512 function.

The sole external algorithm authority is:

| Algorithm source | Exact edition and identifier | Official location | SHA-256 at review |
| --- | --- | --- | --- |
| NIST, *SHA-3 Standard: Permutation-Based Hash and Extendable-Output Functions* | FIPS PUB 202, August 2015, DOI `10.6028/NIST.FIPS.202` | <https://nvlpubs.nist.gov/nistpubs/fips/nist.fips.202.pdf> | `1592607831ff0908cc590632ce371c6c95e94025bb1a0c8ae90a4d0ec1ed025e` |

FIPS 202 is authoritative here for `KECCAK-p[1600,24]` (also called
Keccak-f[1600]), state/bit ordering, `pad10*1`, and the sponge construction.
It is not cited to claim FIPS SHA3-512 conformance. The legacy domain/padding
choice is fixed by this public benchmark specification.

The following file was consulted only for family/interface compatibility and
realizability:

| Compatibility evidence | SHA-256 at review |
| --- | --- |
| `benchmark/synth/sha3/README.md` | `8e43833a004df4c8e2ae0524a9ade5faa2b7ac093b10f32b02d0c2a5b4c66a8b` |
| `benchmark/synth/sha3/src/main/scala/Sha3.scala` | `66469ad18d34a77cfdc70813e21a91b06002b8ee47d6a4c54befed7a13edb12b` |

The README records translation and workflow compatibility only. Its
implementation-variant material is outside this specification and supplied no
normative requirement. Implementation behavior cannot amend the golden
relation below. Any semantic change requires a new version, review, and
suite-level digest.

## 2. Public/evaluator boundary

This document exposes the complete message assembly, padding, permutation,
digest, and handshake contract. An evaluator may privately compile the
clauses into checkers, bind ports, choose messages, and use independent FIPS
202 permutation code. Those private checkers, bindings, test messages, traces,
expected verdicts, and diagnostic answer keys are not additional requirements.

No implementation variant, mutation description, private trigger, oracle,
formal wrapper, implementation difference, or evaluator result supplied any
normative requirement. Section 9 states public verification intent only; it is
not a set of hidden golden assertions or hidden signal bindings.

## 3. Fixed configuration

- Construction: Keccak sponge with a 1600-bit state.
- Permutation: `KECCAK-p[1600,24]`, the 24-round Keccak-f[1600] permutation
  defined by FIPS 202.
- Rate: 576 bits, or 72 bytes.
- Capacity: 1024 bits.
- Initial state: all 1600 bits zero.
- Digest length: 512 bits, or 64 bytes.
- Padding/domain profile: append byte `0x01`, append zero or more bytes, and
  set the most significant bit of the last byte of the 72-byte rate block.
  If the first and last padding byte coincide, that byte is `0x81`.
- Input beat width: 32 bits, with bytes presented most-significant byte first.
- Output: one digest per reset episode; there is no output acknowledgement.

For clarity, standardized FIPS 202 SHA3-512 uses a SHA-3 domain suffix before
`pad10*1`, conventionally represented by a first padding byte `0x06` in a byte
API. This benchmark instead uses the legacy first padding byte `0x01`. The two
functions must not be conflated even though they use the same rate, capacity,
permutation, and digest width.

## 4. Top-level interface

| Port | Direction | Width | Public meaning |
| --- | --- | ---: | --- |
| `clock` | input | 1 | Rising-edge state-update clock |
| `reset` | input | 1 | Active-high synchronous reset |
| `in` | input | 32 | Candidate message word; valid bytes begin at bit 31 |
| `in_ready` | input | 1 | Source-valid strobe; despite its name, this is an input from the source, not readiness returned by the core |
| `is_last` | input | 1 | Marks an accepted beat as the final beat of the message |
| `byte_num` | input | 2 | Number, 0 through 3, of valid most-significant bytes in an accepted final beat |
| `buffer_full` | output | 1 | Backpressure indication; no beat is accepted while high |
| `out` | output | 512 | Digest, meaningful when `out_ready` is high |
| `out_ready` | output | 1 | Sticky digest-valid indication, cleared only by reset |

The interface contains no `out_ready` input and no digest-consumed event.

## 5. Terms, events, byte order, and abstract golden model

An **input acceptance event** is a rising edge with `reset=0`, `in_ready=1`,
and `buffer_full=0`, before any final beat has already been accepted.

For a non-final accepted beat (`is_last=0`), the four message bytes are
appended in this exact order:

1. `in[31:24]`;
2. `in[23:16]`;
3. `in[15:8]`; and
4. `in[7:0]`.

For a final accepted beat (`is_last=1`), `byte_num` is the number of message
bytes appended from that same ordered list and is restricted to 0, 1, 2, or
3. The remaining low bytes of `in` do not belong to the message. A message
whose byte length is a multiple of four is terminated by accepting all of its
full words as non-final beats and then accepting a final beat with
`byte_num=0`. There is intentionally no encoding for four valid bytes in a
final beat.

Let `M` be the finite byte string assembled from all accepted beats. Define
`LegacyPad(M)` as `M`, followed by byte `0x01`, followed by the minimum number
of zero bytes, followed by byte `0x80`, such that the result length is a
multiple of 72 bytes. When only one padding byte is needed, the two set bits
share byte `0x81`.

Split `LegacyPad(M)` into 72-byte blocks. Starting from the all-zero 1600-bit
state, for each block in order:

1. map the block into the first 576 state bits using the FIPS 202 Keccak bit
   and lane convention;
2. exclusive-or those rate bits with the current state, leaving the 1024
   capacity bits unchanged; and
3. apply `KECCAK-p[1600,24]`.

After the final block, the first 512 rate bits form the digest. Because 512 is
less than the 576-bit rate, no additional squeeze permutation is needed.

At the external port, digest byte `j`, for `j` from 0 through 63, appears at
`out[511-8*j : 504-8*j]`. Thus `out[511:504]` is the first digest byte in the
FIPS byte presentation, and `out[7:0]` is the sixty-fourth.

## 6. Clock, reset, and initialization

All storage uses the rising edge of `clock`. Reset is active high and
synchronous: it takes effect when sampled high at a rising edge. That edge
establishes an empty message buffer, a zero 1600-bit sponge state, no accepted
final beat, `buffer_full=0`, `out_ready=0`, and an all-zero visible output
state.

No input acceptance event occurs on a reset edge. Before the first qualifying
reset edge, state and outputs are outside the contract. Reasserting reset
cancels a partial message and starts a new independent one-message episode.

Once asserted for a completed message, `out_ready` remains high and `out`
remains the corresponding digest until reset. The interface does not support
starting a second message without reset.

## 7. Legal environment and allowed assumptions

1. **Reset establishment.** `reset` is high for at least one rising edge before
   post-reset behavior is checked. This establishes the zero sponge state.
2. **Binary, stable inputs.** All inputs are binary and meet ordinary
   setup/hold requirements at relevant edges. Electrical X/Z behavior is
   outside the two-state protocol.
3. **Backpressure compliance.** The source asserts `in_ready` only when
   `buffer_full=0`. This prevents a beat from being presented while the
   576-bit rate buffer awaits permutation acceptance.
4. **Final-marker discipline.** `is_last` is asserted only on an input
   acceptance event. After a final beat is accepted, `in_ready` remains low
   until reset. This is necessary because the interface is one-message-per-reset.
5. **Final-byte encoding.** On a final beat, `byte_num` has its ordinary
   two-bit value 0 through 3 and the source places the valid tail bytes in the
   most-significant positions of `in`.
6. **Finite message.** A progress property assumes that a final beat is
   eventually supplied. The core cannot decide that an open-ended byte stream
   has ended without `is_last`.
7. **Progress interval.** After final acceptance, reset remains low until
   `out_ready` is observed. No scheduler fairness or output-consumer action is
   needed because all remaining work is internal.

`byte_num` on a non-final accepted beat is ignored and need not be constrained.
No assumption may replace the legacy padding with FIPS SHA3-512 padding or
constrain messages merely to values for which those two functions coincide.

## 8. Normative clauses

- **K512-N-001 — Function identity.** The golden function MUST be the
  576-bit-rate, 1024-bit-capacity, 512-bit-output legacy-padded Keccak sponge
  defined in Sections 3 and 5; it MUST NOT be labeled FIPS SHA3-512.
- **K512-N-002 — Permutation.** Every absorbed block MUST be transformed by
  exactly the FIPS 202 `KECCAK-p[1600,24]` permutation from the correct prior
  state.
- **K512-N-010 — Acceptance.** A word contributes bytes if and only if an
  input acceptance event occurs.
- **K512-N-011 — Word byte order.** Every accepted non-final word MUST append
  its four bytes from `in[31:24]` down to `in[7:0]`.
- **K512-N-012 — Final word.** An accepted final word MUST append exactly the
  first `byte_num` most-significant bytes, where `byte_num` is 0 through 3.
- **K512-N-013 — Single final marker.** Exactly one final beat terminates a
  legal message, and no later beat may be accepted before reset.
- **K512-N-020 — Legacy padding.** The accepted message MUST be padded by the
  exact `0x01`, zero-fill, final-`0x80` rule in Section 5, including the
  `0x81` coincidence case.
- **K512-N-021 — Block ordering.** Padded 72-byte blocks MUST be absorbed in
  message order, with each permutation result feeding the next block.
- **K512-N-022 — Digest extraction.** The digest MUST be the first 64 bytes of
  the final state in the external byte order defined in Section 5.
- **K512-N-030 — Backpressure.** While `buffer_full=1`, no input word may be
  consumed; already accepted bytes and their order MUST be preserved.
- **K512-N-031 — Causality.** `out_ready` MUST NOT identify a digest before a
  final beat and all resulting padding blocks have been absorbed and
  permuted.
- **K512-N-032 — Completion.** After a legal final beat, with reset low, the
  core MUST reach `out_ready=1` in finite time without further input.
- **K512-N-033 — Sticky result.** Once `out_ready=1`, both `out_ready` and the
  digest value MUST remain stable until reset.
- **K512-N-040 — Synchronous reset.** A rising edge with `reset=1` MUST
  establish the initialized state in Section 6 and cancel any partial message.

## 9. Expected verification properties

These natural-language objectives reference only public clauses. They are not
hidden golden assertions, signal bindings, secret vectors, or a private
clause-to-obligation map.

| Property ID | Class | Clause coverage | Expected public check |
| --- | --- | --- | --- |
| `K512-P-REL-001` | algorithm/reference relation | K512-N-001, K512-N-002, K512-N-020 through K512-N-022 | Compare `out` with an independent Keccak-f[1600] sponge model using legacy `0x01` padding; do not compare against FIPS SHA3-512 digests |
| `K512-P-SAF-001` | safety/message assembly | K512-N-010 through K512-N-013 | Accepted beats contribute exactly the declared bytes once, in order, and ignored beats contribute none |
| `K512-P-SAF-002` | safety/padding | K512-N-020 | Padding is correct for every tail length, including an empty message and the `0x81` rate-boundary case |
| `K512-P-SAF-003` | safety/state relation | K512-N-002, K512-N-021, K512-N-022 | Every complete absorption/permutation step and final digest extraction match the abstract model |
| `K512-P-TIM-001` | timing/handshake | K512-N-030, K512-N-031 | Backpressure prevents consumption, and digest validity does not precede final-block completion |
| `K512-P-TIM-002` | timing/result hold | K512-N-033, K512-N-040 | Digest-valid and digest data are sticky until a synchronous reset edge clears the episode |
| `K512-P-ORD-001` | ordering | K512-N-011, K512-N-012, K512-N-021, K512-N-022 | Word bytes, rate blocks, state updates, and digest bytes preserve their specified ordinal order |
| `K512-P-PRG-001` | progress | K512-N-032 | A legally terminated finite message eventually produces a digest without any output acknowledgement or fairness premise |
| `K512-P-ACT-001` | activation cover | K512-N-010 through K512-N-020 | Exercise final beats with `byte_num` 0, 1, 2, and 3, including the empty message |
| `K512-P-ACT-002` | activation cover | K512-N-020, K512-N-021, K512-N-030 | Exercise padding that fits in the current rate block, padding that creates a new block, and a multi-block message that raises `buffer_full` |
| `K512-P-OBS-001` | observer cover | K512-N-022, K512-N-033 | Observe `buffer_full` both low and high, observe `out_ready` rise, and observe the stable 64-byte digest presentation |

## 10. Optional and undefined behavior

- `in`, `is_last`, and `byte_num` while no input acceptance event occurs do
  not contribute to the message. An environment that asserts `is_last`
  without acceptance violates Section 7 and receives no specified result.
- The low, unused bytes of a final word are ignored. `byte_num` on a non-final
  accepted word is ignored.
- Input after an accepted final beat and a second message without reset are
  outside the protocol.
- `out` before `out_ready=1` has no digest meaning.
- There is no output backpressure, acknowledgement, replay, extendable output,
  keyed mode, salt, or configurable digest length.
- Four-state values, metastability, clock glitches, physical timing closure,
  and setup/hold violations are outside scope.
- FIPS SHA3-512, SHAKE, and other domain-separated Keccak functions are not
  optional modes of this fixed core.

## 11. Review record

- **Reviewer:** `codex`
- **Review date:** 2026-07-18
- **Reviewed scope:** FIPS 202 primitive/sponge definitions, the explicit
  legacy-padding distinction, rate/capacity/digest configuration, input and
  digest byte order, final-word encoding, backpressure, synchronous reset,
  one-message lifetime, progress, and undefined behavior.
- **Compatibility evidence:** the hashed Scala file in Section 1 was used only
  to audit public port names, widths, byte-lane compatibility, and feasible
  handshake behavior.
- **Leakage review:** no implementation-variant behavior, defect metadata,
  trigger, private oracle, evaluator artifact, or formal wrapper supplied a
  normative clause.
- **Semantic caveat resolved:** despite the directory name, this profile is
  legacy-padded Keccak-512 and is not FIPS SHA3-512. Verification reports must
  preserve that distinction.
- **Decision:** approved and frozen as public version `1.0.0`; the canonical
  content hash is recorded in `benchmark/synth/SPECIFICATIONS.sha256`.
