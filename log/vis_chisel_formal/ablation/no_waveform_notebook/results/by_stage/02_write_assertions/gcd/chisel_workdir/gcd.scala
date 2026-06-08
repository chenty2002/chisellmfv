package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

// GCD circuit for unsigned N-bit numbers
class Gcd(val N: Int = 8, val logN: Int = 3) extends Module with Formal {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val a = Input(UInt(N.W))
    val b = Input(UInt(N.W))
    val busy = Output(Bool())
    val o = Output(UInt(N.W))
  })
  
  // Internal registers
  val lsb = RegInit(0.U(logN.W))
  val x = RegInit(0.U(N.W))
  val y = RegInit(0.U(N.W))
  val busyReg = RegInit(false.B)
  val oReg = RegInit(0.U(N.W))
  
  // Helper function to select bit based on lsb using dynamic bit selection
  def select(z: UInt, lsb: UInt): UInt = {
    // Use dynamic bit selection - this is equivalent to the Verilog function
    z(lsb)
  }
  
  // Wires
  val xy_lsb = Wire(UInt(2.W))
  val diff = Wire(UInt(N.W))
  val done = Wire(Bool())
  val load = Wire(Bool())
  
  xy_lsb := Cat(select(x, lsb), select(y, lsb))
  diff := Mux(x < y, y - x, x - y)
  done := ((x === y) || (x === 0.U) || (y === 0.U)) && busyReg
  load := io.start && !busyReg
  
  // Data path logic
  when(load) {
    x := io.a
    y := io.b
    lsb := 0.U
  }.elsewhen(busyReg && !done) {
    switch(xy_lsb) {
      is(0.U) { // 2'b00
        lsb := lsb + 1.U
      }
      is(1.U) { // 2'b01
        x := Cat(0.U(1.W), x(N-1, 1))
      }
      is(2.U) { // 2'b10
        y := Cat(0.U(1.W), y(N-1, 1))
      }
      is(3.U) { // 2'b11
        when(x < y) {
          y := Cat(0.U(1.W), diff(N-1, 1))
        }.otherwise {
          x := Cat(0.U(1.W), diff(N-1, 1))
        }
      }
    }
  }.elsewhen(done) {
    oReg := Mux(x < y, x, y)
  }
  
  // Controller logic
  when(!busyReg) {
    when(io.start) {
      busyReg := true.B
    }
  }.otherwise {
    when(done) {
      busyReg := false.B
    }
  }
  
  // Connect outputs
  io.busy := busyReg
  io.o := oReg
  
  // ====== Formal Verification Assertions ======
  
  // Safety: load and done must never be active simultaneously
  // load requires !busyReg, done requires busyReg → guaranteed by logic, but verify
  fvAssert(!(load && done), "load_and_done_mutex")
  
  // Safety: done implies busyReg
  fvAssert(!done || busyReg, "done_implies_busy")
  
  // Safety: load implies not busyReg
  fvAssert(!load || !busyReg, "load_implies_not_busy")
  
  // Safety: Output correctness when done with equal numbers
  // GCD(x, x) = x, so when x === y, the result must equal x (and y)
  fvAssert(!(done && x === y) || oReg === x, "gcd_result_equal_numbers")
  
  // Safety: Output correctness when done with x === 0
  // GCD(0, y) = y, but the logic computes oReg = Mux(x < y, x, y) = Mux(0 < y, 0, y) = 0
  // This assertion is EXPECTED TO FAIL, revealing a bug
  fvAssert(!(done && x === 0.U) || oReg === y, "gcd_result_x_is_zero")
  
  // Safety: Output correctness when done with y === 0
  // GCD(x, 0) = x, but the logic computes oReg = Mux(x < y, x, y) = Mux(x < 0, x, 0) = 0
  // This assertion is EXPECTED TO FAIL, revealing a bug
  fvAssert(!(done && y === 0.U) || oReg === x, "gcd_result_y_is_zero")
  
  // Liveness: Once a computation starts (load fires), it must complete (done fires)
  // within a bounded number of cycles. For N=8-bit binary GCD, the worst-case
  // number of iterations is well under 200 cycles.
  astRelaxedLiveness(load, done, 200, "bounded_progress_load_to_done")
  
  // Liveness: Once busy, the computation must eventually finish
  // This catches stuck-at-busy bugs
  astRelaxedLiveness(busyReg, done, 200, "bounded_progress_busy_to_done")
  
  // Safety: Output should not change when not done (stable result)
  // When !done, oReg must retain its value (structural: oReg only updated in done branch)
  // This catches any unintended write to oReg
  assertStable(oReg, "output_stable_when_idle")
}
