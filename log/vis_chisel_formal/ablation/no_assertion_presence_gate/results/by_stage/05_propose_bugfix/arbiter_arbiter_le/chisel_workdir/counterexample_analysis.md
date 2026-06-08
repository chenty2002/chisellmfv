# Counterexample Analysis Report: ArbiterLE.at_most_one_pass_token

## 1. Verification Environment

### Top Module
- **Module Name**: `ArbiterLE` (in `arbiter_le.scala`)
- **Structure**: The module instantiates three `Controller` modules (A, B, C), one `Arbiter` module, three `Client` modules, and one `Observer` module.
- **Key Components**:
  - **Controller** (×3): Each controller has a `pass_tokenReg` register that indicates whether it is passing the token forward. The token-passing logic is governed by the controller's state machine (`IDLE` → `READY` → `BUSY`).
  - **Arbiter**: Round-robin arbiter cycling through Selection.A → Selection.B → Selection.C. Outputs `io.sel` based on `io.active`.
  - **Client** (×3): Generates random requests using an LFSR-based random choice.
- **Connections**:
  - `active = pass_tokenA || pass_tokenB || pass_tokenC` (drives arbiter's `io.active`)
  - `arbiter.io.sel` is distributed to all three controllers as their `io.sel`
  - Each controller's `io.id` is hardwired to its respective selection (A=00, B=01, C=10)

## 2. Violated Assertion

### Assertion Name
`at_most_one_pass_token`

### Code Snippet
```scala
// Line 242 in arbiter_le.scala
assertOneHot0(Cat(io.pass_tokenA, io.pass_tokenB, io.pass_tokenC), "at_most_one_pass_token")
```

### Property Description
The assertion checks that **at most one** of the three pass_token output signals is high at any given time. This is a safety property ensuring mutual exclusion of the token-passing mechanism. The `assertOneHot0` constraint requires that the number of high bits in the concatenated pass_token signals is ≤ 1.

### File Location
- **File**: `arbiter_le.scala`
- **Line**: 242 (within class `ArbiterLE`, the top module)

## 3. Waveform Information

### Waveform File
- **Full Path**: `verilog/extra_bench/arbiter_arbiter_le/ArbiterLE.at_most_one_pass_token.fst`
- **Duration**: 1 cycle (10 ns)
- **Time Range**: 0 ns → 10 ns

### Key Time Points and Signal Values

| Signal | Time 0 ns | Time 10 ns |
|--------|-----------|------------|
| `ArbiterLE.io_pass_tokenA` | **1** | **1** |
| `ArbiterLE.io_pass_tokenB` | **1** | **1** |
| `ArbiterLE.io_pass_tokenC` | **1** | **1** |
| `ArbiterLE.controllerA.pass_tokenReg` | **1** | **1** |
| `ArbiterLE.controllerB.pass_tokenReg` | **1** | **1** |
| `ArbiterLE.controllerC.pass_tokenReg` | **1** | **1** |
| `ArbiterLE.controllerA.state [1:0]` | 00 (IDLE) | 00 (IDLE) |
| `ArbiterLE.controllerB.state [1:0]` | 00 (IDLE) | 00 (IDLE) |
| `ArbiterLE.controllerC.state [1:0]` | 00 (IDLE) | 00 (IDLE) |
| `ArbiterLE.arbiter.state [1:0]` | 00 (A) | 00 (A) |
| `ArbiterLE.active` | 1 | 1 |
| `ArbiterLE.controllerA.io_req` | 0 | 0 |
| `ArbiterLE.controllerB.io_req` | 0 | 0 |
| `ArbiterLE.controllerC.io_req` | 0 | 0 |

### Failure Point
The assertion is violated **immediately after reset** (time 0 ns) and remains violated throughout the entire trace (0–10 ns) because all three pass_token signals are high simultaneously.

## 4. Root Cause Analysis

### Buggy Code Location
- **File**: `arbiter_le.scala`
- **Line**: 38 (within `class Controller`)
- **Module**: Controller

```scala
// Line 38 - the buggy line
val pass_tokenReg = RegInit(true.B)
```

### Description of the Bug

**Category: Bug in the Original Design (dut_bug)**

The `Controller` class initializes its `pass_tokenReg` register to `true.B` at reset. When three instances of `Controller` (A, B, C) are created inside `ArbiterLE`, all three controllers start with `pass_tokenReg = true.B` simultaneously. This directly violates the `assertOneHot0` assertion, which requires that **at most one** pass_token signal is high.

The intended design behavior is:
1. At most one controller should be actively passing the token at any time.
2. The round-robin arbiter selects which controller gets the token next.
3. Non-selected controllers should not pass the token.

However, the initialization sets **all three** controllers' `pass_tokenReg` to true, creating a situation where:
- Controller A: `pass_tokenReg = true` (expected, since arbiter starts with Selection.A)
- Controller B: `pass_tokenReg = true` (**INCORRECT** — should be false)
- Controller C: `pass_tokenReg = true` (**INCORRECT** — should be false)

### Evidence from Waveform

The waveform trace shows **no transitions** on any signal throughout the entire 10 ns simulation:
1. All three `pass_tokenReg` values remain at 1 from time 0 to time 10.
2. All three controller states remain at `IDLE` (00) — no state transitions occur.
3. The arbiter state remains at `Selection.A` (00) — no round-robin cycling.
4. All `io_req` values remain at 0.

The fact that all three `pass_tokenReg` registers are simultaneously 1 at time 0 (immediately after reset) is the direct cause of the assertion failure.

### Why It Causes the Assertion to Fail

The assertion `assertOneHot0(Cat(io.pass_tokenA, io.pass_tokenB, io.pass_tokenC))` computes a one-hot-0 check on the three pass_token bits. With all three bits set to `111`, the count of high bits is 3, which violates the "at most one" constraint (≤ 1).

### Suggested Fix

The fix is to initialize only **one** controller's `pass_tokenReg` to `true.B` (the one that the arbiter selects first). Since the arbiter initializes to `Selection.A`, only Controller A should start with the token. Controllers B and C should initialize their `pass_tokenReg` to `false.B`.

**Option 1** (simplest): Change the initialization in `Controller` to accept a parameter:
```scala
class Controller(initToken: Boolean = false) extends Module {
  val pass_tokenReg = RegInit(initToken.B)
  // ...
}
```
Then instantiate: `val controllerA = Module(new Controller(true))`

**Option 2** (in `ArbiterLE`): Alternatively, override the pass_tokenReg initialization externally, but Option 1 is cleaner since it aligns with the arbiter's initial selection of A.

**Option 3**: Initialize all to `false.B` and add an initial grant sequence, but this is more complex and unnecessary since the round-robin naturally starts with A.
