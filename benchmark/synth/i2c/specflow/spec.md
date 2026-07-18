# Public Protocol Specification: WISHBONE-Controlled I2C Controller

## Status and authority

| Field | Value |
| --- | --- |
| Specification ID | `CHISELLMFV-SYNTH-I2C-M-001` |
| Version | 1.0.0 |
| Review date | 2026-07-18 |
| Reviewer | `codex` |
| Visibility | Public |
| Difficulty | M |
| Status | Reviewed authority snapshot |
| Normative authority | This `spec.md` file |
| Canonical content hash | Recorded after review in `benchmark/synth/SPECIFICATIONS.sha256`; the suite ledger binds this exact snapshot without a self-referential field |

The authority layers are deliberately separate:

1. NXP UM10204 defines the external I2C wire protocol where a clause is marked
   **External I2C**.
2. WISHBONE B4 defines the host-bus vocabulary and legal standard-mode
   handshake where a clause is marked **External WISHBONE**.
3. This document defines the benchmark-specific register map, cycle timing,
   reset behavior, command engine, and implementation-compatible interface.

| External source | Version and official location | Integrity |
| --- | --- | --- |
| NXP, *I2C-bus specification and user manual*, UM10204 | Rev. 7.0, 1 October 2021, <https://www.nxp.com/docs/en/user-guide/UM10204.pdf> | SHA-256 `dc91f00f65584e06ef36e26c93bf9d91a95fb3c8a1830a9223e53caf678b36af` |
| OpenCores, *WISHBONE System-on-Chip Interconnection Architecture for Portable IP Cores* | Revision B.4, 2010, <https://cdn.opencores.org/downloads/wbspec_b4.pdf> | Version and official URL recorded; this specification snapshot fixes the benchmark's interpretation |

The following compatibility inputs were inspected only to confirm port naming,
widths, synchronous realizability, and the feasibility of this public contract.
They are not normative authorities.

| Compatibility source | SHA-256 at review |
| --- | --- |
| `benchmark/synth/i2c/README.md` | `3ba9cf02791365bfe57d0e06b3150bbf5fe89b2edf20bc36f234e8540ea7bd39` |
| `benchmark/synth/i2c/src/main/scala/I2CMaster.scala` | `c29a8911a122a5f70f3ae44d0288823b5bfdd4e3c03ae0bcf0ef4c7fa4b216f1` |

## Public/evaluator boundary

This document contains the complete public abstract golden model. An evaluator
may encode it in private monitors and may choose private legal transactions,
but evaluator-only material cannot add, remove, or reinterpret requirements.
The expected properties are natural-language verification objectives, not
hidden golden assertions or signal bindings.

## Configuration

The fixed host profile is an eight-bit, standard-mode, single-transfer
WISHBONE slave with a three-bit byte-register address. It has `ACK` termination
and no `ERR`, `RTY`, `STALL`, byte-select, or burst ports.

### Register map

Reads are selected by `wb_adr_i` and sampled into `wb_dat_o` on every rising
edge, whether or not a bus cycle is active.

| Address | Read value | Accepted write effect |
| ---: | --- | --- |
| 0 | Prescaler bits 7:0 | Replace prescaler bits 7:0 |
| 1 | Prescaler bits 15:8 | Replace prescaler bits 15:8 |
| 2 | Control register `CTR` | Replace `CTR` |
| 3 | Receive register `RXR` | Replace transmit register `TXR` |
| 4 | Status register `SR` | Replace command register `CR` only when `CTR.EN=1` |
| 5 | `TXR` | No effect |
| 6 | `CR` | No effect |
| 7 | Zero | No effect |

`CTR[7]` is core enable (`EN`) and `CTR[6]` is interrupt enable (`IEN`); the
remaining control bits have no specified function. The command fields are
`CR[7]=STA`, `CR[6]=STO`, `CR[5]=RD`, `CR[4]=WR`, `CR[3]=ACK`, and
`CR[0]=IACK`. Bits 2 and 1 have no command function.

