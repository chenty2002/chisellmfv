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

  // ========== Formal Verification Assertions ==========

  // Assertion 1: CSA Full-Adder Correctness
  // The carry-save adder must satisfy: sum + 2*carry = a + b + cin
  // i.e., faS + 2*faC must equal c + s + andA_trunc
  fvAssert(faS + (faC << 1.U) === c + s + andA_trunc, "CSA_correctness")

  // Assertion 2: Reset Clears State
  // When io.reset is asserted, both the sum and carry registers must be zero
  fvAssert(!io.reset || (s === 0.U && c === 0.U), "reset_clears_state")

  // Assertion 3: Accumulation Invariant
  // The serial multiplier correctly accumulates partial products.
  // The key recurrence: 2*s_new + c_new + io.o = s_old + c_old + andA
  // This proves that the carry-save accumulation preserves the arithmetic sum.
  val prevCS = RegNext(c + s)
  fvAssert((s << 1.U) + c + io.o === prevCS + andA, "accumulation_invariant")

  // Assertion 4: Liveness - Forward Progress
  // When not in reset, the serial multiplier must make forward progress.
  // The accumulated value (s << 1) + c should eventually change (since andA is
  // constantly re-evaluated with fresh inputs), or reset must be asserted.
  // Using a bounded liveness check with a generous bound of 2*BITS cycles.
  val accumVal = (s << 1.U) + c
  val prevAccum = RegNext(accumVal)
  astRelaxedLiveness(!io.reset && accumVal === prevAccum,
                     io.reset || accumVal =/= prevAccum,
                     BITS * 2,
                     "liveness_accum_progress")
}

object VerilogGenerator extends App {
  emitVerilog(new SerialCSAMult(32), args)
}
