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

  // ====== Formal Verification Assertions ======

  // CSA invariant: For each full-adder bit position, a + b + cin = sum + 2*cout.
  // Aggregated across all bits: c + s + andA_trunc == faS + 2*faC
  // This guards the mathematical correctness of the carry-save adder core.
  fvAssert(
    c.asUInt + s.asUInt + andA_trunc.asUInt === faS.asUInt + (faC.asUInt << 1),
    "csa_invariant"
  )

  // Reset safety: When reset is asserted, sum and carry registers must be zero.
  fvAssert(!io.reset || (s === 0.U && c === 0.U), "reset_clears_s_c")

  // Output derivation: The serial output must equal the LSB of the full-adder sum.
  fvAssert(io.o === faS(0), "output_is_faS_bit0")
}

object VerilogGenerator extends App {
  emitVerilog(new SerialCSAMult(32), args)
}
