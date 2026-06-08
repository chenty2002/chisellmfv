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
        stato := b01State.e  // Fixed: transition directly to state e when both inputs are true
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
  
  // Formal verification assertions
  
  // Property 1: OVERFLW is only true in state e
  fvAssert(stato === b01State.e || !io.OVERFLW, "OVERFLW should only be true in state e")
  
  // Property 2: State encoding - only one state should be active at a time
  val stateBits = Cat(stato.asUInt)
  assertOneHot(stateBits, "State should be one-hot encoded")
  
  // Property 3: From state a, next state should be either b or f
  fvAssert(stato =/= b01State.a || (stato === b01State.b || stato === b01State.f), 
           "From state a, next state should be b or f")
  
  // Property 4: From state e, next state should be either b or f
  fvAssert(stato =/= b01State.e || (stato === b01State.b || stato === b01State.f), 
           "From state e, next state should be b or f")
  
  // Property 5: From state b, next state should be either c or g
  fvAssert(stato =/= b01State.b || (stato === b01State.c || stato === b01State.g), 
           "From state b, next state should be c or g")
  
  // Property 6: From state c, next state should be either e or wf0
  fvAssert(stato =/= b01State.c || (stato === b01State.e || stato === b01State.wf0), 
           "From state c, next state should be e or wf0")
  
  // Property 7: From state f, next state should be either c or g
  fvAssert(stato =/= b01State.f || (stato === b01State.c || stato === b01State.g), 
           "From state f, next state should be c or g")
  
  // Property 8: From state g, next state should be either wf0 or wf1
  fvAssert(stato =/= b01State.g || (stato === b01State.wf0 || stato === b01State.wf1), 
           "From state g, next state should be wf0 or wf1")
  
  // Property 9: From state wf0, next state should be either a or e
  fvAssert(stato =/= b01State.wf0 || (stato === b01State.a || stato === b01State.e), 
           "From state wf0, next state should be a or e")
  
  // Property 10: From state wf1, next state should be either a or e
  fvAssert(stato =/= b01State.wf1 || (stato === b01State.a || stato === b01State.e), 
           "From state wf1, next state should be a or e")
  
  // Property 11: Output logic consistency - OUTP should follow XOR/NXOR pattern based on state
  // States a, b, c, e, wf0 use XOR
  val xorStates = Seq(b01State.a, b01State.b, b01State.c, b01State.e, b01State.wf0)
  val nXorStates = Seq(b01State.f, b01State.g, b01State.wf1)
  
  fvAssert(!xorStates.map(stato === _).reduce(_ || _) || io.OUTP === (io.LINE1 ^ io.LINE2), 
           "In XOR states, OUTP should equal LINE1 XOR LINE2")
  
  fvAssert(!nXorStates.map(stato === _).reduce(_ || _) || io.OUTP === ~(io.LINE1 ^ io.LINE2), 
           "In NXOR states, OUTP should equal NOT(LINE1 XOR LINE2)")
  
  // Property 12: Liveness - the circuit should eventually return to state a
  astRelaxedLiveness(true.B, stato === b01State.a, 20, "Should eventually return to state a")
}

object VerilogGenerator extends App {
  emitVerilog(new b01(), args)
}