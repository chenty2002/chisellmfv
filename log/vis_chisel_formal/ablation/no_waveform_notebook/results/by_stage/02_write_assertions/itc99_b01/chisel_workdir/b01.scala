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
  
  // State decoding for assertions
  val is_a   = stato === b01State.a
  val is_b   = stato === b01State.b
  val is_c   = stato === b01State.c
  val is_e   = stato === b01State.e
  val is_f   = stato === b01State.f
  val is_g   = stato === b01State.g
  val is_wf0 = stato === b01State.wf0
  val is_wf1 = stato === b01State.wf1
  
  val allStates = Seq(is_a, is_b, is_c, is_e, is_f, is_g, is_wf0, is_wf1)
  
  // --- Formal Assertions ---
  
  // Safety 1: At most one state is active at any time
  assertMutex(allStates, "state_mutex")
  
  // Safety 2: OVERFLW is only asserted when in state e
  // If OVERFLW is high, we must be in state e
  fvAssert(!io.OVERFLW || is_e, "OVERFLW_only_in_state_e")
  
  // Safety 3: OUTP follows the correct XOR formula based on state group
  // Group 1 (states a,b,c,e,wf0): OUTP = LINE1 ^ LINE2
  // Group 2 (states f,g,wf1):     OUTP = ~(LINE1 ^ LINE2)
  val in_xor_group = is_a || is_b || is_c || is_e || is_wf0
  val in_xnor_group = is_f || is_g || is_wf1
  fvAssert(!in_xor_group || io.OUTP === (io.LINE1 ^ io.LINE2), "OUTP_XOR_in_states_a_b_c_e_wf0")
  fvAssert(!in_xnor_group || io.OUTP === ~(io.LINE1 ^ io.LINE2), "OUTP_XNOR_in_states_f_g_wf1")
  
  // Liveness 4: The state machine makes progress toward home states (a or e)
  // If not at a home state, we should reach one within 20 cycles
  astRelaxedLiveness(
    !is_a && !is_e,   // request: not at a home state
    is_a || is_e,      // response: reached a home state
    20,
    "eventually_reach_home_state"
  )
  
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
}

object VerilogGenerator extends App {
  emitVerilog(new b01(), args)
}
