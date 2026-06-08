package llmverify

import chisel3._
import chisel3.util._

class CounterCell extends Module {
  val io = IO(new Bundle {
    val carry_in = Input(Bool())
    val carry_out = Output(Bool())
    val value = Output(Bool())
  })
  
  val value = RegInit(false.B)
  
  io.carry_out := value & io.carry_in
  io.value := value
  
  // Equivalent to (value + carry_in) % 2
  when (io.carry_in) {
    value := !value
  }
}

class Counter extends Module {
  val io = IO(new Bundle {
    val out0 = Output(Bool())
    val out1 = Output(Bool())
    val out2 = Output(Bool())
  })
  
  val bit0 = Module(new CounterCell())
  val bit1 = Module(new CounterCell())
  val bit2 = Module(new CounterCell())
  
  // Connect bit0 - always has carry_in = 1
  bit0.io.carry_in := true.B
  
  // Connect bit1 - carry_in from bit0's carry_out
  bit1.io.carry_in := bit0.io.carry_out
  
  // Connect bit2 - carry_in from bit1's carry_out
  bit2.io.carry_in := bit1.io.carry_out
  
  // Output the values
  io.out0 := bit0.io.value
  io.out1 := bit1.io.value
  io.out2 := bit2.io.value
}

object VerilogGenerator extends App {
  emitVerilog(new Counter(), args)
}