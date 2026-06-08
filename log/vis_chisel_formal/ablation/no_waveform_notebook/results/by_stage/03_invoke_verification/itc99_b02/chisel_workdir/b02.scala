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

  // ============================================================
  // Formal Verification Assertions
  // ============================================================

  val resetBool = reset.asBool

  // Safety: state register must always hold one of the 7 valid
  // state encodings.  The 3-bit register can reach value 7 (111),
  // which is not a valid state and would cause undefined behavior.
  fvAssert(stato <= StateG, "state_in_valid_range")

  // Safety: after reset deasserts, the next cycle must enter StateB.
  // The FSM is initialized to StateA, which unconditionally
  // transitions to StateB.
  assertNextStepWhen(!resetBool, stato === StateB,
    "reset_transition_to_B")

  // Safety: U_reg must be asserted exactly one cycle after the FSM
  // enters StateE.  In StateE the switch assigns U_reg := true.B and
  // stato := StateB; therefore one cycle later U_reg holds the true
  // value.
  assertImpliesDelay(stato === StateE, U_reg, 1,
    "U_asserted_after_StateE")

  // Liveness: after reset the output U_reg must be asserted within
  // a bounded number of cycles.  The maximum distance from StateA
  // to StateE is 3 cycles (A→B→C→D→E with LINEA=0,0) or 4 cycles
  // (A→B→F→G→E with LINEA=1,0).  We use a generous bound of 15
  // to accommodate the worst-case input sequence while still
  // detecting a deadlocked machine.
  astRelaxedLiveness(!resetBool, U_reg, 15,
    "output_eventually_asserted_after_reset")
}

object VerilogGenerator extends App {
  emitVerilog(new b02(), args)
}