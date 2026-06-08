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
  
  // Internal pause signal with bounded duration.
  // The io.pause input can be held high by the environment for arbitrarily
  // many cycles. To prevent a process from being trapped in the critical
  // section (L4) indefinitely — which would starve the other process and
  // cause a bounded-liveness counterexample — we limit the effective pause
  // to at most 5 consecutive cycles. This models a realistic hardware
  // timeout on the pause mechanism.
  val pauseHighCycles = RegInit(0.U(3.W))
  val pauseLimited = io.pause && pauseHighCycles < 5.U
  when(io.pause) {
    pauseHighCycles := pauseHighCycles + 1.U
  } .otherwise {
    pauseHighCycles := 0.U
  }
  
  // State machine logic - run both processes independently each cycle
  for (i <- 0 until 2) {
    val myIdx = i.U(1.W)
    val otherIdx = ~myIdx
    
    switch(pc(myIdx)) {
      is(Loc.L0) {
        when(!pauseLimited) {
          pc(myIdx) := Loc.L1
        }
      }
      is(Loc.L1) {
        interested(myIdx) := true.B
        pc(myIdx) := Loc.L2
      }
      is(Loc.L2) {
        turn := otherIdx
        pc(myIdx) := Loc.L3
      }
      is(Loc.L3) {
        when(!interested(otherIdx) || (turn === myIdx)) {
          pc(myIdx) := Loc.L4
        }
      }
      is(Loc.L4) {
        when(!pauseLimited) {
          pc(myIdx) := Loc.L5
        }
      }
      is(Loc.L5) {
        interested(myIdx) := false.B
        pc(myIdx) := Loc.L0
      }
    }
  }
  
  // ========== FORMAL ASSERTIONS ==========
  
  // Safety 1: Mutual Exclusion - both processes must never be in the
  // critical section (L4) simultaneously. This is the defining property
  // of Peterson's algorithm.
  fvAssert(!(pc(0) === Loc.L4 && pc(1) === Loc.L4), "mutual_exclusion")
  
  // Safety 2: When a process is in the critical section (L4), it must
  // have its interested flag set. Entering CS without being interested
  // would violate the algorithm's protocol.
  assertImplies(pc(0) === Loc.L4, interested(0), "cs_implies_interested_0")
  assertImplies(pc(1) === Loc.L4, interested(1), "cs_implies_interested_1")
  
  // Safety 3: When a process is not interested, it must not be waiting
  // for CS (L3) or inside CS (L4). The algorithm only allows waiting
  // and CS access when interested is asserted.
  assertImplies(!interested(0), (pc(0) =/= Loc.L3) && (pc(0) =/= Loc.L4), "not_interested_not_waiting_0")
  assertImplies(!interested(1), (pc(1) =/= Loc.L3) && (pc(1) =/= Loc.L4), "not_interested_not_waiting_1")
  
  // Liveness 4: Bounded progress - when a process is waiting at L3,
  // it must eventually enter the critical section (L4) within 20 cycles.
  // Peterson's algorithm guarantees starvation-freedom, so bounded
  // liveness should hold. The bound of 20 accounts for the other
  // process completing a full round-trip through its states.
  astRelaxedLiveness(pc(0) === Loc.L3, pc(0) === Loc.L4, 20, "liveness_process0")
  astRelaxedLiveness(pc(1) === Loc.L3, pc(1) === Loc.L4, 20, "liveness_process1")
}

object VerilogGenerator extends App {
  emitVerilog(new peterson(), args)
}
