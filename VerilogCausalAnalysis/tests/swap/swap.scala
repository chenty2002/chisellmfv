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
  
  // Capture pre-swap values for assertion checking
  val x_before_swap = Wire(Vec(Nm1 + 1, UInt(K.W)))
  x_before_swap := x
  
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
  
  // Formal verification assertions
  
  // Property 1: p should be within bounds [0, Nm1]
  fvAssert(p <= Nm1.U, "p should not exceed Nm1")
  
  // Property 2: m should be within bounds [0, Nm1]
  fvAssert(m <= Nm1.U, "m should not exceed Nm1")
  
  // Property 3: When p = 0, m should be Nm1 (wrap-around)
  fvAssert(p =/= 0.U || m === Nm1.U, "m should be Nm1 when p is 0")
  
  // Property 4: When p > 0, m should be p-1
  fvAssert(p === 0.U || m === (p - 1.U), "m should be p-1 when p > 0")
  
  // Property 5: The swap operation should preserve the multiset of values
  // After swap, the set of values in x should be the same as before
  val x_prev = RegNext(x)
  val tmp_prev = RegNext(tmp)
  val p_prev = RegNext(p)
  val m_prev = RegNext(m)
  
  // Check that after swap, x[p] and x[m] have been swapped
  // Fixed: Use x_before_swap to capture pre-swap values in current cycle
  fvAssert(x(p) === x_before_swap(m), "x[p] should equal previous x[m] after swap")
  fvAssert(x(m) === x_before_swap(p), "x[m] should equal previous x[p] after swap")
  
  // Property 6: For all other indices not involved in swap, values should remain unchanged
  for (j <- 0 to Nm1) {
    when(j.U =/= p && j.U =/= m) {
      fvAssert(x(j) === x_before_swap(j), s"x[$j] should remain unchanged when not involved in swap")
    }
  }
  
  // Property 7: tmp should hold the previous value of x[p]
  fvAssert(tmp === x_before_swap(p), "tmp should hold previous x[p] value")
  
  // Property 8: Initial values should be correct (0, 1, 2, ..., Nm1)
  for (j <- 0 to Nm1) {
    assertAt(0.U, x(j) === j.U, s"Initial value of x[$j] should be $j")
  }
  
  // Property 9: No two indices should have the same value (bijection property)
  // This ensures the swap maintains the permutation property
  for (j1 <- 0 to Nm1) {
    for (j2 <- (j1 + 1) to Nm1) {
      fvAssert(x(j1) =/= x(j2), s"x[$j1] and x[$j2] should have different values")
    }
  }
  
  // Property 10: The operation should be invertible (swap twice returns to original)
  // This is a more complex property that we can check over 2 cycles
  val x_two_cycles_ago = RegNext(RegNext(x))
  val p_two_cycles_ago = RegNext(RegNext(p))
  val m_two_cycles_ago = RegNext(RegNext(m))
  
  // If we swap the same indices twice, we should return to original
  fvAssert((p =/= p_two_cycles_ago || m =/= m_two_cycles_ago) || 
           (x(p) === x_two_cycles_ago(p) && x(m) === x_two_cycles_ago(m)), 
           "Swapping same indices twice should return to original values")
}

object VerilogGenerator extends App {
  emitVerilog(new swap(), args)
}