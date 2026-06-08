package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

// Verilog translation of the original b02 circuit from the ITC99
// benchmark set.

// Author: Fabio Somenzi <Fabio@Colorado.EDU>

class b02 extends Module with Formal {
  val io = IO(new Bundle {
    val LINEA = Input(Bool())
    val U = Output(Bool())
  })
  
  // State enumeration using UInt
  val StateA = 0.U(3.W)
  val StateB = 1.U(3.W)
  val StateC = 2.U(3.W)
  val StateD = 3.U(3.W)
  val StateE = 4.U(3.W)
  val StateF = 5.U(3.W)
  val StateG = 6.U(3.W)
  
  // State register with initial state A
  val stato = RegInit(StateA)
  
  // Output register U with initial value 0
  val U_reg = RegInit(false.B)
  io.U := U_reg
  
  // State machine logic
  switch(stato) {
    is(StateA) {
      stato := StateB
      U_reg := false.B
    }
    is(StateB) {
      when(io.LINEA === false.B) {
        stato := StateC
      }.otherwise {
        stato := StateF
      }
      U_reg := false.B
    }
    is(StateC) {
      when(io.LINEA === false.B) {
        stato := StateD
      }.otherwise {
        stato := StateG
      }
      U_reg := false.B
    }
    is(StateD) {
      stato := StateE
      U_reg := false.B
    }
    is(StateE) {
      stato := StateB
      U_reg := true.B
    }
    is(StateF) {
      stato := StateG
      U_reg := false.B
    }
    is(StateG) {
      when(io.LINEA === false.B) {
        stato := StateE
      }.otherwise {
        stato := StateA
      }
      U_reg := false.B
    }
  }

  // ========== Formal Verification Assertions ==========

  // Safety: The 3-bit state register should never hold the illegal value 7 (StateH),
  // since only 7 valid states (0-6) are defined.
  fvAssert(stato <= 6.U, "stato must be a valid state (0-6)")

  // Safety: U_reg is asserted only during the transition from StateE to StateB.
  // When U_reg is true, the current state after the transition must be StateB.
  fvAssert(!U_reg || stato === StateB, "U_reg=1 implies stato is StateB")

  // Liveness: The FSM must always make forward progress — the state should change
  // every cycle. All states have defined unconditional or combinational next-state
  // logic, so no state should persist for more than one cycle.
  fvAssert(stato =/= RegNext(stato), "state must change every cycle (no deadlock)")

  // Bounded liveness: After reset, the FSM should assert U=1 within a bounded
  // number of cycles. The longest non-looping path from reset (StateA) to StateE
  // is 4 cycles (A->B->C->D->E or A->B->F->G->E).  We use a relaxed bound of 8
  // to cover cases where LINEA causes short loops before reaching StateE.
  astRelaxedLiveness(!reset.asBool, U_reg, 8, "U_reg should assert within 8 cycles after reset")
}

object VerilogGenerator extends App {
  emitVerilog(new b02(), args)
}
