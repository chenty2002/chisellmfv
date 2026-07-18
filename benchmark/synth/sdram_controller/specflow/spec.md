# Public Protocol Specification: Fixed-Profile SDRAM Controller

## Status and authority

| Field | Value |
| --- | --- |
| Specification ID | `CHISELLMFV-SYNTH-SDRAM-M-001` |
| Version | 1.0.0 |
| Review date | 2026-07-18 |
| Reviewer | `codex` |
| Visibility | Public |
| Difficulty | M |
| Status | Reviewed authority snapshot |
| Normative authority | This `spec.md` file |
| Canonical content hash | Recorded after review in `benchmark/synth/SPECIFICATIONS.sha256`; the suite ledger binds this exact snapshot without a self-referential field |

This specification is the normative benchmark contract. The files below were
inspected only to confirm port names, widths, synchronous realizability, and
contract compatibility; neither file is a normative authority.

| Compatibility source | SHA-256 at review |
| --- | --- |
| `benchmark/synth/sdram_controller/README.md` | `13cc1e9efa66e56a9bc73b7fede5ffa84d6a00ae3b1244ef42054cd22831e4a6` |
| `benchmark/synth/sdram_controller/src/main/scala/SdramController.scala` | `14afd64b7af0b160db360fe215c1d0bb5800cb13ebdd10daea7292d30136bbf5` |

No external SDRAM/JEDEC conformance is claimed. Symbolic command names in this
document describe the fixed benchmark pin patterns and sequencing only.

## Public/evaluator boundary

This public document completely defines the abstract golden model. An
evaluator may translate it into private monitors and choose private legal
transactions, but evaluator-only material cannot add obligations or alter the
meaning of a public clause. The expected properties below are natural-language
verification objectives, not hidden golden assertions or bindings.

## Configuration

The fixed profile has:

- a 24-bit host address split into bank `[23:22]`, row `[21:9]`, and column
  `[8:0]`;
- 16-bit write and read data;
- a 13-bit SDRAM address and two-bit bank address;
- a ten-bit modulo-1024 refresh counter with service threshold 519;
- a five-bit controller state and four-bit dwell counter;
- automatic-precharge address bit A10 set for host read/write column commands;
- identical one-bit low/high data-mask outputs.

The observable command-pin patterns are fixed. Columns list
`clock_enable, cs_n, ras_n, cas_n, we_n` in that order.

| Symbolic command | Pin pattern |
| --- | --- |
| `NOP` | `1,0,1,1,1` |
| `PALL` | `1,0,0,1,0` |
| `REFRESH` | `1,0,0,0,1` |
| `MODE` | `1,0,0,0,0` |
| `BANK-ACTIVATE` | `1,0,0,1,1` |
| `READ` | `1,0,1,0,1` |
| `WRITE` | `1,0,1,0,0` |

## Top-level interface

| Port | Direction | Width | Meaning |
| --- | --- | ---: | --- |
| `clock` | input | 1 | Rising-edge state-update clock |
| `reset` | input | 1 | Implicit implementation port; functionally unused |
| `rst_n` | input | 1 | Active-low synchronous functional reset |
| `wr_addr` | input | 24 | Host write address |
| `wr_data` | input | 16 | Host write data |
| `wr_enable` | input | 1 | Host write request level |
| `rd_addr` | input | 24 | Host read address |
| `rd_data` | output | 16 | Registered host read data |
| `rd_ready` | output | 1 | One-cycle read-result indication |
| `rd_enable` | input | 1 | Host read request level |
| `busy` | output | 1 | Registered, one-edge-delayed read/write-state observer |
| `addr` | output | 13 | SDRAM address pins |
| `bank_addr` | output | 2 | SDRAM bank-address pins |
| `data_out` | output | 16 | Write-data pins |
| `data_in` | input | 16 | Read-data pins |
| `data_oe` | output | 1 | Write-data drive enable |
| `clock_enable` | output | 1 | Command bit 7 |
| `cs_n` | output | 1 | Active-low chip select |
| `ras_n` | output | 1 | Active-low row command |
| `cas_n` | output | 1 | Active-low column command |
| `we_n` | output | 1 | Active-low write command |
| `data_mask_low` | output | 1 | Low-byte mask |
| `data_mask_high` | output | 1 | High-byte mask |