The status fields are `SR[7]=RXACK`, `SR[6]=BUSY`, `SR[5]=AL`, zeros in bits
4:2, `SR[1]=TIP`, and `SR[0]=IF`.

## Top-level interface

| Port | Direction | Width | Meaning |
| --- | --- | ---: | --- |
| `clock` | input | 1 | Common rising-edge clock |
| `reset` | input | 1 | Implicit implementation port; no functional state in this profile is reset by it |
| `wb_rst_i` | input | 1 | Active-high synchronous functional reset |
| `arst_i` | input | 1 | Active-low synchronous functional reset despite the historical port name |
| `wb_adr_i` | input | 3 | WISHBONE byte-register address |
| `wb_dat_i` | input | 8 | WISHBONE write data |
| `wb_dat_o` | output | 8 | Registered WISHBONE read data |
| `wb_we_i` | input | 1 | Write when high, read when low |
| `wb_stb_i` | input | 1 | Slave-selection strobe |
| `wb_cyc_i` | input | 1 | Bus-cycle qualifier |
| `wb_ack_o` | output | 1 | Normal cycle termination |
| `wb_inta_o` | output | 1 | Registered interrupt request |
| `scl_pad_i` | input | 1 | Resolved external SCL level |
| `scl_pad_o` | output | 1 | SCL output data, fixed low |
| `scl_padoen_o` | output | 1 | SCL release control: one releases, zero requests a low drive |
| `sda_pad_i` | input | 1 | Resolved external SDA level |
| `sda_pad_o` | output | 1 | SDA output data, fixed low |
| `sda_padoen_o` | output | 1 | SDA release control: one releases, zero requests a low drive |

The split pad interface represents open-drain wiring; it is not a push-pull
data/output-enable pair.

## Terms, events, and abstract golden model

### Host-side events

A **request phase** exists while both `wb_cyc_i` and `wb_stb_i` are high. After
each rising edge, the next `wb_ack_o` value is the request-phase value sampled
at that edge AND the inverse of the pre-edge `wb_ack_o`. Under the legal master
behavior below, this creates one registered acknowledgement pulse.

An **accepted transfer edge** is a rising edge at which the pre-edge
`wb_ack_o` is high and the master still presents stable address, direction, and
write data. An accepted write updates the register map at that edge. A read
value is independently sampled from the addressed register on every rising
edge, so a stable address makes `wb_dat_o` valid throughout the acknowledgement
phase.

### Command-side events

An **admitted command** is an accepted write to address 4 while `CTR.EN=1`.
The command engine uses the following abstract ordering:

1. A legal transmit command sends the `TXR` byte most-significant bit first.
   `STA` requests a START before the byte; `STO` requests a STOP after its
   acknowledge bit.
2. A legal receive command samples eight bits most-significant bit first into
   `RXR`. On the ninth clock, `CR.ACK=0` drives ACK low and `CR.ACK=1` releases
   SDA for NACK. Optional START and STOP surround the byte as above.
3. A legal stop-only command performs STOP without a data byte.
4. At idle, command selection priority is START, READ, WRITE, then STOP. A
   command that includes START proceeds to READ when `RD=1`, otherwise to
   WRITE. Legal software uses `RD` or `WR` with START and does not rely on
   contradictory command combinations.
5. Command completion occurs after the acknowledge phase, or after STOP when
   STOP was requested. Completion clears command bits 7:4 and sets the
   interrupt flag.

The engine leaves idle only when at least one of `RD`, `WR`, or `STO` is set.
`STA` by itself remains represented in `CR` but emits no START condition.

At the bit level, the prescaler is a 16-bit reload value. In the absence of
clock stretching, internal bit phases advance on enable pulses separated by
the prescaler countdown. When the controller releases SCL but the filtered SCL
input remains low, the phase engine waits rather than treating the line as
high.

