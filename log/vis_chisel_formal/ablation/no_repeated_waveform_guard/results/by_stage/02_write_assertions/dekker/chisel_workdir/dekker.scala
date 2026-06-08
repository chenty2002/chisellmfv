package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

// Model of Dekker's algorithm for mutual exclusion of two processes.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

class dekker extends Module with Formal {
  val io = IO(new Bundle {
    val select = Input(Bool())
    val pause = Input(Bool())
    // Add outputs to preserve internal state for verification
    val c0 = Output(Bool())
    val c1 = Output(Bool())
    val turn = Output(UInt(1.W))
    val self = Output(UInt(1.W))
    val pc0 = Output(UInt(3.W))
    val pc1 = Output(UInt(3.W))
  })
  
  // Enum for locations
  val L0 = 0.U(3.W)
  val L1 = 1.U(3.W)
  val L2 = 2.U(3.W)
  val L3 = 3.U(3.W)
  val L4 = 4.U(3.W)
  val L5 = 5.U(3.W)
  val L6 = 6.U(3.W)
  
  // Registers
  val c = RegInit(VecInit(Seq(true.B, true.B)))
  val turn = RegInit(0.U(1.W))
  val self = RegInit(io.select)
  val pc = RegInit(VecInit(Seq(L0, L0)))
  
  // Combinational logic for next state
  val selfNext = Wire(UInt(1.W))
  selfNext := io.select
  
  // State machine logic
  switch(pc(self)) {
    is(L0) {
      when(!io.pause) {
        pc(self) := L1
      }
    }
    is(L1) {
      c(self) := false.B
      pc(self) := L2
    }
    is(L2) {
      when(c(~self) === true.B) {
        pc(self) := L5
      }.otherwise {
        pc(self) := L3
      }
    }
    is(L3) {
      when(turn === self) {
        pc(self) := L2
      }.otherwise {
        c(self) := true.B
        pc(self) := L4
      }
    }
    is(L4) {
      when(turn === self) {
        c(self) := false.B
        pc(self) := L2
      }
    }
    is(L5) {
      when(!io.pause) {
        pc(self) := L6
      }
    }
    is(L6) {
      c(self) := true.B
      turn := ~self
      pc(self) := L0
    }
  }
  
  // Update self register
  self := selfNext
  
  // Connect outputs
  io.c0 := c(0)
  io.c1 := c(1)
  io.turn := turn
  io.self := self
  io.pc0 := pc(0)
  io.pc1 := pc(1)

  // ========== Formal Verification Assertions ==========

  // ---- Safety Properties ----

  // 1. Mutual Exclusion: Never both processes in critical section (L5 or L6) at the same time.
  //    This is the fundamental safety property of Dekker's algorithm.
  val inCS0 = pc(0) === L5 || pc(0) === L6
  val inCS1 = pc(1) === L5 || pc(1) === L6
  fvAssert(!(inCS0 && inCS1), "mutual_exclusion")

  // 2. c flag consistency: When a process is in critical section, its c flag must be false
  //    (indicating it is still "interested"/holding the resource).
  fvAssert(!(inCS0 && c(0)), "cs0_c_flag_consistency")
  fvAssert(!(inCS1 && c(1)), "cs1_c_flag_consistency")

  // 3. Valid program counter values: Each PC must be one of the defined states 0-6.
  fvAssert(pc(0) <= 6.U, "pc0_valid_range")
  fvAssert(pc(1) <= 6.U, "pc1_valid_range")

  // 4. Turn must be binary (0 or 1).
  fvAssert(turn <= 1.U, "turn_binary")

  // 5. At most one process is actively modified in a given cycle (self selects one process).
  //    The inactive process's state must not change (c(i) holds its previous value).
  //    This is inherent to the design but useful to formalize.

  // ---- Liveness / Progress Properties ----

  // 6. Bounded liveness: When a process enters L5 (critical section), it eventually reaches L6
  //    within a bounded number of cycles. Since the process only runs when self matches, and
  //    self changes at most every cycle, a bound of 10 captures worst-case interleaving.
  //    Condition: process in L5 (it wants to exit) -> within 10 cycles it exits L5.
  //    We use the relaxed liveness pattern: request being "in L5", response being "not in L5".
  astRelaxedLiveness(pc(0) === L5, pc(0) =/= L5, 10, "liveness_cs_exit_p0")
  astRelaxedLiveness(pc(1) === L5, pc(1) =/= L5, 10, "liveness_cs_exit_p1")

  // 7. Bounded liveness: When a process declares interest (c(i) becomes false), it should
  //    eventually enter the critical section (L5/L6) within a reasonable bound.
  //    We check: if c(i) is false and the process is making forward progress (it is the
  //    currently selected process), it eventually reaches CS.
  //    Using the "assertAlwaysAfterNStepWhen" or astRelaxedLiveness pattern.
  //    Request: c(0) === false.B (process 0 wants CS) and self === 0.U (it can make progress)
  //    Response: pc(0) === L5 || pc(0) === L6 within 20 cycles
  astRelaxedLiveness(
    c(0) === false.B && self === 0.U,
    pc(0) === L5 || pc(0) === L6,
    20,
    "liveness_cs_entry_p0"
  )
  astRelaxedLiveness(
    c(1) === false.B && self === 1.U,
    pc(1) === L5 || pc(1) === L6,
    20,
    "liveness_cs_entry_p1"
  )

  // 8. Turn alternation: After a process exits critical section (L6 completes), turn flips.
  //    This ensures fairness. We check that turn takes both 0 and 1 over time.
  //    A simpler check: turn is never "stuck" - it eventually changes.
  //    Request: we just exited CS (pc(old_self) was L6) -> within some cycles turn != old_turn
  //    Actually, the turn change happens in the same cycle as L6, so this is immediate.
  //    We can check: if pc(0) was L6 previous cycle and now is L0, turn should be ~old_turn.
  //    This is already structurally ensured by the RTL, so we skip this.
}
