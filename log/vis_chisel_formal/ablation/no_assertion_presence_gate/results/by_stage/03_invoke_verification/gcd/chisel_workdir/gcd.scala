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
  
  // ========== Formal Verification Assertions ==========
  
  // 1. Bounded liveness: once computation starts (load), it must finish (done) within 200 cycles
  // For N=8 bit numbers, binary GCD converges in well under 200 iterations
  astRelaxedLiveness(load, done, 200, "completion_within_200_cycles")
  
  // 2. Output stability when not busy: output should not change while idle
  assertStableWhen(!busyReg, io.o, "output_stable_when_idle")
  
  // 3. Controller safety: when done fires, busy must go low in the next cycle
  assertNextStepWhen(done, !busyReg, "done_clears_busy")
  
  // 4. Controller safety: when load fires, busy must go high in the next cycle
  assertNextStepWhen(load, busyReg, "load_sets_busy")
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
