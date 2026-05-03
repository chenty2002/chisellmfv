package llmverify

import chisel3._
import chisel3.util._

// Slider puzzle reduced to 2 rows and four columns. +----+----+----+----+
//                                                   |  0 |  1 |  2 |  3 |
// The entries of the matrix are numbered thus:      +----+----+----+----+
//                                                   |  4 |  5 |  6 |  7 |
// Author: Fabio Somenzi <Fabio@Colorado.EDU>        +----+----+----+----+

class twoByFour extends Module {
  val io = IO(new Bundle {
    val from = Input(UInt(3.W))
    val to = Input(UInt(3.W))
    // Add outputs to preserve the design
    val b_out = Output(Vec(8, UInt(3.W)))
    val valid_out = Output(Bool())
    val parity_out = Output(Bool())
  })

  // Register array b[0:7], each 3 bits
  val b = RegInit(VecInit(
    7.U(3.W), 6.U(3.W), 5.U(3.W), 4.U(3.W),
    3.U(3.W), 2.U(3.W), 1.U(3.W), 0.U(3.W)
  ))

  // Registers for from and to
  val freg = RegInit(0.U(3.W))
  val treg = RegInit(0.U(3.W))

  // Wire assignments
  val valid = Wire(Bool())
  val parity = Wire(Bool())

  // Valid logic
  valid := (b(treg) === 0.U) && (
    ((treg(1, 0) === freg(1, 0)) && (treg(2) =/= freg(2))) ||
    (treg(2) === freg(2)) && (
      ((treg(1, 0) === "b00".U) && (freg(1, 0) === "b01".U)) ||
      ((treg(1, 0) === "b01".U) && (freg(0) === 0.U)) ||
      ((treg(1, 0) === "b10".U) && (freg(0) === 1.U)) ||
      ((treg(1, 0) === "b11".U) && (freg(1, 0) === "b10".U))
    )
  )

  // Parity logic - XOR of all conditions
  def parityCondition(x: UInt): Bool = {
    ((x & 5.U) === 1.U) || ((x & 5.U) === 4.U)
  }

  def parityCondition2(x: UInt): Bool = {
    ((x & 5.U) === 0.U) || ((x & 5.U) === 5.U)
  }

  parity := parityCondition(b(0)) ^
           parityCondition2(b(1)) ^
           parityCondition(b(2)) ^
           parityCondition2(b(3)) ^
           parityCondition(b(4)) ^
           parityCondition2(b(5)) ^
           parityCondition(b(6)) ^
           parityCondition2(b(7))

  // Sequential logic
  when(valid) {
    b(treg) := b(freg)
    b(freg) := 0.U
  }

  freg := io.from
  treg := io.to

  // Connect outputs to preserve the design
  io.b_out := b
  io.valid_out := valid
  io.parity_out := parity
}

object VerilogGenerator extends App {
  emitVerilog(new twoByFour(), args)
}