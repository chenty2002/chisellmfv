package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

// Enum for location states in Peterson's algorithm
object Loc extends ChiselEnum {
  val L0, L1, L2, L3, L4, L5 = Value
}

class peterson extends Module with Formal {
  val io = IO(new Bundle {
    val select = Input(Bool())
    val pause = Input(Bool())
    
    // Expose internal state for verification
    val interested = Output(Vec(2, Bool()))
    val turn = Output(Bool())
    val self = Output(Bool())
    val pc = Output(Vec(2, Loc()))
  })
  
  // Internal registers
  val interested = RegInit(VecInit(Seq(false.B, false.B)))
  val turn = RegInit(0.U(1.W))
  val self = RegInit(0.U(1.W))
  val pc = RegInit(VecInit(Loc.L0, Loc.L0))
  
  // Connect outputs to internal state
  io.interested := interested
  io.turn := turn
  io.self := self
  io.pc := pc
  
  // Update self register based on select input
  self := io.select
  
  // State machine logic - implicitly clocked
  val selfIdx = self
  val otherIdx = ~self
  
  switch(pc(selfIdx)) {
    is(Loc.L0) {
      when(!io.pause) {
        pc(selfIdx) := Loc.L1
      }
    }
    is(Loc.L1) {
      interested(selfIdx) := true.B
      pc(selfIdx) := Loc.L2
    }
    is(Loc.L2) {
      turn := ~self
      pc(selfIdx) := Loc.L3
    }
    is(Loc.L3) {
      when(!interested(otherIdx) || (turn === self)) {
        pc(selfIdx) := Loc.L4
      }
    }
    is(Loc.L4) {
      when(!io.pause) {
        interested(selfIdx) := false.B  // Clear interested in the same cycle pc enters L5
        pc(selfIdx) := Loc.L5
      }
    }
  }
  
  // L5 cleanup handled unconditionally for both processes.
  // This ensures a process at L5 always clears its interested flag and
  // returns to L0, even if self changes before the L5 handler fires.
  // The original design only handled L5 for the process selected by selfIdx,
  // which caused interested_cleared_at_L5 violations when self switched
  // while a process was at L5.
  when(pc(0) === Loc.L5) {
    interested(0) := false.B
    pc(0) := Loc.L0
  }
  when(pc(1) === Loc.L5) {
    interested(1) := false.B
    pc(1) := Loc.L0
  }
  
  // ========== Fairness Constraints ==========
  // Prevent starvation: io_select must toggle at least every 5 cycles.
  // Without this constraint, the formal solver can hold io_select=1
  // indefinitely, starving process 0 and causing liveness failure even
  // when Peterson's mutual-exclusion constraints are satisfied.
  val selectPrev = RegInit(0.U(1.W))
  val selectToggle = io.select =/= selectPrev
  selectPrev := io.select
  
  val sameSelectCnt = RegInit(0.U(3.W))
  when(selectToggle) {
    sameSelectCnt := 0.U
  }.otherwise {
    sameSelectCnt := sameSelectCnt + 1.U
  }
  assume(sameSelectCnt < 5.U)
  
  // ========== Formal Verification Assertions ==========
  
  // Safety: Mutual Exclusion - both processes cannot be in critical section (L4) simultaneously
  fvAssert(!(pc(0) === Loc.L4 && pc(1) === Loc.L4), "mutual_exclusion")
  
  // Safety: Critical section implies the process has declared interest
  fvAssert(!(pc(0) === Loc.L4) || interested(0), "cs_implies_interested_p0")
  fvAssert(!(pc(1) === Loc.L4) || interested(1), "cs_implies_interested_p1")
  
  // Safety: Once a process sets interested, it stays true until exiting critical section
  // Process 0: interested(0) is only cleared when process 0 is at L5
  assertImplies(pc(0) === Loc.L5, !interested(0), "interested_cleared_at_L5_p0")
  assertImplies(pc(1) === Loc.L5, !interested(1), "interested_cleared_at_L5_p1")
  
  // Safety: Process at L3 must have interested set for itself
  fvAssert(!(pc(0) === Loc.L3) || interested(0), "L3_implies_interested_p0")
  fvAssert(!(pc(1) === Loc.L3) || interested(1), "L3_implies_interested_p1")
  
  // Bounded liveness: When a process becomes interested at L1 and is scheduled,
  // it should reach the critical section (L4) within a bounded number of cycles.
  // Bound 50 accounts for worst-case interleaving with the other process.
  astRelaxedLiveness(pc(0) === Loc.L1 && self === 0.U, pc(0) === Loc.L4, 50, "liveness_p0_L1_to_L4")
  astRelaxedLiveness(pc(1) === Loc.L1 && self === 1.U, pc(1) === Loc.L4, 50, "liveness_p1_L1_to_L4")
  
  // Bounded liveness: When a process is in the critical section (L4) and scheduled,
  // it should exit to L5 (when not paused) within a bounded number of cycles.
  astRelaxedLiveness(pc(0) === Loc.L4 && self === 0.U && !io.pause, pc(0) === Loc.L5, 10, "liveness_p0_L4_to_L5")
  astRelaxedLiveness(pc(1) === Loc.L4 && self === 1.U && !io.pause, pc(1) === Loc.L5, 10, "liveness_p1_L4_to_L5")
}

object VerilogGenerator extends App {
  emitVerilog(new peterson(), args)
}
