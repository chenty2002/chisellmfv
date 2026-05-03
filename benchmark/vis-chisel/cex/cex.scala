package llmverify

import chisel3._
import chisel3.util._

class cex extends Module {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val i = Input(Bool())
    val p = Output(Bool())
    val q = Output(Bool())
  })
  
  // 2-bit state register
  val state = RegInit(0.U(2.W))
  
  // State transition logic
  switch(state) {
    is(0.U) {
      state := 1.U
    }
    is(1.U) {
      when(io.i) {
        state := 2.U
      } .otherwise {
        state := 0.U
      }
    }
    is(2.U) {
      when(io.i) {
        state := 3.U
      } .otherwise {
        state := 2.U
      }
    }
    is(3.U) {
      state := 3.U
    }
  }
  
  // Output logic
  io.p := (state === 1.U)
  io.q := (state === 2.U)
}

object VerilogGenerator extends App {
  emitVerilog(new cex(), args)
}