## Terms, events, and abstract golden model

A **dwell state** has a four-bit counter. When that counter is nonzero at a
rising edge, the state and command hold and the counter decrements by one. When
it is zero, the state machine takes its listed transition and loads the listed
new dwell value, or zero when none is listed.

An **admission edge** is a non-reset rising edge whose pre-edge abstract state
is `IDLE`. At such an edge, refresh has first priority when the refresh counter
is at least 519, read has second priority when `rd_enable=1`, and write has
third priority when `wr_enable=1`.

### Initialization sequence

A reset edge establishes `INIT-NOP` with command `NOP` and dwell 15. After
reset release, the abstract sequence is:

`INIT-NOP` → `INIT-PRE/PALL` → `INIT-NOP-A/NOP` →
`INIT-REF-A/REFRESH` → `INIT-NOP-B/NOP` with dwell 7 →
`INIT-REF-B/REFRESH` → `INIT-NOP-C/NOP` with dwell 7 →
`INIT-MODE/MODE` → `INIT-WAIT/NOP` with dwell 1 → `IDLE/NOP`.

Counting the dwell rules, `IDLE` is reached on the 39th consecutive non-reset
rising edge after the last reset edge. There is no separate initialization
complete output.

### Refresh sequence

At an eligible `IDLE` edge with refresh counter at least 519, the sequence is:

`REF-PRE/PALL` → `REF-NOP/NOP` → `REF-CMD/REFRESH` →
`REF-WAIT/NOP` with dwell 7 → `IDLE/NOP`.

The refresh counter resets to zero at every rising edge whose pre-edge state is
`REF-WAIT`; it increments modulo 1024 on other non-reset edges.

### Read sequence

An admitted read captures `rd_addr` and enters:

`READ-ACT/BANK-ACTIVATE` → `READ-NOP-A/NOP` with dwell 1 →
`READ-CAS/READ` → `READ-NOP-B/NOP` with dwell 1 →
`READ-CAPTURE/NOP` → `IDLE/NOP`.

At the edge leaving `READ-CAPTURE`, `data_in` is captured into `rd_data` and
`rd_ready` becomes one. On the following non-reset edge, `rd_ready` returns to
zero unless another impossible overlapping capture were present. With the
legal environment, the pulse is exactly one cycle and occurs seven rising
edges after the admission edge.

### Write sequence

An admitted write captures `wr_addr` and `wr_data` and enters:

`WRITE-ACT/BANK-ACTIVATE` → `WRITE-NOP/NOP` with dwell 1 →
`WRITE-CAS/WRITE` → `WRITE-WAIT/NOP` with dwell 1 → `IDLE/NOP`.

`data_oe` is high only in `WRITE-CAS`; `data_out` continuously presents the
captured write data.

### Address and observer model

During `READ-ACT` or `WRITE-ACT`, `bank_addr` is captured address bits 23:22
and `addr` is bits 21:9. During `READ-CAS` or `WRITE-CAS`, the bank is unchanged
and `addr` is `00`, then A10=`1`, A9=`0`, then captured column bits 8:0. During
`INIT-MODE`, `addr` is the fixed 13-bit value `0001000110000`. Other states in
the read/write family drive both `bank_addr` and `addr` to zero. Outside that
family and outside `INIT-MODE`, `bank_addr` is the command's low bank field and
`addr` is zero except that A10 takes the command's low address field. No
unknown command field is exposed through the top-level address outputs.

Both data masks are high outside the read/write state family and low throughout
that family. On every non-reset edge, `busy` takes the most-significant bit of
the pre-edge state. Consequently it goes high one edge after entry into a read
or write family and stays high for one edge after return to `IDLE`. It is not
an initialization-complete or refresh-busy signal.