The SCL and SDA inputs pass through two synchronization samples and a
three-sample majority filter. Filtered SDA transitions while filtered SCL is
high define the internal START/STOP observations used by `BUSY` and arbitration
logic.

### Status and interrupt model

- `RXACK` records the resolved SDA value sampled during the receiver's
  acknowledge bit; zero means ACK and one means NACK.
- `BUSY` becomes set after a filtered START observation and clears after a
  filtered STOP observation.
- `AL` latches arbitration loss. It clears when a new START command is active
  and no new loss is observed.
- On each non-reset rising edge, `TIP` takes the pre-edge value of
  `CR.RD OR CR.WR`; it is a registered observation rather than a combinational
  decode of a newly accepted command.
- At each non-reset rising edge, next `IF` is zero when pre-edge `IACK=1`.
  Otherwise it is one when completion, arbitration loss, or pre-edge `IF` is
  one, and zero when all three are zero. Thus `IACK` has clear priority if an
  event coincides with it.
- At each non-reset rising edge, `wb_inta_o` takes the pre-edge value of
  `IF AND CTR.IEN`; it is therefore a registered interrupt indication.

## Clock, reset, and initialization

All functional resets in this profile are synchronous to `clock`. At a rising
edge with `arst_i=0`, or with `arst_i=1` and `wb_rst_i=1`, the prescaler becomes
`FFFF`, `CTR`, `TXR`, `CR`, status/event state, interrupt output, byte engine,
and bit engine return to their inactive values, and both pad controls release
their lines. The active-low reset has priority, although the public reset
values are the same for both sources.

`wb_ack_o` and the read-data sampling register are not directly reset. The
acknowledgement recurrence remains active during functional reset. With the
legal reset environment (`wb_cyc_i=wb_stb_i=0`), the first reset edge makes
`wb_ack_o=0` and initializes the functional registers. At that same edge the
clocked read mux still samples their pre-edge values, so a known address alone
does not make an arbitrary register read known. Address 7 samples constant
zero in one edge; for addresses 0 through 6, holding functional reset active
and the address stable across a second rising edge makes `wb_dat_o` sample the
already-initialized value. The implicit `reset` port has no functional effect.
Power-on values before a functional reset edge are not specified.

## Legal environment and allowed assumptions

1. **WISHBONE rationale:** A master asserts `wb_stb_i` only while
   `wb_cyc_i=1`, holds address, direction, and write data stable through the
   accepted transfer edge, and negates its request after observing
   `wb_ack_o=1`. This is the standard-mode handshake expected by WISHBONE B4
   and prevents one request from being counted repeatedly.
2. During either functional reset, the master holds `wb_cyc_i=wb_stb_i=0`.
   To require deterministic `wb_dat_o` at address 0 through 6, it also holds a
   known address and the reset active across two rising edges. A one-edge check
   is permitted only at address 7, whose sampled value is constant zero. This
   makes the intentionally unreset host observation registers deterministic
   without pretending that they have reset assignments.
3. A functional reset is sampled in its active state for at least one rising
   edge. For the WISHBONE reset specifically, B4 requires at least one complete
   clock cycle.
4. The external pad model obeys wired-AND consistency: when this controller
   requests a low drive, the corresponding resolved pad input is low; when it
   releases a line, another device may hold that line low or the pull-up may
   make it high.
5. Legal command software enables the core before writing `CR`, keeps it
   enabled until completion, uses either READ or WRITE for a data command, and
   does not overwrite command/data/prescaler state while a command is active.
   These restrictions separate a transaction from deliberate reprogramming.
6. A bounded-progress property may assume no reset, no arbitration loss, a
   finite prescaler, and eventual release of externally stretched SCL. I2C
   explicitly allows a target to hold SCL low, so unconditional completion is
   not a valid requirement.
7. Inputs are binary and satisfy synchronous timing. Analog rise/fall times,
   voltage thresholds, and four-state behavior are outside this digital model.

## Normative clauses

