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

  // ============ FORMAL ASSERTIONS ============

  // Safety 1: Mutual Exclusion
  // The two processes must never be in their critical sections (L4 or L5) simultaneously.
  // This is the primary correctness property of Peterson's algorithm.
  fvAssert(
    !((pc(0) === Loc.L4 || pc(0) === Loc.L5) && (pc(1) === Loc.L4 || pc(1) === Loc.L5)),
    "mutual_exclusion_p0_p1_cs"
  )

  // Safety 2: Protocol invariant - process in critical section must have its interested flag set.
  // A process can only be in CS after setting interested at L1.
  fvAssert(
    !(pc(0) === Loc.L4 || pc(0) === Loc.L5) || interested(0),
    "cs_requires_interested_0"
  )
  fvAssert(
    !(pc(1) === Loc.L4 || pc(1) === Loc.L5) || interested(1),
    "cs_requires_interested_1"
  )

  // Safety 3: Protocol invariant - process not interested should not be past L1.
  // Once a process passes L1, it sets interested, and before L5/L0 it never clears it.
  fvAssert(
    !(pc(0) === Loc.L2 || pc(0) === Loc.L3 || pc(0) === Loc.L4) || interested(0),
    "interested_held_from_l2_to_l4_0"
  )
  fvAssert(
    !(pc(1) === Loc.L2 || pc(1) === Loc.L3 || pc(1) === Loc.L4) || interested(1),
    "interested_held_from_l2_to_l4_1"
  )

  // Safety 4: Turn is set to the other process before entering the entry protocol.
  // At L2, turn is set to ~self (the other process), ensuring fairness in Peterson's algorithm.
  assertImpliesDelay(
    pc(selfIdx) === Loc.L2,
    turn === ~self,
    0,
    "turn_set_to_other_at_l2"
  )

  // Liveness: Bounded progress assertions.
  // When a process is waiting at L3 (entry protocol) with its interested flag set,
  // it should eventually enter the critical section (L4/L5).
  // Bound of 25 cycles accounts for the other process potentially being in CS
  // and needing to exit (L4->L5->L0), plus scheduling delays.
  val inL3_0 = pc(0) === Loc.L3
  val inCS_0 = pc(0) === Loc.L4 || pc(0) === Loc.L5
  astRelaxedLiveness(interested(0) && inL3_0, inCS_0, 25, "progress_entry_cs_0")

  val inL3_1 = pc(1) === Loc.L3
  val inCS_1 = pc(1) === Loc.L4 || pc(1) === Loc.L5
  astRelaxedLiveness(interested(1) && inL3_1, inCS_1, 25, "progress_entry_cs_1")
}

object VerilogGenerator extends App {
  emitVerilog(new peterson(), args)
}
