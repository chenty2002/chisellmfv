package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

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

class Counter extends Module with Formal {
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

  // --- Formal Assertions ---

  // Track initialization to skip the first cycle
  val init_done = RegInit(false.B)
  init_done := true.B

  val out0_prev = RegNext(io.out0)
  val out1_prev = RegNext(io.out1)
  val out2_prev = RegNext(io.out2)

  // Safety: out0 toggles every cycle because carry_in to bit0 is always true
  fvAssert(!init_done || io.out0 =/= out0_prev, "out0 toggles every cycle")

  // Safety: out1 toggles exactly when out0 is 1 (carry propagates from bit0)
  fvAssert(!init_done || !io.out0 || io.out1 =/= out1_prev, "out1 toggles when out0=1")
  fvAssert(!init_done || io.out0 || io.out1 === out1_prev, "out1 stable when out0=0")

  // Safety: out2 toggles exactly when both out0 and out1 are 1 (carry propagates from bit1)
  val toggle_out2 = io.out0 & io.out1
  fvAssert(!init_done || !toggle_out2 || io.out2 =/= out2_prev, "out2 toggles when both out0 and out1 are set")
  fvAssert(!init_done || toggle_out2 || io.out2 === out2_prev, "out2 stable when not both set")

  // Liveness: The 3-bit counter cycles through all states, reaching 0 within 8 cycles
  val count_value = io.out2 ## io.out1 ## io.out0
  astRelaxedLiveness(true.B, count_value === 0.U, 8, "counter reaches 0 within 8 cycles")
}

object VerilogGenerator extends App {
  emitVerilog(new Counter(), args)
}