- **I2C-001 — External WISHBONE selection.** `wb_ack_o` MUST only be generated
  in response to `wb_cyc_i AND wb_stb_i`, consistent with WISHBONE B4 standard
  mode. The benchmark's exact registered recurrence MUST be the one stated in
  Host-side events.
- **I2C-002 — WISHBONE transfer stability.** A transfer MUST use address,
  direction, and data held through its accepted transfer edge. `wb_dat_o` MUST
  contain the register-map value sampled for the stable address.
- **I2C-003 — Register map.** Reads and accepted writes MUST implement exactly
  the addresses and effects in Configuration. Writes to `CR` MUST be ignored
  when `CTR.EN=0`.
- **I2C-004 — Functional reset.** The two functional reset polarities and
  synchronous semantics MUST match Clock, reset, and initialization. The
  implicit `reset` input MUST NOT change functional state.
- **I2C-005 — Reset defaults.** A qualifying functional reset edge MUST set the
  prescaler to `FFFF`, clear `CTR`, `TXR`, `CR`, status/event state and
  interrupt state, idle both engines, and release both serial lines.
- **I2C-006 — Command admission.** Only an accepted address-4 write with
  `CTR.EN=1` MUST load a new command. Idle selection and START-follow-on
  ordering, including the no-action `STA`-only case, MUST follow the priority
  stated in Command-side events.
- **I2C-007 — External I2C line discipline.** `scl_pad_o` and `sda_pad_o` MUST
  remain low. A pad-enable value of zero MUST mean pull low and one MUST mean
  release, implementing the open-drain/wired-AND behavior required by NXP
  UM10204.
- **I2C-008 — External I2C START/STOP.** A START MUST be a high-to-low SDA
  transition while resolved SCL is high; a STOP MUST be a low-to-high SDA
  transition while resolved SCL is high. The bus-observation state MUST treat
  START as busy and STOP as free after its documented synchronization/filter
  latency.
- **I2C-009 — External I2C data validity.** Outside START and STOP, transmitted
  SDA MUST remain stable while resolved SCL is high and may change during SCL
  low.
- **I2C-010 — Byte and acknowledge order.** Every data byte MUST contain eight
  bits sent or sampled most-significant bit first, followed by a ninth
  acknowledge clock. Write commands MUST sample receiver ACK/NACK; read
  commands MUST drive or release the final acknowledge according to `CR.ACK`.
- **I2C-011 — Clock stretching.** If SCL remains low after the controller
  releases it, the affected serial phase MUST wait; it MUST resume after the
  filtered SCL input is released high.
- **I2C-012 — Arbitration loss.** While checking a transmitted released-high
  SDA bit, observing filtered SDA low MUST report arbitration loss and abort
  the active byte operation. An unexpected STOP during an active non-STOP bit
  operation MUST also report loss under this benchmark contract.
- **I2C-013 — Status encoding.** `SR` MUST use the fixed bit layout and meanings
  in Configuration, including zeros in bits 4:2.
- **I2C-014 — Command retirement.** Completion or arbitration loss MUST clear
  `CR[7:4]`; `CR.ACK` MUST be retained; and the transient low command bits MUST
  clear when no accepted command write occurs.
- **I2C-015 — Interrupt behavior.** Completion or arbitration loss MUST latch
  `IF` when pre-edge `IACK=0`. Pre-edge `IACK=1` MUST make next `IF=0` even if
  completion or loss is present; otherwise old `IF`, completion, and loss MUST
  be ORed to form next `IF`. `wb_inta_o` MUST be the registered `IF AND IEN`
  indication.
- **I2C-016 — Receive and transmit storage.** An admitted write command MUST
  source its eight data bits from the loaded `TXR`. An admitted read command
  MUST assemble the eight sampled bits into `RXR` in transmitted order.

## Expected verification properties

