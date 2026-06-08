package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

class CounterCell extends Module with Formal {
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
  
  // Formal verification assertions for CounterCell
  
  // Property 1: carry_out should be true only when both value and carry_in are true
  fvAssert(io.carry_out === (value & io.carry_in), "carry_out should equal value AND carry_in")
  
  // Property 2: value toggles when carry_in is true, stays stable when carry_in is false
  val next_value = Mux(io.carry_in, !value, value)
  fvAssert(RegNext(value) === next_value, "value should toggle when carry_in is true, stay stable otherwise")
  
  // Property 3: value output should equal the internal register
  fvAssert(io.value === value, "value output should equal internal register")
  
  // Property 4: carry_out should be stable when inputs are stable
  assertStableWhen(!io.carry_in && !value, io.carry_out, "carry_out should be stable when inputs are false")
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
  
  // Formal verification assertions for Counter
  
  // Property 1: This is a 3-bit counter, so it should count from 0 to 7 and wrap around
  // Create the current counter value
  val current_count = Cat(io.out2, io.out1, io.out0)
  
  // Property 2: Counter should increment by 1 each cycle (using RegNext for next state)
  fvAssert(current_count === (RegNext(current_count) + 1.U(3.W)), "Counter should increment by 1 each cycle")
  
  // Property 3: Counter should wrap from 7 to 0
  fvAssert(!(current_count === 7.U) || (RegNext(current_count) === 0.U), "Counter should wrap from 7 to 0")
  
  // Property 4: Counter should never exceed 7
  fvAssert(current_count <= 7.U, "Counter value should never exceed 7")
  
  // Property 5: All possible values 0-7 should be reachable (liveness)
  // We'll use relaxed liveness to ensure each value appears within a reasonable time
  for (i <- 0 to 7) {
    astRelaxedLiveness(true.B, current_count === i.U, 8, s"Counter should reach value $i within 8 cycles")
  }
  
  // Property 6: Bit0 should toggle every cycle (since carry_in is always true)
  fvAssert(bit0.io.carry_in === true.B, "bit0 carry_in should always be true")
  fvAssert(RegNext(bit0.io.value) === !bit0.io.value, "bit0 should toggle every cycle")
  
  // Property 7: Bit1 should toggle when bit0 transitions from 1 to 0
  val bit0_falling = RegNext(bit0.io.value) && !bit0.io.value
  fvAssert(!bit0_falling || (RegNext(bit1.io.value) === !bit1.io.value), "bit1 should toggle when bit0 falls")
  
  // Property 8: Bit2 should toggle when bit1 transitions from 1 to 0
  val bit1_falling = RegNext(bit1.io.value) && !bit1.io.value
  fvAssert(!bit1_falling || (RegNext(bit2.io.value) === !bit2.io.value), "bit2 should toggle when bit1 falls")
  
  // Property 9: Counter should start from 0 after reset
  fvAssert(reset.asBool || (current_count === 0.U), "Counter should start from 0 after reset")
  
  // Property 10: No two consecutive values should be the same
  fvAssert(reset.asBool || (RegNext(current_count) =/= current_count), "Counter should not have consecutive same values")
  
  // Property 11: Carry chain should work correctly
  fvAssert(bit1.io.carry_in === bit0.io.carry_out, "bit1 carry_in should equal bit0 carry_out")
  fvAssert(bit2.io.carry_in === bit1.io.carry_out, "bit2 carry_in should equal bit1 carry_out")
  
  // Property 12: Counter should follow binary counting pattern
  // Check that the counter sequence follows expected binary progression
  val expected_next = Mux(current_count === 7.U, 0.U, current_count + 1.U)
  fvAssert(RegNext(current_count) === expected_next, "Counter should follow binary counting pattern")
}

object VerilogGenerator extends App {
  emitVerilog(new Counter(), args)
}