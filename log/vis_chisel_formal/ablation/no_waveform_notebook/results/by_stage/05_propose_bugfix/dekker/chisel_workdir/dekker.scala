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
        c(self) := true.B  // Restore c-flag before exiting critical section
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
  
  // ===== Formal Verification Assertions =====
  
  // Safety 1: Mutual Exclusion - the fundamental property of Dekker's algorithm.
  // Both processes must never be in the critical section (L5) simultaneously.
  fvAssert(!(pc(0) === L5 && pc(1) === L5), "mutual_exclusion")
  
  // Safety 2: c-flag consistency in critical section.
  // When a process is in L5 (critical section), its c flag must be false
  // (indicating the process wants to enter / is in the critical section).
  fvAssert(!(pc(0) === L5) || !c(0), "c0_false_in_cs")
  fvAssert(!(pc(1) === L5) || !c(1), "c1_false_in_cs")
  
  // Safety 3: Valid program counter range.
  // Both program counters must be within the defined state values (0 through 6).
  fvAssert(pc(0) <= L6, "pc0_valid_range")
  fvAssert(pc(1) <= L6, "pc1_valid_range")
  
  // Safety 4: c-flag consistency when process is not contending.
  // When a process is at L0 (idle) or L6 (just exited CS), its c flag must be true
  // (indicating it does NOT want to enter the critical section).
  fvAssert(!(pc(0) === L0 || pc(0) === L6) || c(0), "c0_true_when_idle_or_exit")
  fvAssert(!(pc(1) === L0 || pc(1) === L6) || c(1), "c1_true_when_idle_or_exit")
  
  // Bounded Liveness 1: Critical section exit progress.
  // When the selected process is in L5 and not paused, it must advance to L6
  // in the very next cycle.
  // Note: Using RegNext-based next-cycle checking instead of assertNextStepWhen
  // to ensure proper next-cycle implication semantics (|=>) rather than same-cycle (|->).
  val proc0_cs_exit_cond = RegNext(self === 0.U && pc(0) === L5 && !io.pause)
  fvAssert(!proc0_cs_exit_cond || pc(0) === L6, "proc0_cs_exit_progress")
  val proc1_cs_exit_cond = RegNext(self === 1.U && pc(1) === L5 && !io.pause)
  fvAssert(!proc1_cs_exit_cond || pc(1) === L6, "proc1_cs_exit_progress")
  
  // Bounded Liveness 2: Direct entry to critical section.
  // When the selected process is at L2 and the other process does not want to
  // enter (c(~self) is true), it must reach L5 in the very next cycle.
  val proc0_l2_l5_cond = RegNext(self === 0.U && pc(0) === L2 && c(1))
  fvAssert(!proc0_l2_l5_cond || pc(0) === L5, "proc0_l2_to_l5_when_c1_true")
  val proc1_l2_l5_cond = RegNext(self === 1.U && pc(1) === L2 && c(0))
  fvAssert(!proc1_l2_l5_cond || pc(1) === L5, "proc1_l2_to_l5_when_c0_true")
}

object VerilogGenerator extends App {
  emitVerilog(new dekker(), args)
}