Outside reset, host address capture occurs whenever a request is high, with
read taking priority over write, even if the state machine is not in `IDLE`.
The legal host protocol avoids such mid-operation recapture.

## Clock, reset, and initialization

`rst_n` is sampled synchronously. A rising edge with `rst_n=0` sets state to
`INIT-NOP`, command to `NOP`, dwell to 15, captured address and write/read data
to zero, `busy` to zero, and refresh counter to zero. The implicit `reset` port
does not affect functional state.

`rd_ready` has no reset assignment: every reset edge retains its pre-edge
value. Consequently its value during an initial reset and until the first
rising edge with `rst_n=1` can remain unknown. That first released edge is not
`READ-CAPTURE`, so it establishes `rd_ready=0`. All other listed state objects
have the explicit synchronous behavior above; no power-on values are assumed.

## Legal environment and allowed assumptions

1. `rst_n` is held low across at least one rising edge. Post-initialization host
   transactions begin only after the 39-edge initialization sequence reaches
   abstract `IDLE`. This is necessary because no external pin reports
   initialization completion and `busy` does not cover initialization.
2. A host request is asserted for one admission edge only. The host presents
   no further request until the current abstract read/write sequence has
   returned to `IDLE`. Address and write data remain stable through admission.
3. `data_in` is binary and stable at the edge leaving `READ-CAPTURE`, when the
   controller samples it. This is the digital memory-response contract.
4. Reset, request, address, and data inputs are binary and meet setup/hold
   requirements. Four-state and analog behavior are outside this contract.
5. Bounded-progress properties may assume `rst_n` remains high for the measured
   interval. Reasserted reset legitimately restarts initialization.
6. Simultaneous read and write at an otherwise legal admission edge may be
   exercised; read priority is defined and the write request is not admitted.
7. No external SDRAM timing or power-up rule may be imported as an assumption.
   The benchmark's complete digital timing is the state/dwell model above.

## Normative clauses

- **SDR-001 — Functional reset.** A rising edge with `rst_n=0` MUST establish
  every reset value listed in Clock, reset, and initialization, except that
  `rd_ready` MUST retain its pre-edge value rather than receive a reset value.
  The implicit `reset` port MUST be functionally inert.
- **SDR-002 — Initialization.** After reset release, the controller MUST follow
  the initialization command/state sequence and dwell counts exactly, reaching
  `IDLE` on the 39th uninterrupted non-reset edge.
- **SDR-003 — Dwell semantics.** A nonzero dwell counter MUST hold state and
  command while decrementing. State transition and dwell reload MUST occur
  only when the pre-edge counter is zero.
- **SDR-004 — Idle priority.** At `IDLE`, refresh threshold MUST take priority
  over read, and read MUST take priority over write.
- **SDR-005 — Host capture.** Outside reset, a high `rd_enable` MUST capture
  `rd_addr`; otherwise a high `wr_enable` MUST capture `wr_addr`. A high
  `wr_enable` MUST capture `wr_data`. Legal admission into a sequence MUST use
  the captured values from that edge.
- **SDR-006 — Read sequence.** An admitted read MUST follow the complete read
  sequence, command order, and dwell timing in the abstract model.
- **SDR-007 — Read result.** `data_in` MUST be captured at the edge leaving
  `READ-CAPTURE`; `rd_ready` MUST be one for the resulting cycle and MUST clear
  on the next ordinary edge.
- **SDR-008 — Write sequence.** An admitted write MUST follow the complete
  write sequence, command order, and dwell timing in the abstract model.
- **SDR-009 — Refresh.** Threshold detection, refresh priority, command
  sequence, dwell 7, modulo increment, and reset-in-`REF-WAIT` behavior MUST
  match the Refresh sequence.
- **SDR-010 — Address mapping.** Bank, row, column, A10/A9, and mode-register
  address outputs MUST match Address and observer model in their named states.
- **SDR-011 — Command pins.** The five command-related outputs MUST match the
  symbolic command pattern active in the abstract state.
