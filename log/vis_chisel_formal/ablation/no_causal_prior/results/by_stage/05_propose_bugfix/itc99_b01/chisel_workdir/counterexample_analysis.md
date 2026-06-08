# Counterexample Analysis Report: itc99_b01

## 1. Verification Environment

### Top Module Structure
- **Top module**: `b01` (Chisel module with `Formal` mixin)
- **Source file**: `chisel/extra_bench/itc99_b01/b01.scala`
- **Generated Verilog**: Not directly available (Chisel generated)

### Key Components
- **State register** (`stato`): 3-bit register using ChiselEnum `b01State` with 8 states (a, b, c, e, f, g, wf0, wf1)
- **Output registers**: `outpReg` (OUTP) and `overflwReg` (OVERFLW) — both `RegInit(false.B)`
- **Inputs**: `io.LINE1` and `io.LINE2` (Bool)
- **Outputs**: `io.OUTP` and `io.OVERFLW` (Bool)

### Design Description
The b01 module implements a finite state machine (FSM) based on the ITC'99 benchmark. The FSM has 8 states and produces two outputs:
- `OUTP`: either XOR or XNOR of the two line inputs, depending on the current state
- `OVERFLW`: asserted only in state `e`

The FSM transitions between states based on the values of LINE1 and LINE2.

---

## 2. Violated Assertion

### Assertion Name
`OUTP_XOR_correctness` (from waveform filename: `b01.OUTP_XOR_correctness.fst`)

### Code Snippet
```scala
// Source file: b01.scala, lines ~101-126

// XOR of the two line inputs
val inXor = io.LINE1 ^ io.LINE2

// States that output XOR: a, b, c, e, wf0
val xorStates = (stato === b01State.a) || (stato === b01State.b) || 
                (stato === b01State.c) || (stato === b01State.e) || 
                (stato === b01State.wf0)

// ...

// Assertion 2: OUTP correctness in XOR states
// When stato is a, b, c, e, or wf0, outpReg must be XOR of inputs.
fvAssert(!xorStates || (outpReg === inXor), "OUTP_XOR_correctness")
```

### Property Description
The assertion checks: **If the current state is one of the XOR-output states (a, b, c, e, or wf0), then the registered output `outpReg` must equal the XOR of the two line inputs**.

### File Location
- **Path**: `chisel/extra_bench/itc99_b01/b01.scala`
- **Line**: ~124

---

## 3. Waveform Information

### Waveform File
- **Full path**: `verilog/extra_bench/itc99_b01/b01.OUTP_XOR_correctness.fst`
- **Duration**: 1 cycle (10 ns)
- **Time range**: 0 ns → 10 ns

### Key Time Points and Signal Values

#### At time 0 ns (posedge of clock):
| Signal | Value |
|--------|-------|
| `b01.clock` | 1 |
| `b01.reset` | 0 |
| `b01.stato [2:0]` | 000 (state `a`) |
| `b01.outpReg` | 0 |
| `b01.io_LINE1` | 0 |
| `b01.io_LINE2` | 1 |
| `b01.inXor` | 1 (= 0 ^ 1) |
| `b01.xorStates` | 1 (stato === a) |
| `b01.OUTP_XOR_correctness` | 1 |
| `b01.io_OUTP` | 0 |
| `b01.hasBeenReset` | 1 |
| `b01.hasBeenResetReg` | 1 |

#### At time 5 ns (negedge of clock):
| Signal | Value |
|--------|-------|
| `b01.clock` | 0 |
| `b01.stato [2:0]` | 000 (state `a`) |
| `b01.outpReg` | 0 |
| `b01.io_LINE1` | 0 |
| `b01.io_LINE2` | 1 |
| `b01.inXor` | 1 |

#### At time 10 ns:
| Signal | Value |
|--------|-------|
| `b01.clock` | 0 |
| All other signals unchanged from time 0 | |

