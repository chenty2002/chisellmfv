# Counterexample Analysis: `rotate.barrel_shifter_implementation_match`

## 1. Verification Environment

- **Top module**: `rotate` (from `rotate4.scala`)
- **Design under test**: A 4-bit barrel shifter that implements rotate-right by an amount (0–3).
- **Key signals and components**:
  - `io.din` (input, 4-bit): data input
  - `io.amount` (input, 2-bit): rotation amount
  - `inr` (register, 4-bit): samples `io.din` on each clock edge
  - `tmp1`, `tmp2` (combinational): two-stage barrel shifter
    - `tmp1` = rotate right by 1 if `amount(0)=1`, else pass through
    - `tmp2` = rotate right by 2 if `amount(1)=1`, else pass through
  - `dout` (register, 4-bit): captures `tmp2` on each clock edge
  - `io.dout` (output, 4-bit): combinational alias of `dout`
- **Formal tool**: JasperGold / Chisel formal verification framework

## 2. Violated Assertion

- **Full assertion name**: `barrel_shipper_implementation_match` (from waveform filename `rotate.barrel_shifter_implementation_match.fst`)
- **Source location**: `rotate4.scala`, lines 65–70
- **Assertion code**:
  ```scala
  fvAssert(
    tmp2 === Mux(io.amount(1),
      Mux(io.amount(0), Cat(inr(0), inr(3, 1)), inr),
      Mux(io.amount(0), Cat(Cat(inr(0), inr(3, 1))(1, 0), Cat(inr(0), inr(3, 1))(3, 2)),
                         Cat(inr(1, 0), inr(3, 2)))),
    "barrel_shifter_implementation_match"
  )
  ```
- **Natural language description**: The assertion checks that `tmp2` (the combinational output of the two-stage barrel shifter) equals a directly computed rotate-right expression. It is meant to verify that the cascaded barrel-shifter implementation matches a direct mux-based rotate-right.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/rotate_rotate4/rotate.barrel_shifter_implementation_match.fst`
- **Time range**: 0 ns → 20 ns (2 clock cycles)
- **Key time points**:

| Time (ns) | Signal | Value | Description |
|-----------|--------|-------|-------------|
| 0 | `io.amount [1:0]` | 00 | Amount = 0 |
| 0 | `io.din [3:0]` | 1000 | Data input = 0x8 |
| 0 | `inr [3:0]` | 0000 | Register initially 0 |
| 0 | `tmp1 [3:0]` | 0000 | Stage 1 output |
| 0 | `tmp2 [3:0]` | 0000 | Stage 2 output (barrel shifter result) |
| 0 | `io.dout [3:0]` | 0000 | Design output |
| 10 | `io.amount [1:0]` | 11 | Amount = 3 |
| 10 | `io.din [3:0]` | 1000 | Data input = 0x8 |
| 10 | `inr [3:0]` | 1000 | inr sampled io.din from previous cycle |
| 10 | `tmp1 [3:0]` | 0100 | inr rotated right by 1 (since amount(0)=1) |
| 10 | `tmp2 [3:0]` | 0001 | tmp1 rotated right by 2 (since amount(1)=1) = correct result |
| 10 | `io.dout [3:0]` | 0000 | dout still holds old value |

## 4. Root Cause Analysis

### Error Classification: **Incorrect Assertion** (assertion_error)

The bug is in the assertion expression itself, not in the design under test.

### Evidence from the Counterexample

At time 10 ns with the counterexample values:

- `io.amount` = 3 (binary `11`)
- `io.din` = `1000` (hex 0x8)
- `inr` = `1000` (sampled from `io.din`)
- Actual design computation:
  - `tmp1` = `Mux(amount(0)=1, Cat(inr(0)=0, inr(3,1)=100), inr)` = `0100` (rotate right by 1 ✓)
  - `tmp2` = `Mux(amount(1)=1, Cat(tmp1(1,0)=00, tmp1(3,2)=01), tmp1)` = `0001` (rotate right by 2 more = rotate right by 3 total ✓)
- **Expected**: `1000` rotated right by 3 = `0001` — **design is correct** ✓

### What the Assertion Computes

The assertion RHS for `amount(1)=1`, `amount(0)=1` evaluates to the **inner branch**:
```
Cat(inr(0), inr(3, 1)) = Cat(0, 100) = 0100
```
This is rotate-right-by-1 **only**, not rotate-right-by-3.

### Root Cause Details

**Buggy code** (`rotate4.scala`, lines 65–68):
```scala
Mux(io.amount(1),
  Mux(io.amount(0), Cat(inr(0), inr(3, 1)), inr),         // BUG: rotate by 1 or 0 only
  ...
)
```

**Correct code** should be:
```scala
Mux(io.amount(1),
  Mux(io.amount(0), Cat(inr(2, 0), inr(3)),               // rotate right by 3
                     Cat(inr(1, 0), inr(3, 2))),           // rotate right by 2
  Mux(io.amount(0), Cat(inr(0), inr(3, 1)), inr)           // rotate right by 1 or 0
)
```

### Why This Is an Assertion Bug

The `barrel_shifter_implementation_match` assertion aims to rewrite the two-stage cascade (`tmp1` → `tmp2`) into a single expression. However, the author incorrectly assumed that when `amount(1)=1`, only the amount(0) bit matters for the single-stage expression. In reality:

- When `amount(1)=1` **and** `amount(0)=1`: total rotation is 3 (not 1)
- When `amount(1)=1` **and** `amount(0)=0`: total rotation is 2 (not 0)

The outer `Mux` on `amount(1)` should map to rotation amounts 2 or 3, not 0 or 1.

### Verification from the Golden Reference

The golden reference assertion `barrel_shifter_rotateright_correct` (lines 51–58) correctly checks the same design and passes, confirming the design is correct:
```scala
fvAssert(
  Mux(io.amount === 0.U, tmp2 === inr,
  Mux(io.amount === 1.U, tmp2 === Cat(inr(0), inr(3, 1)),
  Mux(io.amount === 2.U, tmp2 === Cat(inr(1, 0), inr(3, 2)),
                          tmp2 === Cat(inr(2, 0), inr(3))))),
  "barrel_shifter_rotateright_correct"
)
```

The design (`tmp2=0001`) matches the golden reference (`Cat(inr(2,0), inr(3)) = Cat(000, 1) = 0001`) for the counterexample. Only the `implementation_match` assertion fails because it has incorrect logic.
