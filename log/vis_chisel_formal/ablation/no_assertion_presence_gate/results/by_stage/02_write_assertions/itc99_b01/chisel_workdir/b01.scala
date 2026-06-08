package llmverify

import chisel3._
import chisel3.util._
import chisel3.ltl._
import chisel3.ltl.Sequence._

object b01State extends ChiselEnum {
  val a, b, c, e, f, g, wf0, wf1 = Value
}

class b01 extends Module {
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

  // Safety: OVERFLW must be asserted exactly when in state e.
  // The design sets overflwReg = true.B only in state e and false.B in all
  // other states, so this equivalence is a critical invariant.
  AssertProperty(io.OVERFLW === (stato === b01State.e), None, None, Some("OVERFLW_indicates_state_e"))

  // Bounded liveness / progress: the FSM state must change every cycle.
  // No state in this machine has a self-loop; every state unconditionally
  // transitions to a different state each cycle, guaranteeing forward progress.
  AssertProperty(RegNext(stato) =/= stato, None, None, Some("state_progress_every_cycle"))
}

object VerilogGenerator extends App {
  emitVerilog(new b01(), args)
}
