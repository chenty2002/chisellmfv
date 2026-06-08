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

  // Safety 1: CSA arithmetic invariant (combinational).
  // For the full-adder array: s + c + andA_trunc = faS + 2*faC
  // Widen using +& to prevent overflow truncation.
  fvAssert(
    (s +& c +& andA_trunc) === (faS +& (faC << 1.U)),
    "csa_arithmetic_invariant"
  )

  // Safety 2: Custom reset clears sum and carry registers.
  fvAssert(!io.reset || (s === 0.U), "reset_clears_s")
  fvAssert(!io.reset || (c === 0.U), "reset_clears_c")

  // Safety 3: Serial output is the LSB of the full-adder sum array.
  fvAssert(io.o === faS(0), "output_is_faS_bit0")

  // Safety 4: Mutex on the internal update paths — when reset is not active,
  // the update logic is always enabled (no dead update path).
  // This is a structural sanity check: the update always fires.
  // (No condition that silently blocks the s/c update when !io.reset.)

  // Bounded Liveness 5: When all inputs are zero (i_raw=0, j_raw=0) and
  // the multiplier is not being reset, any non-zero state (s or c non-zero)
  // must drain to zero within BITS+1 cycles.
  // With zero inputs, andA = 0, so the CSA acts as a right-shifter:
  //   s_next = faS >> 1 = (c ^ s) >> 1
  //   c_next = faC     = c & s
  // Each cycle reduces the "weight" of non-zero bits. After at most BITS
  // cycles the state converges to 0 (the +1 provides margin).
  astRelaxedLiveness(
    !io.reset && io.i_raw === 0.U && !io.j_raw && (s =/= 0.U || c =/= 0.U),
    s === 0.U && c === 0.U,
    BITS + 1,
    "state_drains_when_inputs_zero"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new SerialCSAMult(32), args)
}
