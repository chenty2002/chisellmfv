package llmverify

import chisel3._
import chisel3.util._

class product(val MSB: Int = 7) extends Module {
  val io = IO(new Bundle {
    val clear = Input(Bool())
    val enable = Input(Bool())
    val reset = Input(Bool())
    val in = Input(UInt((MSB + 1).W))
    val equal = Output(Bool())
    // Add outputs to preserve internal signals
    val out1 = Output(UInt((MSB + 1).W))
    val out2 = Output(UInt((MSB + 1).W))
  })
  
  // Instantiate minMax and minMaxR modules
  val mm = Module(new minMax(MSB))
  val mmr = Module(new minMaxR(MSB))
  
  // Connect inputs
  mm.io.clear := io.clear
  mm.io.enable := io.enable
  mm.io.reset := io.reset
  mm.io.in := io.in
  
  mmr.io.clear := io.clear
  mmr.io.enable := io.enable
  mmr.io.reset := io.reset
  mmr.io.in := io.in
  
  // Get outputs
  val out1 = mm.io.out
  val out2 = mmr.io.out
  
  // Register for equal signal
  val equalReg = RegInit(true.B)
  
  // Update equal on clock edge
  when(true.B) { // Always block on posedge clock
    equalReg := (out1 === out2)
  }
  
  // Connect outputs
  io.equal := equalReg
  io.out1 := out1
  io.out2 := out2
}

class minMax(val MSB: Int = 7) extends Module {
  val io = IO(new Bundle {
    val clear = Input(Bool())
    val enable = Input(Bool())
    val reset = Input(Bool())
    val in = Input(UInt((MSB + 1).W))
    val out = Output(UInt((MSB + 1).W))
  })
  
  // Registers
  val min = RegInit(UInt((MSB + 1).W), Fill(MSB + 1, 1.U)) // all ones
  val last = RegInit(UInt((MSB + 1).W), io.in) // nondeterministic initial state
  val max = RegInit(0.U((MSB + 1).W))
  
  // Wires
  val sup = Wire(UInt((MSB + 1).W))
  val inf = Wire(UInt((MSB + 1).W))
  val avg = Wire(UInt((MSB + 1).W))
  val aux = Wire(Bool())
  
  // Next state logic
  sup := Mux(io.in > max, io.in, max) // unsigned comparison
  inf := Mux(io.in < min, io.in, min)
  
  // Average calculation: {avg,aux} = {1'b0,sup} + {1'b0,inf}
  val sum = (0.U ## sup) + (0.U ## inf)
  avg := sum(MSB + 1, 1)
  aux := sum(0)
  
  // Sequential logic
  when(io.clear) {
    last := 0.U
    max := 0.U
    min := Fill(MSB + 1, 1.U)
  }.elsewhen(!io.enable) {
    max := 0.U
    min := Fill(MSB + 1, 1.U)
  }.elsewhen(io.reset) {
    last := io.in
    max := 0.U
    min := Fill(MSB + 1, 1.U)
  }.otherwise {
    last := io.in
    max := sup
    min := inf
  }
  
  // Output logic
  io.out := Mux(io.clear, 0.U,
    Mux(!io.enable, last,
      Mux(io.reset, io.in, avg)))
}

class minMaxR(val MSB: Int = 7) extends Module {
  val io = IO(new Bundle {
    val clear = Input(Bool())
    val enable = Input(Bool())
    val reset = Input(Bool())
    val in = Input(UInt((MSB + 1).W))
    val out = Output(UInt((MSB + 1).W))
  })
  
  // Registers
  val rmin = RegInit(UInt(MSB.W), Fill(MSB, 1.U)) // all ones
  val last = RegInit(UInt((MSB + 1).W), io.in) // nondeterministic initial state
  val rmax = RegInit(0.U(MSB.W))
  val shared = RegInit(io.in(MSB)) // make sure shared == last[MSB]
  
  // Wires
  val min = Wire(UInt((MSB + 1).W))
  val max = Wire(UInt((MSB + 1).W))
  val inf = Wire(UInt((MSB + 1).W))
  val sup = Wire(UInt((MSB + 1).W))
  val avg = Wire(UInt((MSB + 1).W))
  val aux = Wire(Bool())
  val flag = Wire(Bool())
  
  // Next state logic
  flag := (shared === last(MSB)) && rmin(MSB - 1) && !rmax(MSB - 1)
  min := Cat(flag || (shared && last(MSB)), rmin)
  max := Cat(!flag && (shared || last(MSB)), rmax)
  inf := Mux(io.in < min, io.in, min) // unsigned comparison
  sup := Mux(io.in > max, io.in, max)
  
  // Average calculation: {avg,aux} = {1'b0,sup} + {1'b0,inf}
  val sum = (0.U ## sup) + (0.U ## inf)
  avg := sum(MSB + 1, 1)
  aux := sum(0)
  
  // Sequential logic
  when(io.clear) {
    last := 0.U
    rmax := 0.U
    rmin := Fill(MSB, 1.U)
    shared := 0.U
  }.elsewhen(!io.enable) {
    rmax := 0.U
    rmin := Fill(MSB, 1.U)
    shared := last(MSB)
  }.elsewhen(io.reset) {
    last := io.in
    rmax := 0.U
    rmin := Fill(MSB, 1.U)
    shared := io.in(MSB)
  }.otherwise {
    last := io.in
    rmax := sup(MSB - 1, 0)
    rmin := inf(MSB - 1, 0)
    shared := Mux(io.in(MSB), inf(MSB), sup(MSB))
  }
  
  // Output logic
  io.out := Mux(io.clear, 0.U,
    Mux(!io.enable, last,
      Mux(io.reset, io.in, avg)))
}

object VerilogGenerator extends App {
  emitVerilog(new product(), args)
}