### Critical Observation
The waveform shows **only one cycle** where all signals remain constant. The assertion failure occurs at time 0 because the initial register value (0) does not match the combinatorial XOR value (1).

---

## 4. Root Cause Analysis

### Root Cause Category: **Incorrect Assertion (assertion_error)**

### Description of the Bug

The assertion `OUTP_XOR_correctness` has a **timing mismatch** between the signals being compared:

```scala
fvAssert(!xorStates || (outpReg === inXor), "OUTP_XOR_correctness")
```

- **`outpReg`** is a **registered** signal (`RegInit(false.B)`), updated only on the **positive clock edge**. Its value reflects the computation from the **previous** clock cycle's state and inputs.
- **`inXor`** (= `io.LINE1 ^ io.LINE2`) is a **combinatorial** signal, reflecting the **current** inputs at all times.

The assertion compares a registered (past) value against a combinatorial (current) value, creating a timing mismatch that causes a false failure.

### Evidence from Waveform

At time 0 (initial state, posedge of clock):
1. `stato = a` (000) → `xorStates = true` (state `a` is in the XOR-output group)
2. `outpReg = 0` (initial register value from `RegInit(false.B)`)
3. `io.LINE1 = 0`, `io.LINE2 = 1` → `inXor = 0 ^ 1 = 1`
4. Assertion condition: `!true || (0 === 1)` = `false`

**The assertion fails because `outpReg` (0, from initialization) does not match `inXor` (1, from current inputs).**

However, the design behavior is correct: in state `a`, the FSM logic sets `outpReg := io.LINE1 ^ io.LINE2` (line ~70), so **on the next clock edge**, `outpReg` will be updated to `1`. The design correctly computes the XOR output — it just does so with a one-cycle delay through the registered output.

### Why This is an Assertion Bug (Not a Design Bug)

1. **Contrast with the OTHER assertions in the same file**: The `OVERFLW_correctness` assertion correctly uses `RegNext` to account for the register delay:
   ```scala
   fvAssert(RegNext(stato === b01State.e) === overflwReg, "OVERFLW_correctness")
   ```
   This properly checks: "the registered output `overflwReg` equals the previous cycle's state comparison."

2. **Design behavior is correct**: In the FSM, `outpReg` is a registered output that updates on clock edges with the XOR/XNOR of the current inputs. After the first clock edge, `outpReg` will match `inXor` from the previous cycle.

3. **The assertion compares mismatched timing domains**: The `outpReg` is a registered (clocked) signal, while `inXor` is combinatorial. The assertion should either:
   - Compare `outpReg` with `RegNext(inXor)` (previous cycle's XOR), or
   - Compare `outpReg` with the previous cycle's `inXor`

### Where the Bug Manifests

The assertion failure occurs at:
- **Initial state** (time 0): When `outpReg` still holds its initial value (0) while inputs (LINE1=0, LINE2=1) produce XOR=1.
- **Any cycle where inputs change within a state**: If inputs change while `stato` stays in an XOR state, `outpReg` still holds the XOR from when the state was entered, not the current input XOR.

### Corrected Assertion

The assertion should use `RegNext` to match the timing of the registered output:

```scala
fvAssert(!RegNext(xorStates) || (outpReg === RegNext(inXor)), "OUTP_XOR_correctness")
```

Or equivalently, using a single `RegNext` for `xorStates`:

```scala
fvAssert(!RegNext(xorStates) || (outpReg === RegNext(inXor)), "OUTP_XOR_correctness")
```

This checks: "If the **previous** state was an XOR-output state, then `outpReg` (which was updated based on that previous state) should equal the XOR of the **previous** cycle's inputs."

### Buggy Code Location
- **File**: `chisel/extra_bench/itc99_b01/b01.scala`
- **Line**: 124
- **Code**: `fvAssert(!xorStates || (outpReg === inXor), "OUTP_XOR_correctness")`
