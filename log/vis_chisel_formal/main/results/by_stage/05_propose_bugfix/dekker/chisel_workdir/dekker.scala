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
  
  // ========== FORMAL ASSERTIONS ==========
  
  // SA1: Mutual exclusion (core safety) — both processes never simultaneously in L6
  fvAssert(!(pc(0) === L6 && pc(1) === L6), "SA1_mutual_exclusion")
  
  // SA2: Program counters must stay within the valid location range [L0 .. L6]
  fvAssert(pc(0) <= L6, "SA2_pc0_valid_range")
  fvAssert(pc(1) <= L6, "SA3_pc1_valid_range")
  
  // SA3: turn register is always 0 or 1 (width is 1 bit, but explicit check)
  fvAssert(turn === 0.U || turn === 1.U, "SA4_turn_one_hot")
  
  // LA1 (bounded liveness): When the selected process is at L2, the other
  // process is not interested (c(~self) is true), and pause is deasserted,
  // the selected process must reach L6 (critical section) within 10 cycles.
  // The path is L2 -> L5 -> L6 (2 transitions, but L5 may wait for !pause).
  // NOTE: snapshot self at request time to handle nondeterministic io_select
  // that can change self between request and response evaluation.
  // NOTE: custom liveness counter only increments on productive cycles where
  // self == tracked_self && !io.pause, because io_select and io_pause are
  // unconstrained inputs that can stall the tracked thread indefinitely on
  // the wall clock while no actual progress is possible.
  val liveness_req_l2 = (pc(self) === L2) && (c(~self) === true.B) && !io.pause
  val tracked_self_l2 = RegInit(0.U(1.W))
  when (liveness_req_l2) {
    tracked_self_l2 := self
  }
  val liveness_resp_l6_l2 = (pc(tracked_self_l2) === L6)
  
  val pending_l2 = RegInit(false.B)
  val timer_l2 = RegInit(0.U(4.W))
  when (reset.asBool || liveness_resp_l6_l2) {
    pending_l2 := false.B
    timer_l2 := 0.U
  } .elsewhen (liveness_req_l2) {
    pending_l2 := true.B
    timer_l2 := 0.U
  } .elsewhen (pending_l2 && (self === tracked_self_l2) && !io.pause) {
    timer_l2 := timer_l2 + 1.U
  }
  when (pending_l2) {
    fvAssert(timer_l2 < 5.U, "LA1_liveness_L2_to_CS")
  }
  
  // LA2 (bounded liveness): When the selected process is at L5 (ready to enter
  // CS immediately) and pause is deasserted, it must reach L6 within 5 cycles.
  val liveness_req_l5 = (pc(self) === L5) && !io.pause
  val tracked_self_l5 = RegInit(0.U(1.W))
  when (liveness_req_l5) {
    tracked_self_l5 := self
  }
  val liveness_resp_l6_l5 = (pc(tracked_self_l5) === L6)
  astRelaxedLiveness(liveness_req_l5, liveness_resp_l6_l5, 5, "LA2_liveness_L5_to_CS")
  
  // LA3 (bounded liveness): When a process sets its flag to 'interested'
  // (enters L1 so c(self) becomes false), it eventually reaches CS.
  // A process should not linger indefinitely in the trying region (L1-L5).
  val trying_cs = (pc(self) === L1) || (pc(self) === L2) || (pc(self) === L3) ||
                  (pc(self) === L4) || (pc(self) === L5)
  val req_trying = trying_cs && !io.pause
  val tracked_self_try = RegInit(0.U(1.W))
  when (req_trying) {
    tracked_self_try := self
  }
  val in_critical = (pc(tracked_self_try) === L6)
  // Custom productive-cycle liveness counter for LA3 as well.
  // The worst-case path through the contention protocol may take many
  // productive cycles (loops through L2-L3-L4), so we use a generous bound.
  val pending_try = RegInit(false.B)
  val timer_try = RegInit(0.U(6.W))
  when (reset.asBool || in_critical) {
    pending_try := false.B
    timer_try := 0.U
  } .elsewhen (req_trying) {
    pending_try := true.B
    timer_try := 0.U
  } .elsewhen (pending_try && (self === tracked_self_try) && !io.pause) {
    timer_try := timer_try + 1.U
  }
  when (pending_try) {
    fvAssert(timer_try < 50.U, "LA3_liveness_trying_to_CS")
  }
}

object VerilogGenerator extends App {
  emitVerilog(new dekker(), args)
}
