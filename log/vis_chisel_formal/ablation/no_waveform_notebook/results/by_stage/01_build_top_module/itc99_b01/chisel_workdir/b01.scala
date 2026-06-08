package llmverify

import chisel3._
import chisel3.util._

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
}

object VerilogGenerator extends App {
  emitVerilog(new b01(), args)
}