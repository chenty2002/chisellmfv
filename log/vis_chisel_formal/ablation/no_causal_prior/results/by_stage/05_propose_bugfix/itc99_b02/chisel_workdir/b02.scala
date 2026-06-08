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
  
  // State enumeration using one-hot encoding (7 states → 7 bits)
  val StateA = "b0000001".U(7.W)
  val StateB = "b0000010".U(7.W)
  val StateC = "b0000100".U(7.W)
  val StateD = "b0001000".U(7.W)
  val StateE = "b0010000".U(7.W)
  val StateF = "b0100000".U(7.W)
  val StateG = "b1000000".U(7.W)
  
  // State register with initial state A
  val stato = RegInit(StateA)
  
  // Output: combinatorial assignment based on current state
  // (fixes race condition where registered U_reg stayed high one cycle after leaving StateE)
  io.U := (stato === StateE)
  
  // Previous-cycle values for transition assertions
  val prevStato = RegNext(stato)
  val prevLinea = RegNext(io.LINEA)
  
  // First-cycle guard: the hasBeenResetReg X-initialization (1'bx) in the formal
  // wrapper allows assertions to fire at cycle 0 before any reset or transition has
  // occurred.  At cycle 0 both prevStato and stato hold their initial values (both
  // StateA in the found counterexample), so assertions that check a transition from
  // StateA → StateB spuriously fail.  This register is false at cycle 0 and becomes
  // true on every subsequent cycle, providing a guard for all transition assertions.
  val notFirstCycle = RegInit(false.B)
  notFirstCycle := true.B
  
  // State machine logic
  switch(stato) {
    is(StateA) {
      stato := StateB
    }
    is(StateB) {
      when(io.LINEA === false.B) {
        stato := StateC
      }.otherwise {
        stato := StateF
      }
    }
    is(StateC) {
      when(io.LINEA === false.B) {
        stato := StateD
      }.otherwise {
        stato := StateG
      }
    }
    is(StateD) {
      stato := StateE
    }
    is(StateE) {
      stato := StateB
    }
    is(StateF) {
      stato := StateG
    }
    is(StateG) {
      when(io.LINEA === false.B) {
        stato := StateE
      }.otherwise {
        stato := StateA
      }
    }
  }

  // ========== Formal Verification Assertions ==========

  // Safety 1: State register must never enter the reserved/invalid state (all-zero or multi-bit)
  // For one-hot 7-bit encoding, the only valid values have exactly one bit set.
  // An encoding with zero bits or multiple bits set is invalid.
  fvAssert(stato =/= 0.U(7.W), "no_invalid_state")
  // Also check no invalid multi-bit state: the one-hot0 assertion below covers this.

  // Safety 2: Output should only be high when the FSM is in StateE
  // With combinatorial io.U = (stato === StateE), this property is structurally guaranteed.
  fvAssert(!io.U || stato === StateE, "output_only_high_in_state_E")

  // Safety 3: State transition correctness - unconditional jumps
  // Using RegNext-based assertions with notFirstCycle guard to avoid spurious
  // failures at cycle 0 (before any transition has occurred), caused by the
  // hasBeenResetReg X-initialization in the formal wrapper.

  // From StateA, next state must be StateB
  fvAssert(!notFirstCycle || !(prevStato === StateA) || (stato === StateB), "from_A_goes_to_B")

  // From StateD, next state must be StateE
  fvAssert(!notFirstCycle || !(prevStato === StateD) || (stato === StateE), "from_D_goes_to_E")

  // From StateE, next state must be StateB
  fvAssert(!notFirstCycle || !(prevStato === StateE) || (stato === StateB), "from_E_goes_to_B")

  // From StateF, next state must be StateG
  fvAssert(!notFirstCycle || !(prevStato === StateF) || (stato === StateG), "from_F_goes_to_G")

  // Safety 4: State transition correctness - conditional jumps

  // From StateB with LINEA=0, next state is StateC
  fvAssert(!notFirstCycle || !(prevStato === StateB && prevLinea === false.B) || (stato === StateC), "from_B_LINEA0_goes_to_C")

  // From StateB with LINEA=1, next state is StateF
  fvAssert(!notFirstCycle || !(prevStato === StateB && prevLinea === true.B) || (stato === StateF), "from_B_LINEA1_goes_to_F")

  // From StateC with LINEA=0, next state is StateD
  fvAssert(!notFirstCycle || !(prevStato === StateC && prevLinea === false.B) || (stato === StateD), "from_C_LINEA0_goes_to_D")

  // From StateC with LINEA=1, next state is StateG
  fvAssert(!notFirstCycle || !(prevStato === StateC && prevLinea === true.B) || (stato === StateG), "from_C_LINEA1_goes_to_G")

  // From StateG with LINEA=0, next state is StateE
  fvAssert(!notFirstCycle || !(prevStato === StateG && prevLinea === false.B) || (stato === StateE), "from_G_LINEA0_goes_to_E")

  // From StateG with LINEA=1, next state is StateA
  fvAssert(!notFirstCycle || !(prevStato === StateG && prevLinea === true.B) || (stato === StateA), "from_G_LINEA1_goes_to_A")

  // Safety 5: One-hot encoding check - at most one state bit should be active
  assertOneHot0(stato, "state_one_hot0")
}

object VerilogGenerator extends App {
  emitVerilog(new b02(), args)
}
