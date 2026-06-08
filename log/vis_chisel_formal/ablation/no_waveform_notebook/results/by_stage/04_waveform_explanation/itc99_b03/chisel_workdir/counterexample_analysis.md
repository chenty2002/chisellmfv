# Counterexample Analysis Report: `grant_valid_when_serving`

## 1. Verification Environment

- **Top module**: `b03` (itc99_b03 benchmark)
- **Work directory**: `chisel/extra_bench/itc99_b03`
- **Source file**: `b03.scala`
- **Key components**:
  - 3-state FSM: `sInit` (0), `sAnalisReq` (1), `sAssegna` (2)
  - 4-entry queue (`coda0`–`coda3`), each entry is 3-bit encoding of the requestor (U1/U2/U3/U4)
  - Request registers (`ru1`–`ru4`): capture input requests in `sInit` and `sAssegna`
  - Follow-up registers (`fu1`–`fu4`): track pending requests that haven't been serviced
  - Grant register (`grant`): 4-bit one-hot grant output, registered
  - Output: `io.GRANT_O` is wired directly to `grant`

## 2. Violated Assertion

- **Assertion name**: `grant_valid_when_serving` (from waveform filename `b03.grant_valid_when_serving.fst`)
- **Code** (lines 141–142 of `b03.scala`):

```scala
val any_fu = fu1 || fu2 || fu3 || fu4
val coda0_valid = coda0 === U1 || coda0 === U2 || coda0 === U3 || coda0 === U4
val in_assegna = stato === sAssegna
fvAssert(!(in_assegna && any_fu && coda0_valid) || grant.orR,
  "grant_valid_when_serving")
```

- **Natural language property**: When the FSM is in the `sAssegna` state with pending tracked requests (`any_fu` = true) and a valid queue head entry (`coda0_valid` = true), the grant output must be non-zero.
- **File location**: `b03.scala`, lines 138–142

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/itc99_b03/b03.grant_valid_when_serving.fst`
- **Duration**: 3 cycles (0 ns to 30 ns)
- **Clock period**: 10 ns (posedge at 0, 10, 20, 30 ns)

### Key Signal Timeline

| Time (ns) | stato | grant | coda0 | fu2 | ru2 | io_REQUEST2 | any_fu | in_assegna | assertion signal |
|-----------|-------|-------|-------|-----|-----|-------------|--------|------------|-----------------|
| 0         | 00 (sInit) | 0000 | 000 | 0 | 0 | 1 | 0 | 0 | 1 (pass) |
| 10        | 01 (sAnalisReq) | 0000 | 000 | 0 | 1 | 0 | 0 | 0 | 1 (pass) |
| 20        | 10 (sAssegna) | **0000** | **010 (U2)** | **1** | 1 | 0 | **1** | **1** | **0 (FAIL)** |
| 30        | 10 (sAssegna) | 0000 | 010 (U2) | 1 | 1 | 0 | 1 | 1 | 0 (FAIL) |

### Critical Values at Failure Point (t=20 ns)

| Signal | Value | Meaning |
|--------|-------|---------|
| `b03.stato [1:0]` | `10` (2) | FSM in `sAssegna` state |
| `b03.in_assegna` | `1` | `stato === sAssegna` |
| `b03.any_fu` | `1` | At least one follow-up request pending |
| `b03.fu2` | `1` | Requestor 2 is pending |
| `b03.coda0 [2:0]` | `010` (2) | Queue head contains `U2` ("b010") |
| `b03.grant [3:0]` | `0000` | **Grant is zero – assertion fails** |
| `b03.grant_valid_when_serving` | `0` | Assertion violation |

## 4. Root Cause Analysis

### Root Cause Type: **Incorrect Assertion** (assertion_error)

The assertion `grant_valid_when_serving` has a **timing mismatch**: it checks `grant.orR` in the same cycle that the grant register is being assigned, but since `grant` is a registered signal (`RegInit(0.U(4.W))`), its current value reflects the state *before* the update, not *after*.

### Detailed Explanation

#### The FSM Sequence

1. **Cycle 0 (t=0, sInit)**: `io_REQUEST2` is asserted (1). In `sInit`, `ru2` captures this request. State transitions to `sAnalisReq`.

2. **Cycle 1 (t=10, sAnalisReq)**: `ru2=1` and `fu2=0` (not yet tracked), so the queue is updated: `coda0 := U2` ("b010"). `fu2` is set to `ru2=1`. State transitions to `sAssegna`. Grant remains `0000`.

3. **Cycle 2 (t=20, sAssegna)**: The FSM enters `sAssegna`. At this point:
   - `in_assegna` = true
   - `any_fu` = true (`fu2=1`)
   - `coda0` = `U2` (valid queue head)
   - `grant` = `0000` (still the initial value — **has never been updated**)

   In this cycle, the `sAssegna` logic *does* assign `grant := "b0100".U` (because `fu2` is true and `coda0 === U2`). However, since `grant` is a register, this new value **only takes effect on the next clock edge (t=30)**. The assertion evaluates `grant.orR` **in the current cycle** and sees the old value `0000`.

#### Why the Assertion is Wrong

The assertion comment (line 129) says:
> "when in sAssegna with pending follow-up requests and a valid queue head, the grant must be non-zero **in the next cycle**."

The comment correctly identifies that `grant` is registered and appears *after* the clock edge. However, the assertion implementation:

```scala
fvAssert(!(in_assegna && any_fu && coda0_valid) || grant.orR,
    "grant_valid_when_serving")
```

checks `grant.orR` **in the same cycle**, not the next cycle. Since `grant` is registered, it still holds its previous value (initial 0) at the point of evaluation. The assertion will **always fail on the first entry to `sAssegna`** when there are pending requests and the queue head is valid, because the grant hasn't been set yet.

#### Fix

The assertion should be corrected to check the grant value after the register update takes effect. For example, use `past()` to check `grant.orR` one cycle later:

```scala
// Correct: check grant in the next cycle after the sAssegna update
fvAssert(!(in_assegna && any_fu && coda0_valid) || past(grant.orR, 1),
    "grant_valid_when_serving")
```

Or, alternatively, the assertion could check that the grant assignment's target value (the input to the register) is non-zero rather than the current registered value. However, the simplest correct fix is to use `past(grant.orR, 1)` to peek at the grant value *after* the clock edge when the new value has settled.

### Bug-free Design

The actual design logic (`b03.scala`) is functionally correct:
- In `sAssegna`, when a valid queue head exists and there are pending requests, `grant` is assigned a non-zero one-hot value matching the queue head requestor
- The queue is properly shifted
- The grant value will appear on `io.GRANT_O` on the next clock cycle

The bug is solely in the assertion property, which fails to account for the register's one-cycle latency.
