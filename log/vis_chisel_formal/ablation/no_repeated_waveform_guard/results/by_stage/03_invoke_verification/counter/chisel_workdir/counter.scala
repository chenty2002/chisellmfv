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
  
  // ========== Formal Verification Assertions ==========
  
  // Form the 3-bit counter value
  val count = Cat(io.out2, io.out1, io.out0)
  val prev_count = RegNext(count)
  
  // Property 1: Counter increments by exactly 1 modulo 8 each cycle
  // This verifies the ripple counter behaves as a binary counter.
  // UInt addition wraps naturally, so 7.U + 1.U === 0.U (mod 8).
  AssertProperty(prev_count + 1.U === count, None, None, Some("counter_sequential"))
  
  // Property 2: out0 toggles every cycle
  // Since bit0.carry_in is tied to true.B, bit0 must toggle each cycle.
  // This checks the fundamental T-flip-flop behavior.
  AssertProperty(RegNext(io.out0) === !io.out0, None, None, Some("out0_toggle"))
  
  // Property 3: Liveness - counter changes every cycle
  // Since out0 (the LSB) toggles every cycle, the full 3-bit count is
  // guaranteed to change each cycle. This ensures the counter never stalls.
  AssertProperty(count =/= prev_count, None, None, Some("counter_changes"))
}

object VerilogGenerator extends App {
  emitVerilog(new Counter(), args)
}
