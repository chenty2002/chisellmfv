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

  // ===== Formal Verification Assertions =====

  // Safety: Output U_reg is a single-cycle pulse -- never true for two consecutive cycles
  fvAssert(!(U_reg && RegNext(U_reg)), "U_single_cycle_pulse")

  // Safety: State register must always hold a valid state encoding (0 through 6)
  fvAssert(stato <= 6.U, "valid_state_encoding")

  // Safety: When U_reg is asserted, the previous state must have been StateE.
  // U_reg is only set to true in the StateE case, and it takes effect the
  // following cycle as the FSM transitions to StateB.
  fvAssert(!U_reg || RegNext(stato) === StateE, "U_causal_from_StateE")

  // Safety: From StateG, when LINEA is false, the next state is unconditionally StateE.
  fvAssert(!(stato === StateG && io.LINEA === false.B) || RegNext(stato) === StateE,
    "StateG_to_StateE_when_LINEA0")

  // Bounded liveness: After reaching StateG with LINEA=0, the FSM is on a
  // deterministic path to assert U_reg within 2 cycles:
  //   Cycle 0: stato=StateG, LINEA=0
  //   Cycle 1: stato=StateE (U_reg scheduled to become true)
  //   Cycle 2: U_reg=true (stato=StateB)
  astRelaxedLiveness(stato === StateG && io.LINEA === false.B, U_reg, 2,
    "StateG_LINEA0_leads_to_U_within_2")

  // Bounded liveness: From StateD the FSM reaches StateE in exactly 1 cycle,
  // and U_reg is asserted within 2 cycles.
  astRelaxedLiveness(stato === StateD, U_reg, 2,
    "StateD_leads_to_U_within_2")
}

object VerilogGenerator extends App {
  emitVerilog(new b02(), args)
}
