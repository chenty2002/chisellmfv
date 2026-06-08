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
        pc(selfIdx) := Loc.L5
      }
    }
    is(Loc.L5) {
      interested(selfIdx) := false.B
      pc(selfIdx) := Loc.L0
    }
  }

  // ========== FORMAL VERIFICATION ASSERTIONS ==========

  // === Safety: Mutual Exclusion ===
  // The two processes must never be in the critical section (L4) simultaneously.
  // This is the most critical property of Peterson's algorithm.
  fvAssert(!(pc(0) === Loc.L4 && pc(1) === Loc.L4), "mutual_exclusion")

  // === Safety: State Consistency ===
  // A process can only be at the entry protocol (L3) if it is interested.
  assertImplies(pc(0) === Loc.L3, interested(0), "L3_implies_interested_p0")
  assertImplies(pc(1) === Loc.L3, interested(1), "L3_implies_interested_p1")

  // A process can only be in the critical section (L4) if it is interested.
  assertImplies(pc(0) === Loc.L4, interested(0), "L4_implies_interested_p0")
  assertImplies(pc(1) === Loc.L4, interested(1), "L4_implies_interested_p1")

  // A process can only be in the exit section (L5) if it is interested.
  assertImplies(pc(0) === Loc.L5, interested(0), "L5_implies_interested_p0")
  assertImplies(pc(1) === Loc.L5, interested(1), "L5_implies_interested_p1")

  // === Bounded Liveness: Progress into Critical Section ===
  // When a process is waiting at the entry protocol (L3), it must eventually
  // reach the critical section (L4) within 15 cycles.
  // Peterson's algorithm is starvation-free: if a process is waiting,
  // the other can hold the CS for at most one pass (L4→L5→L0 = 2-3 cycles)
  // before turn flips and the waiting process enters.
  astRelaxedLiveness(pc(0) === Loc.L3, pc(0) === Loc.L4, 15, "liveness_p0_entry")
  astRelaxedLiveness(pc(1) === Loc.L3, pc(1) === Loc.L4, 15, "liveness_p1_entry")

  // === Entry Protocol Correctness ===
  // If a process is at L3 and the other process is not interested,
  // the process must reach L4 in the very next cycle, because the
  // condition (!interested(otherIdx) || turn === self) is guaranteed true.
  assertImpliesDelay((pc(0) === Loc.L3) && !interested(1), pc(0) === Loc.L4, 1, "L3_to_L4_when_other_not_interested_p0")
  assertImpliesDelay((pc(1) === Loc.L3) && !interested(0), pc(1) === Loc.L4, 1, "L3_to_L4_when_other_not_interested_p1")
}

object VerilogGenerator extends App {
  emitVerilog(new peterson(), args)
}
