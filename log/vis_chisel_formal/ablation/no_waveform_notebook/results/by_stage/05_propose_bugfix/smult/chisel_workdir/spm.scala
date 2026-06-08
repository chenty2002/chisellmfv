package llmverify
import chisel3._
import chisel3.util._
import chiselFv._

class SerialCSAMult(BITS: Int = 32) extends Module with Formal {
  val io = IO(new Bundle {
    val reset = Input(Bool())
    val i_raw = Input(UInt(BITS.W))
    val j_raw = Input(Bool())
    val o = Output(Bool())
    // Additional outputs to preserve internal state for verification
    val s_debug = Output(UInt((BITS-1).W))
    val c_debug = Output(UInt((BITS-1).W))
    val i_debug = Output(UInt(BITS.W))
    val j_debug = Output(Bool())
  })
  
  // Registers
  val s = RegInit(0.U((BITS-1).W))  // sum register (BITS-2:0, but using BITS-1 for alignment)
  val c = RegInit(0.U((BITS-1).W))  // carry register (BITS-2:0, but using BITS-1 for alignment)
  val i = RegInit(0.U(BITS.W))      // registered multiplicand
  val j = RegInit(false.B)          // registered multiplier bit
  
  // Register inputs on clock edge
  i := io.i_raw
  j := io.j_raw
  
  // Combinational logic
  val andA = Fill(BITS, j) & i  // product of multiplicand and multiplier bit
  
  // Carry-save adder logic
  val andA_trunc = andA(BITS-2, 0)  // andA[BITS-2:0]
  val faS = c ^ s ^ andA_trunc      // sum outputs of CSA
  val faC = (c & s) | (c & andA_trunc) | (s & andA_trunc)  // carry outputs of CSA
  
  // Sequential logic for sum and carry registers
  when(io.reset) {
    s := 0.U
    c := 0.U
  }.otherwise {
    s := Cat(andA(BITS-1), faS(BITS-2, 1))  // {andA[BITS-1], faS[BITS-2:1]}
    c := faC
  }
  
  // Output assignment
  io.o := faS(0)
  
  // Debug outputs to preserve internal state
  io.s_debug := s
  io.c_debug := c
  io.i_debug := i
  io.j_debug := j

  // ========== Formal Assertions ==========

  // Assertion 1: CSA correctness property
  // The carry-save adder satisfies: faS + (faC << 1) === s + c + andA_trunc
  // The left shift of faC accounts for the carry output representing carries
  // to the next bit position, which must be shifted left by 1 before adding.
  // Using +& (carry-extending addition) on both sides to handle overflow
  // correctly — without it, the 31-bit RHS wraps while the 32-bit LHS does not.
  fvAssert(faS +& (faC << 1).asUInt === s +& c +& andA_trunc, "csa_correctness")

  // Assertion 2: Pipeline arithmetic invariant
  // (next_s << 1) + (faC << 1) + io.o === s + c + andA
  // This shows the serial multiplier correctly accumulates partial products
  // while shifting out the LSB of the sum each cycle.
  // The carry word faC must be shifted left by 1 to account for its
  // bit-position significance in carry-save representation.
  val next_s = Cat(andA(BITS-1), faS(BITS-2, 1))
  fvAssert((next_s << 1).asUInt + (faC << 1).asUInt + faS(0) === s + c + andA, "pipeline_invariant")

  // Assertion 3: Reset clears sum and carry registers
  // One cycle after reset is asserted, both s and c must be zero.
  assertAfterNStepWhen(io.reset, 1, (s === 0.U) && (c === 0.U), "reset_clears_registers")
}

object VerilogGenerator extends App {
  emitVerilog(new SerialCSAMult(32), args)
}
