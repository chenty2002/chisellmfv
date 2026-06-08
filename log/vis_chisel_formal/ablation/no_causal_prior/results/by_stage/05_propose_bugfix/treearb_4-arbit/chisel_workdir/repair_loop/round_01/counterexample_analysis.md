# Counterexample Analysis Report

## 1. Verification Environment

### Top Module Structure
- **Top module**: `main` (arbit.scala:127)
- **Design under test**: A tree arbiter with 4 processor clients (P1-P4) connected through a 2-level binary tree of arbiter cells (C0, C1, C2)
- **Processor model** (`procModel`): A 4-state handshake machine (idle → request → lock → release → idle)
- **Arbiter cell** (`arbitCell`): A round-robin arbiter cell that arbitrates between left and right child requests
- **Connections**:
  - P1, P2 → C1 (arbitrates between them) → C0 (root)
  - P3, P4 → C2 (arbitrates between them) → C0 (root)
  - C0 produces the final grant outputs (io_xa, io_ya → io_sa is unused)

### Processor State Machine (procModel)
```
idle(0) → request(1) → lock(2) → release(3) → idle(0)
```
- idle→request: when randCounter reaches 7 (after 8 cycles)
- request→lock: when grant (io_ack) is received
- lock→release: when randCounter > 3
- release→idle: unconditional (next cycle)

## 2. Violated Assertion

### Assertion Name
`release_to_idle_p1` (from waveform filename `main.release_to_idle_p1.fst`)

### Source Code Location
**File**: `arbit.scala`, line 266

### Code Snippet
```scala
// PROTOCOL: Release state cleanly transitions to idle in the next
// cycle (the procModel unconditionally leaves release for idle)
assertNextStepWhen(ur1 === HandShakeType.release, ur1 === HandShakeType.idle, "release_to_idle_p1")
```

### Intended Property
When `ur1` (P1's request output) is in the **release** state (3 = `2'h3`), in the **next clock cycle** it should be in the **idle** state (0 = `2'h0`). This is because the procModel unconditionally transitions from release to idle.

### Actual Generated Verilog Assertion
```verilog
release_to_idle_p1:
    assert property (@(posedge clock) disable iff (~hasBeenReset) _P1_io_req == 2'h0);
```
This checks that `_P1_io_req` is **always** equal to `2'h0` (idle) — a much stronger, incorrect requirement.

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/treearb_4-arbit/main.release_to_idle_p1.fst`

### Key Time Points and Signal Values

| Time (ns) | Cycle | ur1/P1.io_req | P1.procState | P1.randCounter | Assertion (release_to_idle_p1) |
|-----------|-------|---------------|--------------|----------------|-------------------------------|
| 0         | 0     | 2'h0 (idle)   | 2'h0 (idle)  | 3'd0           | 1 (passing)                   |
| 10        | 1     | 2'h0 (idle)   | 2'h0 (idle)  | 3'd1           | 1 (passing)                   |
| 20        | 2     | 2'h0 (idle)   | 2'h0 (idle)  | 3'd2           | 1 (passing)                   |
| 30        | 3     | 2'h0 (idle)   | 2'h0 (idle)  | 3'd3           | 1 (passing)                   |
| 40        | 4     | 2'h0 (idle)   | 2'h0 (idle)  | 3'd4           | 1 (passing)                   |
| 50        | 5     | 2'h0 (idle)   | 2'h0 (idle)  | 3'd5           | 1 (passing)                   |
| 60        | 6     | 2'h0 (idle)   | 2'h0 (idle)  | 3'd6           | 1 (passing)                   |
| 70        | 7     | 2'h0 (idle)   | 2'h0 (idle)  | 3'd7           | 1 (passing)                   |
| **80**    | **8** | **2'h1 (request)** | **2'h1 (request)** | **3'd0** | **0 (FAILING)** |

### Failure Point
- **Time**: 80 ns (cycle 8)
- At time 70 (cycle 7), the procModel sees `procState === idle && randCounter === 7`, so it transitions from idle→request
- At time 80, `procState` becomes `2'h1` (request), which violates the always-idle assertion

## 4. Root Cause Analysis

### Root Cause Category: **assertion_error** (Incorrect Assertion)

### Root Cause
The `assertNextStepWhen(ur1 === HandShakeType.release, ur1 === HandShakeType.idle, "release_to_idle_p1")` call on **arbit.scala:266** generates an incorrect SystemVerilog assertion.

### Bug Description
The `assertNextStepWhen` API is supposed to generate a **temporal** assertion of the form:
> "When the precondition (`ur1 === release`) is true, the postcondition (`ur1 === idle`) must be true in the **next clock cycle**."

However, the generated Verilog assertion is:
```verilog
assert property (@(posedge clock) disable iff (~hasBeenReset) _P1_io_req == 2'h0);
```

This is a **non-temporal** assertion that requires `_P1_io_req` to be `2'h0` (idle) at **all times**. There is no `|=>` (non-overlapping implication) or any temporal operator encoding the "next cycle" semantics.

### Why the Assertion Fails
1. The design's procModel correctly transitions from idle→request→lock→release→idle
2. At cycle 7 (time 70), `randCounter = 7` triggers the idle→request transition in procModel
3. At cycle 8 (time 80), `procState` becomes `2'h1` (request), making `ur1 = 2'h1`
4. The generated assertion requires `ur1 == 2'h0` always, so it fails

### Correct Assertion
The assertion should encode a temporal implication:
```verilog
assert property (@(posedge clock) disable iff (~hasBeenReset) 
    (_P1_io_req == 2'h3) |=> (_P1_io_req == 2'h0));
```
This checks: "When P1's request is in release state (3), then in the next cycle it must be in idle state (0)."

### Design vs. Assertion
- **The design (DUT) is correct**: procModel correctly cycles through idle→request→lock→release→idle
- **The assertion is incorrect**: It enforces `ur1 always == idle`, preventing the procModel from ever requesting a grant
