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

  // Previous state snapshot for formal assertions (init to b to avoid
  // matching stato's init value a, which would cause a false failure on
  // cycle 0 for no_self_loop_state_machine, and also avoid e which would
  // cause a false failure on cycle 0 for overflow_when_in_state_e)
  val prevStato = RegNext(stato, b01State.b)

  // ========== Formal Verification Assertions ==========

  // Safety 1: Overflow flag is only asserted in the overflow state (state 'e')
  // Use prevStato because overflwReg is registered and updates one cycle after
  // the state transition — when overflwReg=1 the state has already moved to f,
  // but prevStato still holds the previous value e.
  fvAssert(!overflwReg || prevStato === b01State.e, "overflow_only_in_state_e")

  // Safety 2: When the PREVIOUS state was 'e', the overflow flag must be asserted.
  // Use prevStato because overflwReg is registered and updates one cycle after
  // the state transition — when stato=e, overflwReg is still false from the
  // previous state's logic (wf0/wf1); overflwReg becomes true only in the next
  // cycle when stato has already transitioned to f or b.
  fvAssert(prevStato =/= b01State.e || overflwReg, "overflow_when_in_state_e")

  // Safety 3: State register always holds a valid encoding (one of the 8 defined enum values)
  fvAssert(stato.asUInt < b01State.all.length.U, "valid_state_encoding")

  // Safety 4: FSM has no self-loops — state must change every cycle (progress property)
  fvAssert(stato =/= prevStato, "no_self_loop_state_machine")
}

object VerilogGenerator extends App {
  emitVerilog(new b01(), args)
}
