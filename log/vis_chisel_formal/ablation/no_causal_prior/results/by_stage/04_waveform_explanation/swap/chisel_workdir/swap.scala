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
  
  // Formal verification assertions
  // Safety: p and m are always within array bounds [0, Nm1]
  fvAssert(p <= Nm1.U, "p_in_range")
  fvAssert(m <= Nm1.U, "m_in_range")
  
  // Safety: p and m must always be distinct (swap different elements)
  // When p=0, m=Nm1 (>0). When p>0, m=p-1 (<p). So p =/= m always.
  fvAssert(p =/= m, "distinct_indices")
  
  // Safety: tmp always captures the value at x(p) before the swap.
  // This is guaranteed by the register update semantics, but we assert
  // that tmp takes the value of x(p) after the combinational path settles.
  fvAssert(tmp === x(p), "tmp_equals_x_p")
  
  // Connect outputs to preserve the design
  io.x_out := x
  io.tmp_out := tmp
  io.p_out := p
  io.m_out := m
}

object VerilogGenerator extends App {
  emitVerilog(new swap(), args)
}
