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

  // Safety 1: State register must never enter the reserved/invalid state (7)
  fvAssert(stato =/= 7.U(3.W), "no_invalid_state")

  // Safety 2: Output should only be high when the FSM is in StateE
  fvAssert(!U_reg || stato === StateE, "output_only_high_in_state_E")

  // Safety 3: State transition correctness - unconditional jumps

  // From StateA, next state must be StateB
  assertImpliesDelay(stato === StateA, stato === StateB, 1, "from_A_goes_to_B")

  // From StateD, next state must be StateE
  assertImpliesDelay(stato === StateD, stato === StateE, 1, "from_D_goes_to_E")

  // From StateE, next state must be StateB
  assertImpliesDelay(stato === StateE, stato === StateB, 1, "from_E_goes_to_B")

  // From StateF, next state must be StateG
  assertImpliesDelay(stato === StateF, stato === StateG, 1, "from_F_goes_to_G")

  // Safety 4: State transition correctness - conditional jumps

  // From StateB with LINEA=0, next state is StateC
  assertImpliesDelay(stato === StateB && io.LINEA === false.B, stato === StateC, 1, "from_B_LINEA0_goes_to_C")

  // From StateB with LINEA=1, next state is StateF
  assertImpliesDelay(stato === StateB && io.LINEA === true.B, stato === StateF, 1, "from_B_LINEA1_goes_to_F")

  // From StateC with LINEA=0, next state is StateD
  assertImpliesDelay(stato === StateC && io.LINEA === false.B, stato === StateD, 1, "from_C_LINEA0_goes_to_D")

  // From StateC with LINEA=1, next state is StateG
  assertImpliesDelay(stato === StateC && io.LINEA === true.B, stato === StateG, 1, "from_C_LINEA1_goes_to_G")

  // From StateG with LINEA=0, next state is StateE
  assertImpliesDelay(stato === StateG && io.LINEA === false.B, stato === StateE, 1, "from_G_LINEA0_goes_to_E")

  // From StateG with LINEA=1, next state is StateA
  assertImpliesDelay(stato === StateG && io.LINEA === true.B, stato === StateA, 1, "from_G_LINEA1_goes_to_A")

  // Safety 5: Mutex - at most one state should be active (they are mutually exclusive by encoding, but this checks for encoding bugs)
  assertOneHot0(stato, "state_one_hot0")
}

object VerilogGenerator extends App {
  emitVerilog(new b02(), args)
}
