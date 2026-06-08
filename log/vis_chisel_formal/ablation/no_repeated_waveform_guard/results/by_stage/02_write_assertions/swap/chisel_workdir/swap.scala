package llmverify
import chisel3._
import chisel3.util._
import chisel3.ltl._
import chisel3.ltl.Sequence._

class swap(K: Int = 3, Nm1: Int = 7) extends Module {
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
  
  // Connect outputs to preserve the design
  io.x_out := x
  io.tmp_out := tmp
  io.p_out := p
  io.m_out := m
  
  // ═══════════════════════════════════════════════
  //  Formal Verification Assertions
  // ═══════════════════════════════════════════════

  // ── 1. Index Safety ──
  // p and m must always be within the valid index range [0, Nm1]
  AssertProperty(p <= Nm1.U && m <= Nm1.U, None, None, Some("index_bounds"))

  // ── 2. Indices Distinct ──
  // Since Nm1 > 0 (default Nm1=7), p and m are always different indices.
  // Proof: p=0  => m=Nm1>0, p>0 => m=p-1 =/= p.
  AssertProperty(p =/= m, None, None, Some("indices_distinct"))

  // ── 3. Value Preservation ──
  // The swap operation exchanges values between x(p) and x(m) without
  // losing or corrupting data. After the swap:
  //   - x(p)  gets the previous value of x(m)
  //   - x(m)  gets the previous value of tmp (which held the previous x(p))
  // We check that x(m) equals the tmp value saved in the prior cycle,
  // since tmp captures x(p) before the swap.

  // Capture the value that was written into x(p) (which is old x(m))
  // and x(m) (which is old tmp) after each cycle.
  val prev_xp = RegNext(x(p))   // x(p) after swap = old x(m)
  val prev_xm = RegNext(x(m))   // x(m) after swap = old tmp
  val prev_tmp = RegNext(tmp)   // tmp after swap = old x(p)

  // Swap invariant: after a cycle, x(p) should equal the previous x(m)
  // and x(m) should equal the previous tmp (which was the previous x(p)
  // from two cycles ago, since tmp := x(p) in the same cycle).
  //
  // More precisely, after one cycle with stable p,m:
  //   x(p)  = old x(m)   (captured in prev_xm)
  //   x(m)  = old tmp    (captured in prev_tmp)
  //   tmp   = old x(p)   (captured in prev_xp)

  // When p and m are stable (same input), the swap completes cleanly
  val p_stable = RegNext(p) === p
  val m_stable = RegNext(m) === m
  val stable = p_stable && m_stable

  // With stable indices, x(p) gets the old x(m) and x(m) gets the old tmp
  AssertProperty(
    stable |-> Sequence(x(p) === prev_xm && x(m) === prev_tmp),
    None, None, Some("swap_values_correct")
  )

  // ── 4. Multiset Preservation ──
  // The multiset of values in x is preserved across swaps.
  // We verify this by computing the XOR of all elements, which is
  // invariant under any permutation (including swaps).
  val x_xor = x.reduce(_ ^ _)
  val prev_x_xor = RegNext(x_xor)
  AssertProperty(x_xor === prev_x_xor, None, None, Some("multiset_preserved"))
}

object VerilogGenerator extends App {
  emitVerilog(new swap(), args)
}
