package llmverify

import chisel3._
import chisel3.util._
import chisel3.ltl._
import chisel3.ltl.Sequence._

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
  
  // ── Formal Verification Assertions ──
  // Combined 3-bit count value (out2 is MSB, out0 is LSB)
  val count = Cat(io.out2, io.out1, io.out0)
  
  // Track previous cycle values
  val prev_count = RegInit(0.U(3.W))
  prev_count := count
  val prev_out0 = RegInit(false.B)
  prev_out0 := io.out0
  val not_first = RegInit(false.B)
  not_first := true.B
  
  // Assertion 1: Count increments by 1 each cycle (mod 8).
  // After reset the count starts at 0; every cycle it advances by 1,
  // wrapping from 7 (0b111) back to 0 (mod 8 via 3-bit arithmetic).
  AssertProperty(
    !not_first || count === (prev_count + 1.U),
    None, None, Some("counter_increment")
  )
  
  // Assertion 2: LSB (out0) toggles every cycle.
  // bit0 always has carry_in = true.B, so its register inverts every cycle.
  AssertProperty(
    !not_first || io.out0 === !prev_out0,
    None, None, Some("lsb_toggles")
  )
}

object VerilogGenerator extends App {
  emitVerilog(new Counter(), args)
}
