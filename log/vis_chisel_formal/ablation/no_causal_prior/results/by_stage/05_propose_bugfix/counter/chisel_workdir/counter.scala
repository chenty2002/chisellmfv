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

  // ── Formal verification assertions ──
  // 3-bit counter value (out2=MSB, out0=LSB)
  val counter_value = Cat(io.out2, io.out1, io.out0)
  val prev_value = RegNext(counter_value)

  // Safety: counter increments by 1 modulo 8 each cycle
  // Guard: allow the first combinational evaluation (before any clock edge)
  // where prev_value (initialized to 0) equals counter_value (also 0),
  // making (0+1)%8 == 0 fail spuriously.
  val notFirstCycle = RegNext(true.B, false.B)
  fvAssert(!notFirstCycle || reset.asBool || (prev_value + 1.U)(2, 0) === counter_value,
    "counter_increments_by_1_mod_8")

  // Safety: after reset, counter starts at 0
  fvAssert(!reset.asBool || counter_value === 0.U,
    "counter_starts_at_0")

  // Bounded liveness: counter cycles back to 0 within 8 cycles
  // Since the counter runs unconditionally, it must reach 0 repeatedly
  astRelaxedLiveness(true.B, counter_value === 0.U, 8,
    "counter_cycles_back_to_zero_within_8_cycles")
}

object VerilogGenerator extends App {
  emitVerilog(new Counter(), args)
}
