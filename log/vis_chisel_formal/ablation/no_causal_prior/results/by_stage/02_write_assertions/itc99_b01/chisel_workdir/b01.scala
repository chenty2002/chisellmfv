package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

object b01State extends ChiselEnum {
  val a, b, c, e, f, g, wf0, wf1 = Value
}

class b01 extends Module with Formal {
  val io = IO(new Bundle {
    val LINE1 = Input(Bool())
    val LINE2 = Input(Bool())
    val OUTP = Output(Bool())
    val OVERFLW = Output(Bool())
  })
  
  // State register
  val stato = RegInit(b01State.a)
  
  // Output registers
  val outpReg = RegInit(false.B)
  val overflwReg = RegInit(false.B)
  
  // Connect outputs
  io.OUTP := outpReg
  io.OVERFLW := overflwReg
  
  // State transition and output logic
  switch(stato) {
    is(b01State.a) {
      when(io.LINE1 & io.LINE2) {
        stato := b01State.f
      }.otherwise {
        stato := b01State.b
      }
      outpReg := io.LINE1 ^ io.LINE2
      overflwReg := false.B
    }
    is(b01State.e) {
      when(io.LINE1 & io.LINE2) {
        stato := b01State.f
      }.otherwise {
        stato := b01State.b
      }
      outpReg := io.LINE1 ^ io.LINE2
      overflwReg := true.B
    }
    is(b01State.b) {
      when(io.LINE1 & io.LINE2) {
        stato := b01State.g
      }.otherwise {
        stato := b01State.c
      }
      outpReg := io.LINE1 ^ io.LINE2
      overflwReg := false.B
    }
    is(b01State.f) {
      when(io.LINE1 | io.LINE2) {
        stato := b01State.g
      }.otherwise {
        stato := b01State.c
      }
      outpReg := ~(io.LINE1 ^ io.LINE2)
      overflwReg := false.B
    }
    is(b01State.c) {
      when(io.LINE1 & io.LINE2) {
        stato := b01State.wf1
      }.otherwise {
        stato := b01State.wf0
      }
      outpReg := io.LINE1 ^ io.LINE2
      overflwReg := false.B
    }
    is(b01State.g) {
      when(io.LINE1 | io.LINE2) {
        stato := b01State.wf1
      }.otherwise {
        stato := b01State.wf0
      }
      outpReg := ~(io.LINE1 ^ io.LINE2)
      overflwReg := false.B
    }
    is(b01State.wf0) {
      when(io.LINE1 & io.LINE2) {
        stato := b01State.e
      }.otherwise {
        stato := b01State.a
      }
      outpReg := io.LINE1 ^ io.LINE2
      overflwReg := false.B
    }
    is(b01State.wf1) {
      when(io.LINE1 | io.LINE2) {
        stato := b01State.e
      }.otherwise {
        stato := b01State.a
      }
      outpReg := ~(io.LINE1 ^ io.LINE2)
      overflwReg := false.B
    }
  }

  // ===== Formal Verification Assertions =====

  // XOR of the two line inputs
  val inXor = io.LINE1 ^ io.LINE2

  // States that output XOR: a, b, c, e, wf0
  val xorStates = (stato === b01State.a) || (stato === b01State.b) || 
                  (stato === b01State.c) || (stato === b01State.e) || 
                  (stato === b01State.wf0)

  // States that output inverted XOR (XNOR): f, g, wf1
  val xnorStates = (stato === b01State.f) || (stato === b01State.g) || 
                   (stato === b01State.wf1)

  // Assertion 1: OVERFLW correctness
  // overflwReg is set to true.B only in state e, false.B in all others.
  // Therefore, overflwReg should equal RegNext(stato === e).
  fvAssert(RegNext(stato === b01State.e) === overflwReg, "OVERFLW_correctness")

  // Assertion 2: OUTP correctness in XOR states
  // When stato is a, b, c, e, or wf0, outpReg must be XOR of inputs.
  fvAssert(!xorStates || (outpReg === inXor), "OUTP_XOR_correctness")

  // Assertion 3: OUTP correctness in XNOR states
  // When stato is f, g, or wf1, outpReg must be inverted XOR of inputs.
  fvAssert(!xnorStates || (outpReg === ~inXor), "OUTP_XNOR_correctness")

  // Assertion 4: State validity
  // The state must always be one of the valid enum values (catches X-propagation).
  fvAssert(xorStates || xnorStates, "state_is_valid")

  // Assertion 5: OVERFLW is never asserted for two consecutive cycles
  // Since overflwReg is true only in state e, and state e always transitions
  // to f or b (both non-e states), OVERFLW can never be back-to-back true.
  fvAssert(!(overflwReg && RegNext(overflwReg)), "OVERFLW_not_consecutive")

  // Assertion 6: Bounded liveness — from any non-terminal state (not a or e),
  // the FSM must reach state a or state e within 3 cycles.
  // Maximum path length from any state to a or e:
  //   b/c/f/g → c/g → wf0/wf1 → a/e  (max 3 steps)
  astRelaxedLiveness(
    (stato =/= b01State.a) && (stato =/= b01State.e),
    (stato === b01State.a) || (stato === b01State.e),
    3,
    "reach_a_or_e_liveness"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new b01(), args)
}
