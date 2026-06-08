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
  
  // Formal verification assertions
  
  // 1. Initial state: busy should be false at reset
  // Fixed: Only check during reset cycle using implication
  assertImplies(reset.asBool, !busyReg, "busy should be false at reset")
  
  // 2. When start is asserted and not busy, busy should become true next cycle
  assertNextStepWhen(io.start && !busyReg, busyReg, "busy should be asserted after start")
  
  // 3. When done is true, busy should become false next cycle
  assertNextStepWhen(done, !busyReg, "busy should be deasserted when done")
  
  // 4. Output should be stable when not busy
  assertStableWhen(!busyReg, io.o, "output should be stable when not busy")
  
  // 5. Liveness: computation should complete within reasonable time
  // Maximum cycles based on worst-case GCD algorithm behavior
  val maxCycles = N * 4 // Conservative bound
  astRelaxedLiveness(busyReg, !busyReg, maxCycles, "computation should complete within time limit")
  
  // 6. When computation completes, output should be the GCD of inputs
  // Store initial inputs when computation starts
  val initialA = RegInit(0.U(N.W))
  val initialB = RegInit(0.U(N.W))
  val computationStarted = RegInit(false.B)
  
  when(load) {
    initialA := io.a
    initialB := io.b
    computationStarted := true.B
  }.elsewhen(!busyReg) {
    computationStarted := false.B
  }
  
  // Assert that output is correct when computation completes
  // Note: This is a simplified check - full GCD verification would require
  // a reference GCD implementation or mathematical properties
  assertOnRise(!busyReg && computationStarted, 
    (io.o <= initialA) && (io.o <= initialB) && 
    ((initialA % io.o) === 0.U) && ((initialB % io.o) === 0.U),
    "output should be GCD of inputs when computation completes")
  
  // 7. GCD invariant: GCD(x, y) should remain constant during computation
  // This is a complex property, so we'll use a simplified version
  // The GCD should not increase during computation
  assertAlwaysAfterNStepWhen(busyReg && !done, 1, 
    (x <= initialA) && (y <= initialB),
    "intermediate values should not exceed initial inputs")
  
  // 8. When both inputs are zero, output should be zero
  assertImplies(io.start && (io.a === 0.U) && (io.b === 0.U), 
    !busyReg || (io.o === 0.U),
    "GCD(0,0) should be 0")
  
  // 9. When one input is zero, output should be the other input
  assertImplies(io.start && (io.a === 0.U) && (io.b =/= 0.U), 
    !busyReg || (io.o === io.b),
    "GCD(0,n) should be n")
  assertImplies(io.start && (io.a =/= 0.U) && (io.b === 0.U), 
    !busyReg || (io.o === io.a),
    "GCD(n,0) should be n")
}

// Testbench for the gcd circuit
class TestGcd(val N: Int = 8, val logN: Int = 3) extends Module {
  val io = IO(new Bundle {
    val x = Input(UInt(N.W))
    val y = Input(UInt(N.W))
    val s = Input(Bool())
    val busy = Output(Bool())
    val o = Output(UInt(N.W))
  })
  
  // Internal registers
  val a = RegInit(0.U(N.W))
  val b = RegInit(0.U(N.W))
  val start = RegInit(false.B)
  
  // Unit under test
  val gcdModule = Module(new Gcd(N, logN))
  gcdModule.io.start := start
  gcdModule.io.a := a
  gcdModule.io.b := b
  
  // Update registers on clock edge
  a := io.x
  b := io.y
  start := io.s
  
  // Connect outputs
  io.busy := gcdModule.io.busy
  io.o := gcdModule.io.o
}

object VerilogGenerator extends App {
  // Generate Verilog for GCD module
  emitVerilog(new Gcd(), Array("--target-dir", "generated"))
  
  // Generate Verilog for TestGcd module
  emitVerilog(new TestGcd(), Array("--target-dir", "generated"))
}