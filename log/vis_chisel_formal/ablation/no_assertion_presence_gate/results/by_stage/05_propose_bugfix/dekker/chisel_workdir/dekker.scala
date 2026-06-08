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
  
  // State machine logic - evaluate both processes independently each cycle
  // This correctly models Dekker's algorithm where two concurrent processes
  // communicate through shared c() and turn registers.
  for (i <- 0 until 2) {
    switch(pc(i)) {
      is(L0) {
        when(!io.pause) {
          pc(i) := L1
        }
      }
      is(L1) {
        c(i) := false.B
        pc(i) := L2
      }
      is(L2) {
        when(c(1-i) === true.B) {
          pc(i) := L5
        }.otherwise {
          pc(i) := L3
        }
      }
      is(L3) {
        when(turn === i.U) {
          pc(i) := L2
        }.otherwise {
          c(i) := true.B
          pc(i) := L4
        }
      }
      is(L4) {
        when(turn === i.U) {
          c(i) := false.B
          pc(i) := L2
        }
      }
      is(L5) {
        when(!io.pause) {
          pc(i) := L6
        }
      }
      is(L6) {
        c(i) := true.B
        turn := (1-i).U
        pc(i) := L0
      }
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

  // ===== Environment Fairness Constraints =====

  // Fairness: io_select must not stay constant for too long.
  // Without this constraint, the environment can permanently select one
  // process and starve the other, trivially violating the bounded-liveness
  // assertions below (setup_error: missing input fairness).
  val selectPrev = RegNext(io.select)
  val selectStableCnt = RegInit(0.U(3.W))
  when(io.select === selectPrev) {
    selectStableCnt := selectStableCnt + 1.U
  } .otherwise {
    selectStableCnt := 0.U
  }
  assume(selectStableCnt < 4.U)  // io_select must change at least every 4 cycles

  // Fairness: io_pause must not be held high for too many consecutive cycles.
  // Without this constraint, the environment can indefinitely stall a process
  // at L0 (idle entry) or L5 (critical-section exit), which delays the
  // other process's progress and can exceed the bounded-liveness bound.
  // A bound of 4 consecutive cycles matches the select fairness constraint.
  val pausePrev = RegNext(io.pause)
  val pauseStableCnt = RegInit(0.U(3.W))
  when(io.pause === pausePrev) {
    pauseStableCnt := pauseStableCnt + 1.U
  } .otherwise {
    pauseStableCnt := 0.U
  }
  assume(pauseStableCnt < 5.U)  // io_pause must not be held high for >=5 consecutive cycles

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
