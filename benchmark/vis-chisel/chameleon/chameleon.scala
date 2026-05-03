package llmverify

import chisel3._
import chisel3.util._

object Color {
  val RED = 0.U(2.W)
  val GREEN = 1.U(2.W)
  val BLUE = 2.U(2.W)
}

class chameleon(BITS: Int = 2) extends Module {
  val io = IO(new Bundle {
    val first = Input(UInt(BITS.W))
    // Add outputs to preserve the design
    val cham_out = Output(Vec(1 << BITS, UInt(2.W)))
    val stable_out = Output(Bool())
    val select_out = Output(UInt(BITS.W))
  })
  
  val MSB = BITS - 1
  val N = 1 << BITS
  
  // Chameleon array - initialize with different colors for variety
  val chamInit = Wire(Vec(N, UInt(2.W)))
  for (i <- 0 until N) {
    chamInit(i) := (i % 3).U(2.W)
  }
  val cham = RegInit(chamInit)
  
  val select = RegInit(0.U(BITS.W))
  val stable = RegInit(true.B)
  
  val second = io.first + 1.U
  
  // Sequential logic for updates
  when(true.B) { // This always executes on positive edge
    // Latch first for fairness conditions
    select := io.first
    
    // Color update logic
    val firstCham = cham(io.first)
    val secondCham = cham(second)
    
    when(firstCham === Color.RED) {
      when(secondCham === Color.GREEN) {
        cham(io.first) := Color.BLUE
        cham(second) := Color.BLUE
      }.elsewhen(secondCham === Color.BLUE) {
        cham(io.first) := Color.GREEN
        cham(second) := Color.GREEN
      }
    }.elsewhen(firstCham === Color.GREEN) {
      when(secondCham === Color.RED) {
        cham(io.first) := Color.BLUE
        cham(second) := Color.BLUE
      }.elsewhen(secondCham === Color.BLUE) {
        cham(io.first) := Color.RED
        cham(second) := Color.RED
      }
    }.elsewhen(firstCham === Color.BLUE) {
      when(secondCham === Color.RED) {
        cham(io.first) := Color.GREEN
        cham(second) := Color.GREEN
      }.elsewhen(secondCham === Color.GREEN) {
        cham(io.first) := Color.RED
        cham(second) := Color.RED
      }
    }
    
    // Calculate stability using foldLeft to avoid combinational loops
    val newStable = (0 until N-1).foldLeft(true.B) { (acc, i) =>
      acc && (cham(i) === cham(i+1))
    }
    stable := newStable
  }
  
  // Connect outputs
  io.cham_out := cham
  io.stable_out := stable
  io.select_out := select
}

object VerilogGenerator extends App {
  emitVerilog(new chameleon(), args)
}