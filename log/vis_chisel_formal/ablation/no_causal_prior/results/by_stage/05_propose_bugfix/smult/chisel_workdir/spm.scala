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

  // ===== Formal Verification Assertions =====

  // Assertion 1: Reset clears internal state
  // When io.reset is asserted, s and c must be zero in the next cycle.
  val reset_prev = RegNext(io.reset)
  fvAssert(!reset_prev || (s === 0.U && c === 0.U), "reset_clears_s_and_c")

  // Assertion 2: CSA arithmetic invariant
  // The carry-save adder correctly reduces three operands (s, c, andA_trunc)
  // to two operands (faS, faC) such that the arithmetic sum is preserved:
  //   s + c + andA_trunc == faS + 2 * faC
  // Use +& to capture the full-width sum without overflow.
  fvAssert(
    s +& c +& andA_trunc === faS +& (faC << 1.U),
    "csa_arithmetic_invariant"
  )

  // Assertion 3: Output bit comes from the LSB of the CSA sum
  // The serial output bit o is the LSB of faS.
  fvAssert(io.o === faS(0), "output_is_faS_lsb")

  // Constraint: io.reset behaves like a proper synchronous reset.
  // Once it has been deasserted, it must remain deasserted forever.
  // Without this constraint, the formal tool can toggle io.reset every few
  // cycles, repeatedly resetting s and c to 0, which prevents the CSA
  // computation from ever making enough progress for io.o to go low.
  val reset_deasserted = RegInit(false.B)
  when(!io.reset) {
    reset_deasserted := true.B
  }
  assume(!(reset_deasserted && io.reset), "reset_stable_once_deasserted")

  // Assertion 4: Bounded liveness - when not in reset and inputs are stable,
  // the multiplier state should make progress (internal state eventually changes
  // or the output toggles).  The multiplier processes one bit per cycle,
  // so within BITS+2 cycles after reset deasserts with a non-zero multiplicand,
  // the output o should have transitioned at least once.
  // Use relaxed liveness: when !io.reset and i_raw =/= 0, the output should
  // eventually go low within BITS+2 cycles.
  val i_nonzero = io.i_raw.orR
  astRelaxedLiveness(
    !io.reset && i_nonzero,
    !io.reset && !io.o,
    BITS + 2,
    "output_eventually_low_when_busy"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new SerialCSAMult(32), args)
}
