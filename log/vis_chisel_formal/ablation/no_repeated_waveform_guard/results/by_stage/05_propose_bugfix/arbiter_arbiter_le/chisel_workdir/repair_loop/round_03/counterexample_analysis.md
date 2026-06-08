# Counterexample Analysis: `reqA_eventually_gets_ackA_within_30_cycles`

## 1. Verification Environment

### Top Module
- **Module**: `ArbiterLE` (from `arbiter_le.scala`)
- **Structure**: Three `Controller` modules (A, B, C) connected to a round-robin `Arbiter`, three `Client` modules that generate requests via LFSR, and one `Observer` for verification
- **Key Connections**:
  - `arbiter.io.sel` → `controllerX.io.sel` (selection bus for all controllers)
  - `controllerX.io.ack` → `clientX.io.ack` / `io.ackX` (acknowledgement outputs)
  - `clientX.io.req` → `controllerX.io.req` / `io.reqX` (request inputs)
  - `controllerX.io.pass_token` → combinational `active` → `RegNext` → `arbiter.io.active` (token-passing feedback loop)

### Design Under Test
The `ArbiterLE` implements a round-robin arbiter with token-passing for three clients. The arbiter cycles through selections (A→B→C→A) whenever `active` is high. A controller asserts `pass_token` when:
- It is selected (`io.sel === io.id`) AND in IDLE state with no request, OR
- It is in BUSY state with an active request.

## 2. Violated Assertion

### Assertion Name
`reqA_eventually_gets_ackA_within_30_cycles`

### Code Snippet
From `arbiter_le.scala`, line 272:
```scala
astRelaxedLiveness(io.reqA, io.ackA, 30, "reqA eventually gets ackA within 30 cycles")
```

### Property Description
Whenever `io.reqA` is asserted, `io.ackA` must be asserted within 30 clock cycles.

### File Location
`arbiter_le.scala`, line 272

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/arbiter_arbiter_le/ArbiterLE.reqA_eventually_gets_ackA_within_30_cycles.fst`

### Time Range and Key Time Points
- **Full range**: 0 ns → 330 ns (33 cycles, 10 ns per cycle)
- **Time 0 (cycle 0)**: All signals at reset values
- **Time 10 (cycle 1)**: `io.reqA` rises from 0 → 1 (clock edge)
- **Time 320 (cycle 31)**: Assertion fires (falls from 1 → 0) — failure detected

### Critical Signal Values at Failure Point (time 320 ns)

| Signal | Value | Meaning |
|--------|-------|---------|
| `io.reqA` | 1 | Request A has been asserted for 31 cycles |
| `io.ackA` | 0 | No acknowledgement ever delivered |
| `io.sel [1:0]` | 11 (`Selection.X`) | Arbiter never selected any client |
| `active` | 0 | No pass_token is active |
| `arbiter.io.active` | 0 | Arbiter sees inactive — never starts |
| `controllerA.state [1:0]` | 00 (`IDLE`) | Controller A never left idle |
| `controllerB.state [1:0]` | 00 (`IDLE`) | Controller B never left idle |
| `controllerC.state [1:0]` | 00 (`IDLE`) | Controller C never left idle |
| `controllerA.io.pass_token` | 0 | No pass_token from any controller |
| `controllerB.io.pass_token` | 0 | |
| `controllerC.io.pass_token` | 0 | |

## 4. Root Cause Analysis

### Bug Location
**File**: `arbiter_le.scala`, **line 220**
```scala
arbiter.io.active := RegNext(active)
```

### Description of the Bug

The system has a **deadlock at initialization** caused by an incorrect reset value for the `active` register that feeds the arbiter.

#### The Deadlock Cycle

1. At reset, `active` = `controllerA.io.pass_token || controllerB.io.pass_token || controllerC.io.pass_token` = `0`
2. `arbiter.io.active := RegNext(active)` initializes to **`false.B`** (the default for Chisel's `RegNext`)
3. Since `arbiter.io.active = 0`, the arbiter outputs `Selection.X` (value `11`):
   ```scala
   io.sel := Mux(io.active, state, Selection.X)  // line 90 of Arbiter
   ```
4. Since `io.sel = X` (which is `11` in binary, value 3), no controller's `id` matches it:
   - `controllerA.io.id := Selection.A` (= `00`)
   - `controllerB.io.id := Selection.B` (= `01`)
   - `controllerC.io.id := Selection.C` (= `10`)
5. In each Controller, `is_selected = (io.sel === io.id)` is always **false**
6. The combinational `io.pass_token` depends on `is_selected`:
   ```scala
   io.pass_token := (state === ControllerState.IDLE && is_selected && !io.req) ||
                    (state === ControllerState.BUSY && io.req)  // lines 50-51
   ```
   Since `is_selected` is always false, `io.pass_token` is always **false** for all controllers
7. Therefore `active` stays `0` forever, and the system is **permanently deadlocked**

#### The Bootstrap Mechanism That Failed

The design has `pass_tokenReg = RegInit(true.B)` (line 38) in each Controller, initialized to `true`. However, `io.pass_token` is a **combinational** output (lines 50-51) that does **not** use `pass_tokenReg`. The register is only used internally by the state machine and is never connected to the output.

In a prior version of the design, `io.pass_token` was likely connected to `pass_tokenReg`, meaning at reset all controllers would assert `pass_token=true`, making `active=true`, and the arbiter would start cycling after one clock cycle. When the code was refactored to use combinational `pass_token` logic (as noted in the comment on lines 47-49), the bootstrap path via `pass_tokenReg` was inadvertently broken.

#### Evidence from Waveform

All signals in the waveform are **completely static**:
- `io.sel` stays at `11` (`Selection.X`) for all 33 cycles — the arbiter never selects anyone
- All controller states remain at `00` (`IDLE`) for the entire simulation
- All `pass_token` signals remain at `0`
- `io.ackA` remains at `0` for the entire simulation despite `io.reqA` being asserted for 31 cycles

### Why the Assertion Fails

The assertion `astRelaxedLiveness(io.reqA, io.ackA, 30, ...)` checks that after `io.reqA` goes high, `io.ackA` must go high within 30 cycles. Since `io.reqA` goes high at time 10 (cycle 1) and `io.ackA` never goes high due to the deadlock, the assertion fails at the 30-cycle deadline (time 320, cycle 31).

### Error Category
**dut_bug** — The design has a genuine initialization deadlock bug.

### Recommended Fix

On line 220 of `arbiter_le.scala`, change:
```scala
arbiter.io.active := RegNext(active)
```
to:
```scala
arbiter.io.active := RegNext(active, true.B)
```

This initializes the arbiter's `active` input to `true` at reset, causing it to immediately start cycling through selections (A→B→C→→...) until a controller asserts `pass_token`, which sustains the cycle naturally. This provides the bootstrap pulse needed to break the initialization deadlock.
