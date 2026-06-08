# Counterexample Analysis Report: `lock.entry_state1_requires_pos12_up`

## 1. Verification Environment

- **Top module**: `lock` (Chisel → Verilog)
- **Benchmark**: `lock` (extra_bench)
- **Key components**:
  - `position[4:0]` — 5-bit position counter that increments/decrements based on `io_up`/`io_down`
  - `state[1:0]` — 2-bit state machine (states 0→1→2→3, where 3 = open)
  - `upReg`, `downReg` — Registered versions of the up/down inputs (latched each cycle)
  - `prevState` — Previous state used for transition checking
  - `freezePosition` — Combinational signal that freezes position when a target state-transition condition is met
- **Design description**: The lock is a combination-lock state machine. Starting from state 0 at any position (0-31), the user must navigate a sequence of position targets using up/down inputs. The correct sequence is: state 0 requires position=12 with up, state 1 requires position=21 with down, state 2 requires position=15 with up, and state 3 is the open state.

## 2. Violated Assertion

- **Assertion name**: `entry_state1_requires_pos12_up`
- **Waveform file**: `lock.entry_state1_requires_pos12_up.fst`

### Code snippet (lock.scala, lines 108-111):
```scala
  fvAssert(
    !(prevState === 0.U && state === 1.U) || (position === 12.U && upReg),
    "entry_state1_requires_pos12_up"
  )
```

### Natural language description:
> If the state machine transitions from state 0 to state 1, then at the cycle where this transition is observed, `position` must equal 12 and `upReg` must be true.

### File location:
- `lock.scala`, lines 108–111

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/lock/lock.entry_state1_requires_pos12_up.fst`
- **Time range**: 0 ns → 140 ns (14 cycles, clock period = 10 ns)

### Key time points and signal values:

| Time (ns) | state[1:0] | prevState[1:0] | position[4:0] | upReg | io_up | freezePosition | Event |
|-----------|------------|----------------|---------------|-------|-------|----------------|-------|
| 0         | 00         | 00             | 00000         | 0     | 1     | 0              | Initial |
| 110       | 00         | 00             | 01011 (11)    | 1     | 1     | 0              | Before critical edge |
| 115       | 00         | 00             | 01011 (11)    | 1     | 1     | 0              | Between clock edges |
| 120       | 00         | 00             | 01100 (12)    | 1     | **0**  | 0              | **Critical posedge**: position=12, upReg=1, io_up drops to 0 |
| 130       | **01**     | **00**         | 01100 (12)    | **0** | 0     | 1              | **Assertion failure**: prevState=0, state=1, upReg=0 |

### Assertion failure time: 130 ns

## 4. Root Cause Analysis

### Type: **Assertion Error** — the assertion checks `upReg` at the wrong timing snapshot.

### Buggy code location:
- `lock.scala`, line 109 (the assertion condition)

### Root cause explanation:

The assertion verification condition is:
```scala
!(prevState === 0.U && state === 1.U) || (position === 12.U && upReg)
```

This checks that when a state transition from 0→1 is observed (via `prevState=0, state=1`), the **current** values of `position` and `upReg` satisfy `position===12 && upReg`.

**The problem**: The assertion fails because `upReg` is a register that gets updated **every cycle** by sampling `io.up && !io.down`. The state transition from 0→1 was correctly triggered at the posedge at **time 120 ns**, where:
- `position = 12` ✓
- `upReg (old value) = 1` ✓
- The condition `position === 12.U && upReg` evaluated to **true**, causing `state` to become 1.

However, at that same posedge (time 120 ns), `io_up` simultaneously dropped from 1 to 0. This means:
- `upReg` samples `io_up = 0` at the posedge and becomes `0`
- By the **next** posedge (time 130 ns), where the assertion checks the condition, `upReg` is already **0**

### Detailed cycle-by-cycle trace:

1. **Time 110 ns** (posedge): state=0, position=11, upReg=1, io_up=1. Position increments to 12 between edges.

2. **Time 120 ns** (posedge): At this edge:
   - The combinatorial condition `position === 12.U && upReg` evaluates as: `(position=12) && (upReg=1)` = **true**
   - State transition fires: `state := 1.U`
   - But simultaneously: `upReg := io.up && !io.down` where `io.up = 0` → `upReg := 0` (update scheduled for next cycle)
   - `freezePosition` goes high (combinational), freezing position at 12

3. **Time 130 ns** (posedge, assertion check):
   - `prevState = 0` (sampled old state before transition)
   - `state = 1` (new state from transition)
   - `position = 12` (frozen by freezePosition)
   - `upReg = 0` (updated to new value because io_up was 0)
   - Assertion evaluates `!(0 && 1) || (12===12 && 0===1)` → `!(false) || (true && false)` → **true || false → false**
   - **ASSERTION FAILS**

### Why this is an assertion error, not a design bug:

The **state transition itself is correct**: the condition `position === 12.U && upReg` was true at the moment of transition (time 120 ns). The freezePosition correctly froze the position at 12. The only reason the assertion fails is that `upReg` has already been updated to reflect the NEW `io_up=0` input by the time the assertion checks it one cycle later.

The assertion should check the value of `upReg` that was valid **at the time of the transition** (i.e., the previous cycle's value), not the current cycle's value. The fix is to use registered (previous) values of `position` and `upReg` in the assertion condition.

### Suggested fix:

```scala
// Use registered values to capture the condition at the moment of transition
val prevUpReg = RegNext(upReg)
fvAssert(
    !(prevState === 0.U && state === 1.U) || (RegNext(position) === 12.U && prevUpReg),
    "entry_state1_requires_pos12_up"
)
```

This ensures the assertion checks the `position` and `upReg` values that were present at the cycle **before** the transition was observed, which is when the transition condition actually triggered.
