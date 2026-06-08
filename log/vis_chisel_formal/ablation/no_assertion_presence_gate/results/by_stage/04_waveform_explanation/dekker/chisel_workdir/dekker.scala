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

  // ===== Formal Verification Assertions =====

  // Safety: Mutual Exclusion
  // Both processes should never be in the critical section (L5) simultaneously.
  // This is the fundamental property of Dekker's algorithm.
  fvAssert(!(pc(0) === L5 && pc(1) === L5), "mutual_exclusion")

  // Safety: Valid PC range
  // Program counters must always be one of the valid states (L0 through L6).
  fvAssert(pc(0) <= L6 && pc(1) <= L6, "valid_pc_range")

  // Safety: C flag consistency in critical section
  // When a process is in the critical section (L5), its c flag must be false
  // (c=false signals that the process wants/occupies the resource).
  fvAssert(!(pc(0) === L5) || !c(0), "c0_false_in_cs")
  fvAssert(!(pc(1) === L5) || !c(1), "c1_false_in_cs")

  // Safety: Turn consistency
  // turn must always be 0 or 1 (already guaranteed by 1-bit wire width, but explicit).
  fvAssert(turn === 0.U || turn === 1.U, "turn_valid")

  // Bounded Liveness: Process 0 progress
  // When process 0 expresses interest (reaches L1), it should eventually
  // enter the critical section (L5) within a reasonable bound.
  // Bound of 50 cycles accounts for turn-taking and de-scheduling overhead.
  astRelaxedLiveness(pc(0) === L1, pc(0) === L5, 50, "process0_progress_l1_to_cs")

  // Bounded Liveness: Process 1 progress
  // Same guarantee for process 1.
  astRelaxedLiveness(pc(1) === L1, pc(1) === L5, 50, "process1_progress_l1_to_cs")
}

object VerilogGenerator extends App {
  emitVerilog(new dekker(), args)
}
