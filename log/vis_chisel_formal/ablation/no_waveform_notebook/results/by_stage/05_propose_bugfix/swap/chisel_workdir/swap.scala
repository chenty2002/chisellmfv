package llmverify
import chisel3._
import chisel3.util._
import chiselFv._

class swap(K: Int = 3, Nm1: Int = 7) extends Module with Formal {
  val io = IO(new Bundle {
    val i = Input(UInt(K.W))
    // Add outputs to preserve the entire design
    val x_out = Output(Vec(Nm1 + 1, UInt(K.W)))
    val tmp_out = Output(UInt(K.W))
    val p_out = Output(UInt(K.W))
    val m_out = Output(UInt(K.W))
  }
  )
  
  // Register array x[0:Nm1]
  val x = RegInit(VecInit(Seq.tabulate(Nm1 + 1)(j => j.U(K.W))))
  
  // Temporary wire for swapping — must be combinational so that
  // x(m) := tmp reads the current x(p) value, not a stale previous-cycle value.
  val tmp = Wire(UInt(K.W))
  
  // Wires for m and p
  val p = Wire(UInt(K.W))
  val m = Wire(UInt(K.W))
  
  // Combinational logic for p and m
  p := Mux(io.i >= Nm1.U, Nm1.U, io.i)
  m := Mux(p === 0.U, Nm1.U, (p - 1.U))
  
  // Swap: capture x(p) combinationally in tmp, then update both registers
  // on the clock edge.  Because tmp is a Wire, x(m) := tmp sees the current
  // combinational value of x(p), giving a correct single-cycle swap.
  tmp := x(p)
  x(p) := x(m)
  x(m) := tmp
  
  // Connect outputs to preserve the design
  io.x_out := x
  io.tmp_out := tmp
  io.p_out := p
  io.m_out := m

  // ── Formal verification assertions ──

  // Capture previous-cycle values for swap correctness checking
  val prev_x = RegNext(x)
  val prev_p = RegNext(p)
  val prev_m = RegNext(m)

  // Property 1: Value range invariant — all elements stay within [0, Nm1]
  for (j <- 0 to Nm1) {
    fvAssert(x(j) <= Nm1.U, s"x($j)_in_range")
  }

  // First-cycle guard: RegNext values are uninitialised on the very first
  // post-reset cycle, so defer any assertion that depends on them.
  // NOTE: Embed the guard directly in the assertion expression because
  // ChiselFv's fvAssert does not respect enclosing when() blocks.
  val first_cycle = RegInit(true.B)
  first_cycle := false.B

  // Property 2: Swap correctness — after the swap, x(prev_p) equals the old x(prev_m)
  //              and x(prev_m) equals the old x(prev_p)
  fvAssert(!first_cycle || (x(prev_p) === prev_x(prev_m)), "swap_p_gets_old_m")
  fvAssert(!first_cycle || (x(prev_m) === prev_x(prev_p)), "swap_m_gets_old_p")

  // Property 3: Sum preservation — swapping does not change the multiset of values
  val sum = x.reduce(_ + _)
  val prev_sum = RegNext(sum)
  fvAssert(!first_cycle || (sum === prev_sum), "sum_preserved_under_swap")
}

object VerilogGenerator extends App {
  emitVerilog(new swap(), args)
}
