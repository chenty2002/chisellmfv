# Counterexample Analysis: `count_never_exceeds_2`

## 1. Verification Environment

- **Top Module**: `barrier` (from `barrier.scala`, package `llmverify`)
- **Design Under Test**: A two-lane barrier synchronization FSM. Each lane (0 and 1) has its own program counter (`pc(0)` and `pc(1)`), but they share a single `count` register and a single `rel` (release) signal. The active lane is selected by `self`, which registers the `io.select` input.
- **Key Components**:
  - `pc(0)`, `pc(1)`: 3-bit program counters per lane (enum Loc: L0=0..L6=6)
  - `count`: shared 2-bit counter (values 0-3)
  - `rel`: shared 1-bit release flag
  - `self`: registered version of `io.select`, determines which lane is active each cycle
  - `io.pause`: when asserted at L0, prevents the lane from advancing

- **Structure**: The assertions and DUT logic coexist in a single `barrier` module. Two separate lanes iterate through states L0→L1→L2→L3→(L4 or L6)→L5→L0, with lane switching controlled by the `self` signal.

## 2. Violated Assertion

- **Assertion Name**: `count_never_exceeds_2` (from waveform filename `barrier.count_never_exceeds_2.fst`)
- **Code Snippet** (barrier.scala, line ~80):

```scala
fvAssert(count <= 2.U, "count_never_exceeds_2")
```

- **Natural Language Description**: The shared `count` register (2 bits wide, values 0-3) must never exceed the value 2. This guards against count overflow, since the FSM only increments count at L2 (up to 2) and resets it at L4. If count reaches 3, it indicates a count overflow bug.
- **File Location**: `barrier.scala`, line ~80 (in the `// --- SAFETY: Count bounds ---` section)

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/barrier/barrier.count_never_exceeds_2.fst`
- **Time Range**: 0 ns → 250 ns (25 cycles @ 10 ns/cycle)
- **Key Time Points**:
  - **t=200 ns**: `count` reaches 2 (binary `10`) — lane 1 (pc_1=3/L3) has just incremented via L2, and count is now at maximum expected value
  - **t=210 ns**: Lane 0 (pc_0) returns to L0 from L5
  - **t=220 ns**: Lane 0 advances to L1
  - **t=230 ns**: Lane 0 advances to L2; `self=0`, so the FSM processes pc_0
  - **t=240 ns**: **ASSERTION FAILS** — `count` becomes 3 (binary `11`), `count_never_exceeds_2` drops to 0
  - **t=250 ns**: Count remains 3, assertion still failing

**Critical Signal Values at Failure Point (t=240 ns)**:

| Signal | Value | Interpretation |
|--------|-------|---------------|
| `barrier.count [1:0]` | `11` (3) | Count overflowed |
| `barrier.pc_0 [2:0]` | `011` (L3) | Lane 0 just transitioned from L2 |
| `barrier.pc_1 [2:0]` | `011` (L3) | Lane 1 stuck at L3 since t=200 |
| `barrier.self` | `1` | Active lane now = 1 |
| `barrier.rel` | `0` | Release flag not set |
| `barrier.count_never_exceeds_2` | `0` | Assertion violated |

## 4. Root Cause Analysis

### Buggy Code Location

**File**: `barrier.scala`  
**Module**: `class barrier`  
**Lines 36-37** (the L2 state transition):

```scala
}.elsewhen(pc(self) === Loc.L2.asUInt) { // 2
  count := count + 1.U
  pc(self) := Loc.L3.asUInt
```

### Description of the Bug

The `count` register is a **shared resource** between two independent FSM lanes (`pc(0)` and `pc(1)`), but there is **no mutual exclusion or protection** against one lane incrementing count when the other lane has already brought count to its maximum value of 2.

The bug manifests through the following sequence of events:

1. **t=190→200**: Lane 1 is selected (`self=1`). Lane 1 is at L2, so `count` is incremented from 1 to 2. Lane 1 then advances to L3.

2. **t=200→210**: At L3, lane 1 should check if `count === 2.U` and, if so, proceed to L4 to reset count to 0. However, at t=200, `self=0` — the active lane switches to lane 0. Lane 1 is **stuck at L3** because the FSM only processes `pc(self)`, and `self=0` means only `pc(0)` is updated.

3. **t=210→230**: Lane 0, which was at L5, cycles through L0→L1→L2 while lane 1 remains frozen at L3.

4. **t=230→240**: Lane 0 enters L2 (pc_0=2). At this point, `count` is still 2 (set by lane 1 at t=200, never reset). The FSM executes `count := count + 1.U`, incrementing count from **2 to 3**, which violates the assertion `count <= 2.U`.

### Why This Happens

The design has a fundamental architectural flaw: **two independent lanes share a single `count` register without any coordination**. The FSM logic assumes only one lane will ever be in the counting phase (L2→L3→L4), but the `self` signal can switch between lanes at any time, causing:

- Lane 1 to set count=2 and get stuck at L3 (waiting for self to select it again)
- Lane 0 to then increment the already-maximum count from 2 to 3

### Fix

The fix depends on the intended design semantics:

1. **If lanes should be mutually exclusive** (only one lane runs at a time): The `self` selection should ensure a lane completes its full iteration (L0→L5→L0) before switching, OR each lane should have its own `count` register.

2. **If lanes are truly independent**: Each lane needs its own private `count` register, or a semaphore mechanism to prevent both lanes from being in the counting phase simultaneously.

The simplest fix would be to **give each lane its own counter**, e.g., `val count = RegInit(VecInit(0.U(2.W), 0.U(2.W)))` and use `count(self)` in the FSM logic.

### Evidence from Waveform

The waveform trace clearly shows:
- At t=200: `count=2` (set by lane 1), `pc_0=5(L5)`, `pc_1=3(L3)`
- At t=200→230: lane 1 remains at L3 while `self=0` consistently (lane 0 is processed for 3 consecutive cycles)
- At t=230: `pc_0=2(L2)`, `count=2` (unchanged since t=200)
- At t=240: `count=3`, assertion fails — lane 0 incremented the already-saturated count from 2 to 3

This is a **dut_bug** — a genuine design bug in the barrier FSM.
