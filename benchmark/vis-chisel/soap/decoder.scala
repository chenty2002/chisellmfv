package llmverify

import chisel3._
import chisel3.util._

class Decoder extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(3.W))
    val en = Input(Bool())
    val dec = Output(UInt(4.W))
  })
  
  io.dec := 0.U
  when(io.en) {
    switch(io.in) {
      is(0.U) { io.dec := "b0001".U }
      is(1.U) { io.dec := "b0010".U }
      is(2.U) { io.dec := "b0100".U }
      is(3.U) { io.dec := "b1000".U }
    }
  }
}

object Decoder extends App {
  emitVerilog(new Decoder(), args)
}