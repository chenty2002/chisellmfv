package llmverify

import chisel3._
import chisel3.util._

class hanoi extends Module {
  val io = IO(new Bundle {
    val from = Input(UInt(2.W))
    val to = Input(UInt(2.W))
    val done = Output(Bool())
  })

  // Constants
  val N = 5
  val MSB = 2
  
  // Peg constants (representing A, B, C)
  val pegA = 0.U(2.W)
  val pegB = 1.U(2.W)
  val pegC = 2.U(2.W)

  // Peg arrays - storing disc sizes
  val pegA_reg = RegInit(VecInit(Seq.fill(N)(0.U((MSB+1).W))))
  val pegB_reg = RegInit(VecInit(Seq.fill(N)(0.U((MSB+1).W))))
  val pegC_reg = RegInit(VecInit(Seq.fill(N)(0.U((MSB+1).W))))

  // Counters for number of discs on each peg
  val nA_reg = RegInit(N.U((MSB+1).W))
  val nB_reg = RegInit(0.U((MSB+1).W))
  val nC_reg = RegInit(0.U((MSB+1).W))

  // Initialize pegA with discs (largest to smallest)
  // This initialization happens at time 0
  for (i <- 0 until N) {
    pegA_reg(i) := (N - i).U
  }

  // Main combinational logic for moving discs
  when(io.from === pegA && nA_reg > 0.U) {
    when(io.to === pegB && (nB_reg === 0.U || pegA_reg(nA_reg - 1.U) < pegB_reg(nB_reg - 1.U))) {
      pegB_reg(nB_reg) := pegA_reg(nA_reg - 1.U)
      nB_reg := nB_reg + 1.U
      nA_reg := nA_reg - 1.U
    }.elsewhen(io.to === pegC && (nC_reg === 0.U || pegA_reg(nA_reg - 1.U) < pegC_reg(nC_reg - 1.U))) {
      pegC_reg(nC_reg) := pegA_reg(nA_reg - 1.U)
      nC_reg := nC_reg + 1.U
      nA_reg := nA_reg - 1.U
    }
  }.elsewhen(io.from === pegB && nB_reg > 0.U) {
    when(io.to === pegA && (nA_reg === 0.U || pegB_reg(nB_reg - 1.U) < pegA_reg(nA_reg - 1.U))) {
      pegA_reg(nA_reg) := pegB_reg(nB_reg - 1.U)
      nA_reg := nA_reg + 1.U
      nB_reg := nB_reg - 1.U
    }.elsewhen(io.to === pegC && (nC_reg === 0.U || pegB_reg(nB_reg - 1.U) < pegC_reg(nC_reg - 1.U))) {
      pegC_reg(nC_reg) := pegB_reg(nB_reg - 1.U)
      nC_reg := nC_reg + 1.U
      nB_reg := nB_reg - 1.U
    }
  }.elsewhen(io.from === pegC && nC_reg > 0.U) {
    when(io.to === pegA && (nA_reg === 0.U || pegC_reg(nC_reg - 1.U) < pegA_reg(nA_reg - 1.U))) {
      pegA_reg(nA_reg) := pegC_reg(nC_reg - 1.U)
      nA_reg := nA_reg + 1.U
      nC_reg := nC_reg - 1.U
    }.elsewhen(io.to === pegB && (nB_reg === 0.U || pegC_reg(nC_reg - 1.U) < pegB_reg(nB_reg - 1.U))) {
      pegB_reg(nB_reg) := pegC_reg(nC_reg - 1.U)
      nB_reg := nB_reg + 1.U
      nC_reg := nC_reg - 1.U
    }
  }

  // Done signal - all discs on peg B
  io.done := nB_reg === N.U
}

object VerilogGenerator extends App {
  emitVerilog(new hanoi(), args)
}