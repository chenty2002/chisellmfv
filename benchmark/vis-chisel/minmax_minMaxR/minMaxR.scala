package llmverify

import chisel3._
import chisel3.util._

class minMaxR(val MSB: Int = 8) extends Module {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val clear = Input(Bool())
    val enable = Input(Bool())
    val reset = Input(Bool())
    val in = Input(UInt((MSB + 1).W))
    val out = Output(UInt((MSB + 1).W))
  })
  
  // Registers
  val rmin = RegInit(UInt((MSB).W), Fill(MSB, 1.U)) // fill rmin with all ones
  val last = RegInit(UInt((MSB + 1).W), io.in) // nondeterministic initial state
  val rmax = RegInit(UInt((MSB).W), 0.U)
  val shared = RegInit(Bool(), io.in(MSB)) // make sure shared == last[MSB]
  
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
  
  // Average of min and max
  val sum = Cat(0.U(1.W), sup) + Cat(0.U(1.W), inf)
  avg := sum(MSB, 1) // upper bits
  aux := sum(0) // lower bit
  
  // Sequential logic
  when(io.clear) {
    last := 0.U
    rmax := 0.U
    rmin := Fill(MSB, 1.U)
    shared := 0.U
  }.otherwise {
    when(!io.enable) {
      rmax := 0.U
      rmin := Fill(MSB, 1.U)
      shared := last(MSB)
    }.otherwise {
      last := io.in
      when(io.reset) {
        rmax := 0.U
        rmin := Fill(MSB, 1.U)
        shared := io.in(MSB)
      }.otherwise {
        rmax := sup(MSB - 1, 0)
        rmin := inf(MSB - 1, 0)
        shared := Mux(io.in(MSB), inf(MSB), sup(MSB))
      }
    }
  }
  
  // Output logic
  io.out := Mux(io.clear, 0.U,
    Mux(!io.enable, last,
      Mux(io.reset, io.in, avg)))
}

object VerilogGenerator extends App {
  emitVerilog(new minMaxR(), args)
}