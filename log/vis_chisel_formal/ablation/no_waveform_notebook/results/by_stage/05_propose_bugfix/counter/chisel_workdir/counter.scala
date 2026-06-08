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

  // Assemble 3-bit counter value
  val prev_value = RegNext(Cat(io.out2, io.out1, io.out0), 7.U(3.W))
  val curr_value = Cat(io.out2, io.out1, io.out0)

  // Safety: counter increments by exactly 1 (mod 8) each cycle
  fvAssert(curr_value === (prev_value + 1.U)(2, 0), "Counter increments by 1 modulo 8 each cycle")

  // Progress: counter value always changes from the previous cycle
  fvAssert(curr_value =/= prev_value, "Counter value changes every cycle")

  // Cycle counter starting from 0 after reset
  val cycle_cnt = RegInit(0.U(4.W))
  cycle_cnt := cycle_cnt + 1.U

  // Bounded liveness: counter wraps to 0 after exactly 8 cycles
  fvAssert(Mux(cycle_cnt === 8.U, curr_value === 0.U, true.B), "Counter wraps to 0 after 8 cycles")

  // The initial value after reset is 0, becomes 1 after first cycle
  fvAssert(Mux(cycle_cnt === 1.U, curr_value === 1.U, true.B), "Counter starts at 0, becomes 1 after first cycle")
}

object VerilogGenerator extends App {
  emitVerilog(new Counter(), args)
}