- **SDR-012 — Data path.** `data_out` MUST present captured `wr_data`, and
  `data_oe` MUST be high exactly in `WRITE-CAS`.
- **SDR-013 — Masks.** Both data masks MUST be equal; they MUST be low in every
  read/write-family state and high in all other states.
- **SDR-014 — Busy observer.** On each non-reset edge, `busy` MUST become the
  pre-edge state's read/write-family bit, including the one-edge assertion and
  deassertion lag. It MUST remain zero throughout reset.
- **SDR-015 — No fabricated readiness.** `busy` MUST NOT be interpreted as
  initialization or refresh status, and `rd_ready` MUST NOT be required to have
  a reset value.

## Expected verification properties

| Property ID | Class | Expected property |
| --- | --- | --- |
| `SDR-P001` | Safety | Functional reset establishes all specified values, preserves the pre-edge `rd_ready` value, and ignores implicit `reset` (SDR-001). |
| `SDR-P002` | Ordering | Initialization emits exactly the public command sequence with both dwell-7 intervals, the initial dwell-15 interval, and final dwell-1 interval (SDR-002, SDR-003). |
| `SDR-P003` | Timing | The first post-reset `IDLE` occurs on released edge 39, not earlier or later (SDR-002). |
| `SDR-P004` | Ordering | Refresh wins over simultaneous host work and read wins over simultaneous write at `IDLE` (SDR-004). |
| `SDR-P005` | Safety | Read/write bank, row, column, automatic-precharge bit, command pins, masks, and drive enable match their active phases (SDR-006, SDR-008, SDR-010 through SDR-013). |
| `SDR-P006` | Timing | A legal read result is sampled only at the specified capture edge and raises exactly one `rd_ready` cycle seven edges after admission (SDR-006, SDR-007). |
| `SDR-P007` | Timing | `busy` shows the exact one-edge lag around read/write families and does not claim initialization/refresh occupancy (SDR-014, SDR-015). |
| `SDR-P008` | Progress | With reset absent, every admitted read, write, and threshold refresh returns to `IDLE` after its finite listed sequence (SDR-003, SDR-006, SDR-008, SDR-009). |
| `SDR-P009` | Safety | Refresh counter increments modulo 1024 except for its specified reset conditions and cannot lose refresh priority once observed at threshold in `IDLE` (SDR-004, SDR-009). |
| `SDR-C001` | Activation cover | Exercise the full initialization and refresh sequences, including every dwell countdown boundary (SDR-002, SDR-003, SDR-009). |
| `SDR-C002` | Activation cover | Exercise read, write, simultaneous read/write priority, and refresh-versus-request priority (SDR-004, SDR-006, SDR-008). |
| `SDR-C003` | Observer cover | Observe every symbolic command pattern, row and column addresses, write drive, read-ready pulse, both busy transitions, and both mask levels (SDR-007, SDR-010 through SDR-014). |

## Optional and undefined behavior

- Power-on behavior before a qualifying `rst_n=0` edge is undefined.
- The initial value of `rd_ready` during reset and before the first released
  rising edge is intentionally undefined, but reset-edge retention is defined;
  SDR-001 and SDR-015 prohibit inventing a reset value for it.
- Requests outside abstract `IDLE` can recapture host storage but are outside
  the legal transaction protocol. No completion guarantee applies to them.
- There is no separate write-complete pulse, initialization-complete output,
  refresh-busy output, request-ready input, or response backpressure.
- Physical SDRAM electrical timing, retention time, clock frequency, and
  external-device conformance are not specified.
- `X`/`Z`, metastability, clock glitches, and timing violations are undefined.

## Review record

Reviewer `codex` checked the public top/interface profile against the two
compatibility files at the recorded hashes, reconstructed all state/dwell,
priority, address, data, and observer rules, and checked family-local clause
and property identifiers for uniqueness. The key public semantic cautions are
that `busy` is a delayed read/write observer rather than general readiness and
that `rd_ready` is intentionally not reset.
