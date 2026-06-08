# Counterexample Analysis: `input_capture` Assertion Failure

## 1. Verification Environment

### Top Module Structure
- **Top Module**: `rotate` (in package `llmverify`)
- **Source File**: `rotate4.scala` (55 lines)
- **Design**: A 4-bit barrel shifter that performs configurable rotate-right operations

### Key Components
| Signal | Type | Description |
|---|---|---|
| `io.din` | Input (UInt<4>) | Data input |
| `io.amount` | Input (UInt<2>) | Rotation amount (bit0=rot1, bit1=rot2) |
| `io.dout` | Output (UInt<4>) | Rotated output |
| `inr` | Reg (4-bit) | Input capture register, `RegInit(0.U(4.W))` |
| `dout` | Reg (4-bit) | Output register, `RegInit(0.U(4.W))` |
| `tmp0/tmp1/tmp2` | Wire | Combinational barrel-shifter stages |

### Connections
- `inr := io.din` (captures input on every clock edge)
- `dout := tmp2` (registers rotated result)
- `io.dout := dout` (drives output)

## 2. Violated Assertion

### Assertion Name
**`input_capture`** — extracted from waveform filename `rotate.input_capture.fst`

### Code Snippet (rotate4.scala, lines 44-45)
```scala
// Safety 2: Input register captures the data input each cycle.
fvAssert(inr === RegNext(io.din), "input_capture")
```

### Property Description
The assertion checks that register `inr` always equals `RegNext(io.din)` — a register that delays `io.din` by one clock cycle. Since `inr := io.din` captures `io.din` on every clock edge, both `inr` and `RegNext(io.din)` should:
1. **Initialize to 0** (both use `RegInit(0.U(4.W))` or equivalent)
2. **Capture the same `io.din` value** on every clock edge

They should therefore always be identical. The assertion verifies that `inr` correctly functions as an input data register.

### File Location
- **File**: `rotate4.scala`
- **Line**: 45
- **Symbol**: `class rotate`

## 3. Waveform Information

### Waveform File
- **Full path**: `verilog/extra_bench/rotate_rotate4/rotate.input_capture.fst`
- **Duration**: 1 cycle (10 ns, 0 → 10 ns)
- **Clock**: 1 from 0–5ns, 0 from 5–10ns (posedge at time 0)

### Signal Values (stable at all time points)

| Signal | Value | Description |
|---|---|---|
| `rotate.clock` | 1 (0→5ns), 0 (5→10ns) | System clock |
| `rotate.reset` | 0 | Reset de-asserted |
| `rotate.io_din [3:0]` | `0000` | Data input = 0 |
| `rotate.io_amount [1:0]` | `00` | Rotation amount = 0 |
| `rotate.inr [3:0]` | **`0000`** | Input reg (correctly init to 0) |
| `rotate.dout [3:0]` | `0000` | Output reg (init to 0) |
| `rotate.REG [3:0]` | `0000` | **`RegNext(expected_tmp2)`** (init to 0 ✓) |
| **`rotate.REG_1 [3:0]`** | **`0001`** | **`RegNext(io.din)`** (**init to 1 ✗**) |
| `rotate.REG_2 [2:0]` | `000` | `RegNext(PopCount(inr))` (init to 0 ✓) |
| `rotate.expected_tmp1 [3:0]` | `0000` | Combinational compute |
| **`rotate.input_capture`** | **`1`** | **Assertion failure indicator** |

### Critical Observation
- `inr` = `0000` (correctly initialized to 0)
- `REG_1` = `0001` (should be `RegNext(io.din)` = `0000`, but is **`0001`**)
- `io_din` = `0000` (consistent with `inr` being 0)
- `input_capture` = 1 throughout (assertion fires = fails)

## 4. Root Cause Analysis

### Signal Mapping (Chisel → Waveform)

The three `RegNext` instances in assertions map to waveform signals by creation order:

| Order | Chisel Source | Waveform Signal | Expected Init | Actual Init |
|---|---|---|---|---|
| 1 | `RegNext(expected_tmp2)` (correct_rotation) | `REG [3:0]` | `0000` | `0000` ✓ |
| **2** | **`RegNext(io.din)` (input_capture)** | **`REG_1 [3:0]`** | **`0000`** | **`0001` ✗** |
| 3 | `RegNext(PopCount(inr))` (popcount_invariance) | `REG_2 [2:0]` | `000` | `000` ✓ |

### Bug Description

The register created by **`RegNext(io.din)`** inside the `fvAssert` call initializes to **`0001` (value 1)** instead of **`0000` (value 0)**. Meanwhile, the `inr` register (created by `RegInit(0.U(4.W))`) correctly initializes to `0000`.

This discrepancy causes the assertion `inr === RegNext(io.din)` to fail at the initial state:
```
inr (0000) !== RegNext(io.din) (0001)  → assertion fails
```

### Root Cause: `RegNext(io.din)` Register Initialization Mismatch

Both registers should be functionally identical per Chisel semantics:
- **`inr`**: `RegInit(0.U(4.W))` → initial value = `0000`
- **`RegNext(io.din)`**: `RegNext(io.din, 0.U(4.W))` → initial value = `0000`

However, the generated hardware shows `REG_1` (the `RegNext(io.din)` register) initializing to `0001`, not `0000`. This means the **`RegNext` register inside the `fvAssert` assertion has incorrect initialization behavior** — it either:
1. Doesn't receive the correct reset signal, or
2. Uses a different initialization mechanism than `RegInit`

### Detailed Failure Mechanism

At time 0 (after reset, before any clock edge):
1. `inr` = `0000` (correctly from `RegInit`)
2. `io_din` = `0000`
3. `RegNext(io.din)` should = `0000` but instead = **`0001`**
4. Assertion evaluates: `0000 === 0001` = **false**
5. `input_capture` = 1 confirms assertion violation

### Error Classification: **DUT Bug** (Register Initialization Mismatch)

**Category**: Bug in the Original Design

The `input_capture` assertion correctly checks that `inr` captures input data each cycle by comparing it with a reference `RegNext(io.din)` register. However, the `RegNext(io.din)` register inside the assertion does not properly initialize to 0 in the generated hardware, creating a spurious mismatch with `inr`. Both registers should have identical behavior (same initialization, same capture logic), but they don't, revealing a bug in the register initialization of the design's assertion infrastructure.

### Recommended Fix

Ensure the `RegNext(io.din)` reference register initializes consistently with `inr`. One approach is to explicitly create the reference register outside the assertion to guarantee identical initialization:

```scala
// Create the reference register explicitly with matching initialization
val din_delayed = RegNext(io.din, 0.U(4.W))
fvAssert(inr === din_delayed, "input_capture")
```

This ensures both `inr` and the reference register are created with the same `RegInit` semantics in the same module context, avoiding any initialization mismatch in the generated hardware.
