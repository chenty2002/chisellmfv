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
  })
  
  // Register array x[0:Nm1]
  val x = RegInit(VecInit(Seq.tabulate(Nm1 + 1)(j => j.U(K.W))))
  
  // Temporary register
  val tmp = RegInit(0.U(K.W))
  
  // Wires for m and p
  val p = Wire(UInt(K.W))
  val m = Wire(UInt(K.W))
  
  // Combinational logic for p and m
  p := Mux(io.i >= Nm1.U, Nm1.U, io.i)
  m := Mux(p === 0.U, Nm1.U, (p - 1.U))
  
  // Sequential logic on positive clock edge
  when(true.B) { // Chisel implicitly uses the clock
    tmp := x(p)
    x(p) := x(m)
    x(m) := tmp
  }

  // ─── Formal Verification Assertions ───

  // Safety 1: p is always in bounds [0, Nm1]
  fvAssert(p <= Nm1.U, "p_in_bounds")

  // Safety 2: m is always in bounds [0, Nm1]
  fvAssert(m <= Nm1.U, "m_in_bounds")

  // Safety 3: p and m are distinct indices (swap must involve two different positions)
  fvAssert(p =/= m, "p_not_equal_m")

  // Safety 4: Cross-cycle swap correctness.
  // Capture the previous cycle's values to verify that x(p) gets the old x(m)
  // and x(m) gets the old x(p) after each update.
  val prev_x_p = RegNext(x(p))
  val prev_x_m = RegNext(x(m))
  val prev_p = RegNext(p)
  val prev_m = RegNext(m)

  // After the update, x(prev_p) should equal the old value of x(m)
  fvAssert(x(prev_p) === prev_x_m, "swap_x_p_gets_old_x_m")

  // After the update, x(prev_m) should equal the old value of x(p)
  fvAssert(x(prev_m) === prev_x_p, "swap_x_m_gets_old_x_p")

  // Connect outputs to preserve the design
  io.x_out := x
  io.tmp_out := tmp
  io.p_out := p
  io.m_out := m
}

object VerilogGenerator extends App {
  emitVerilog(new swap(), args)
}