| Property ID | Class | Expected property |
| --- | --- | --- |
| `I2C-P001` | Safety | No acknowledgement is asserted without both WISHBONE qualifiers; a legal held request produces the exact registered single-pulse recurrence (I2C-001). |
| `I2C-P002` | Timing | Stable-address reads and accepted writes occur at the documented rising-edge boundaries and implement the complete register map (I2C-002, I2C-003). |
| `I2C-P003` | Safety | Either functional reset produces the specified functional defaults while leaving the implicit reset inert; arbitrary-address read-data determinism is required only after the documented second reset edge (I2C-004, I2C-005). |
| `I2C-P004` | Ordering | START precedes a requested byte, eight MSB-first bits precede the acknowledge phase, and requested STOP follows that phase (I2C-006, I2C-008 through I2C-010). |
| `I2C-P005` | Safety | The controller never drives a serial high level and never changes transmitted SDA during a resolved SCL-high data interval (I2C-007, I2C-009). |
| `I2C-P006` | Timing | Serial progress pauses while released SCL is externally low and resumes only after the filtered line is high (I2C-011). |
| `I2C-P007` | Safety | A released-high/transmitted-high mismatch is reported as arbitration loss, retires the command, and reaches status/interrupt state (I2C-012 through I2C-015). |
| `I2C-P008` | Progress | Under the bounded-progress assumptions, every admitted read, write, or stop-only command eventually retires and clears active command bits. If pre-edge `IACK=0` at retirement, completion also makes next `IF=1`; with `IACK=1`, the defined clear priority applies (I2C-006, I2C-010, I2C-014, I2C-015). |
| `I2C-P009` | Ordering | Transmit bytes equal `TXR` MSB-first; receive bytes reconstruct `RXR` in the same bit order and preserve the sampled ACK/NACK result (I2C-010, I2C-016). |
| `I2C-C001` | Activation cover | Exercise every readable address, every writable address, a disabled-core command write, both functional reset inputs, and the two-edge read-data initialization boundary (I2C-003 through I2C-005). |
| `I2C-C002` | Activation cover | Exercise transmit, receive, stop-only, START-plus-transfer, transfer-plus-STOP, ACK, NACK, clock stretching, and arbitration-loss paths (I2C-006 through I2C-012). |
| `I2C-C003` | Observer cover | Observe `BUSY`, `TIP`, `RXACK`, and `AL` through their public causes; separately observe an event setting `IF` with `IACK=0`, an `IACK` edge clearing `IF`, and the corresponding assertion and clearing of `wb_inta_o` when enabled (I2C-013 through I2C-015). |
| `I2C-C004` | Observer cover | Observe legal START and STOP transitions and all four combinations of controller release/low requests across SCL and SDA, subject to wired-AND consistency (I2C-007, I2C-008). |

## Optional and undefined behavior

- Power-on behavior before a functional reset edge is undefined. `wb_ack_o`
  and `wb_dat_o` become known through the legal reset environment, not through
  invented reset assignments.
- Contradictory command combinations, command-register overwrite while active,
  and disabling/reprogramming timing while active are outside the legal
  transaction profile. The explicit idle priority still defines ordinary
  binary selection, but software must not use it as a substitute for the legal
  command forms.
- The core transmits raw software-provided bytes. It does not autonomously
  enforce 7-bit versus 10-bit addressing or interpret an address byte.
- Absolute I2C electrical-mode timing, pull-up sizing, capacitance, voltage
  thresholds, and analog spike widths are not claimed by this RTL protocol.
- The host interface provides no error, retry, stall, burst, or byte-select
  response.
- `X`/`Z`, metastability, and clock/reset timing violations are undefined.

## Review record

Reviewer `codex` checked the public top/interface profile against the two
compatibility files at the recorded hashes and checked external wire and host
terminology against NXP UM10204 Rev. 7.0 and WISHBONE B4 at the official URLs
above. Clause and property identifiers were reviewed for family-local
uniqueness. Two deliberate semantic cautions are public: `arst_i` is sampled
synchronously despite its name, and completion is conditional on eventual SCL
release because legal I2C clock stretching has no intrinsic finite bound.
