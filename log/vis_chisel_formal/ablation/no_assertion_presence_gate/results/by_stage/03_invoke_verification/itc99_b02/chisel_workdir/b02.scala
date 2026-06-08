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

  // ========== FORMAL ASSERTIONS ==========

  // SAFETY 1: State encoding must remain valid — never use the unused code 7
  fvAssert(stato =/= 7.U, "state_valid")

  // SAFETY 2: Output U_reg is only asserted in the cycle immediately after StateE
  // (U_reg is a registered output: set true in StateE, appears next cycle;
  //  all other states clear it, so it is true for exactly one cycle following StateE)
  fvAssert(!U_reg || RegNext(stato) === StateE, "output_only_after_stateE")

  // SAFETY 3: FSM always makes progress — the state must change every cycle
  // (every state has a non-self transition; RegNext(stato) always differs)
  fvAssert(stato =/= RegNext(stato), "state_changes_every_cycle")

  // TRANSITION 1: From StateA, next state must be StateB (unconditional)
  assertNextStepWhen(stato === StateA, stato === StateB, "from_A_to_B")

  // TRANSITION 2: From StateD, next state must be StateE (unconditional)
  assertNextStepWhen(stato === StateD, stato === StateE, "from_D_to_E")

  // TRANSITION 3: From StateE, next state must be StateB (unconditional)
  assertNextStepWhen(stato === StateE, stato === StateB, "from_E_to_B")

  // TRANSITION 4: From StateF, next state must be StateG (unconditional)
  assertNextStepWhen(stato === StateF, stato === StateG, "from_F_to_G")

  // TRANSITION 5: From StateB, LINEA=0 ⇒ next state is StateC
  assertImpliesDelay(stato === StateB && !io.LINEA, stato === StateC, 1, "from_B_to_C")

  // TRANSITION 6: From StateB, LINEA=1 ⇒ next state is StateF
  assertImpliesDelay(stato === StateB && io.LINEA,   stato === StateF, 1, "from_B_to_F")

  // TRANSITION 7: From StateG, LINEA=0 ⇒ next state is StateE
  assertImpliesDelay(stato === StateG && !io.LINEA, stato === StateE, 1, "from_G_to_E")

  // TRANSITION 8: From StateG, LINEA=1 ⇒ next state is StateA
  assertImpliesDelay(stato === StateG && io.LINEA,   stato === StateA, 1, "from_G_to_A")

  // BOUNDED LIVENESS 1: When in StateC with LINEA=0, the FSM must reach
  // StateE (output-asserting state) within 3 cycles (path: C → D → E).
  astRelaxedLiveness(stato === StateC && !io.LINEA, stato === StateE, 3, "from_C_to_E")

  // BOUNDED LIVENESS 2: When in StateG with LINEA=0, the FSM must reach
  // StateE within 2 cycles (path: G → E).
  astRelaxedLiveness(stato === StateG && !io.LINEA, stato === StateE, 2, "from_G_to_E")
}

object VerilogGenerator extends App {
  emitVerilog(new b02(), args)
}
