package llmverify

import chisel3._
import chisel3.util._

class s1269b extends Module {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val LDAcc = Input(Bool())
    val LDMQ = Input(Bool())
    val LDDR = Input(Bool())
    val STAcc = Input(Bool())
    val STMQ = Input(Bool())
    val STDR = Input(Bool())
    val TESTMODE = Input(Bool())
    val INS = Input(UInt(3.W))
    val inBUS = Input(UInt(8.W))
    val outBUS = Output(UInt(8.W))
    val RDY = Output(Bool())
    val oLDALUout = Output(Bool())
  })

  // Registers
  val Acc_q = RegInit(0.U(8.W))
  val MQ_q = RegInit(0.U(8.W))
  val DR_q = RegInit(0.U(8.W))
  val qINSo = RegInit(0.U(3.W))
  val I679 = RegInit(false.B)
  val I680 = RegInit(false.B)
  val I681 = RegInit(false.B)
  val I682 = RegInit(false.B)
  val I683 = RegInit(false.B)
  val qLDALUout = RegInit(false.B)
  val oLDALUout_reg = RegInit(false.B)
  val qPass1 = RegInit(false.B)
  val qPass2 = RegInit(false.B)
  val qShiftRight = RegInit(false.B)

  // Combinational logic signals
  val ALUout = Wire(UInt(8.W))
  val I235 = Wire(UInt(8.W))
  val I236 = Wire(UInt(8.W))
  val I238 = Wire(UInt(8.W))
  val I240 = Wire(UInt(8.W))
  val I241 = Wire(UInt(8.W))
  val I242 = Wire(UInt(8.W))
  val I243 = Wire(UInt(8.W))
  val I246 = Wire(UInt(8.W))
  val I247 = Wire(UInt(8.W))
  val I248 = Wire(UInt(8.W))
  val I250 = Wire(UInt(8.W))
  val I783 = Wire(UInt(2.W))
  val mINSo = Wire(UInt(3.W))
  val INSo = Wire(UInt(3.W))
  val mShiftRight = Wire(Bool())
  val ShiftRight = Wire(Bool())
  val Pass1 = Wire(Bool())
  val Pass2 = Wire(Bool())
  val F = Wire(Bool())
  val mLDALUout = Wire(Bool())
  val LDALUout = Wire(Bool())
  val I710 = Wire(Bool())
  val I711 = Wire(Bool())
  val I712 = Wire(Bool())
  val I759 = Wire(Bool())
  val I686 = Wire(Bool())
  val I251 = Wire(Bool())
  val I252 = Wire(Bool())
  val I292 = Wire(Bool())
  val I306 = Wire(Bool())
  val I290 = Wire(Bool())
  val I310 = Wire(Bool())
  val I308 = Wire(Bool())
  val I296 = Wire(Bool())
  val I302 = Wire(Bool())
  val I761 = Wire(Bool())
  val I741 = Wire(Bool())
  val I737 = Wire(Bool())
  val I749 = Wire(Bool())
  val I735 = Wire(Bool())
  val I745 = Wire(Bool())
  val I751 = Wire(Bool())
  val I755 = Wire(Bool())
  val I765 = Wire(Bool())
  val I685 = Wire(Bool())
  val I757 = Wire(Bool())
  val I739 = Wire(Bool())
  val I747 = Wire(Bool())
  val I684 = Wire(Bool())
  val I688 = Wire(Bool())
  val I687 = Wire(Bool())
  val I287 = Wire(Bool())
  val I283 = Wire(Bool())
  val I281 = Wire(Bool())
  val I277 = Wire(Bool())
  val I285 = Wire(Bool())
  val I303 = Wire(Bool())
  val I279 = Wire(Bool())
  val I730 = Wire(Bool())
  val I754 = Wire(Bool())
  val I768 = Wire(Bool())
  val I744 = Wire(Bool())
  val I764 = Wire(Bool())
  val I300 = Wire(Bool())
  val I294 = Wire(Bool())
  val I298 = Wire(Bool())
  val I311 = Wire(Bool())
  val I315 = Wire(Bool())
  val I330 = Wire(Bool())
  val I337 = Wire(Bool())
  val I769 = Wire(Bool())
  val I772 = Wire(Bool())
  val I788 = Wire(Bool())
  val I791 = Wire(Bool())
  val I794 = Wire(Bool())
  val I808 = Wire(Bool())
  val I811 = Wire(Bool())
  val I816 = Wire(Bool())

  // Calculate combinational logic first
  I811 := I749 & I751
  I794 := I739 & ~I747
  I330 := ~I243(0) & I287
  I315 := I242(4) & I298
  I337 := I243(1) | I294
  I311 := ~I242(6) | I302
  I761 := ~(DR_q(7) & MQ_q(0))
  I741 := ~(I679 & I682)
  I783(1) := io.INS(1) & I683
  I783(0) := I754 & MQ_q(0) & ~I683
  INSo(2) := io.INS(2) | ~I683
  INSo(1) := I783(0) | I783(1)
  INSo(0) := io.INS(0) | ~I683
  Pass1 := I680 | I768 | io.INS(1)
  Pass2 := I747 | MQ_q(0) | ~I739
  I739 := ~(io.INS(0) & I764)
  I747 := I735 & I764
  I684 := ~I769 | I730
  I769 := I711 | I749
  I749 := ~(I710 & I681)
  I730 := ~(I710 | I788)
  I788 := I680 & I681
  I685 := ~(I772 & I751)
  I772 := I680 | I712
  I751 := ~(I680 & I712)
  I686 := I791 & I712
  I791 := I744 | I745
  I745 := I679 | I680
  I710 := ~I679
  I711 := ~I680
  I712 := ~I681
  I744 := ~(io.INS(0) | I735)
  I735 := ~io.INS(2) | io.INS(1)
  I687 := ~(I755 & I759 & I757 & I765)
  I755 := ~I737 | I761
  I757 := I808 | ~I711
  I765 := ~(I816 & I754 & I761)
  I737 := ~(I679 & I681)
  I759 := ~I682 | I811
  I754 := ~(I711 | I737)
  I808 := I761 & I741
  I816 := DR_q(7) | MQ_q(0)
  I688 := I754 | ~I739 | I747
  ShiftRight := I744 | I680 | I679 | I681
  io.RDY := I754 | I747 | ~I739
  F := ~(I755 & I757 & I759 & I765)
  I287 := ~(I242(0) & I235(0))
  I292 := I243(3) & I242(4)
  I283 := ~(I243(4) & I242(5))
  I306 := I242(5) & I242(3) & I242(4) & I242(2)
  I290 := I242(4) & I300
  I281 := ~(I242(5) & I292)
  I277 := ~(I243(1) & I306)
  I285 := ~(I294 & I306)
  I310 := I283 & I277
  I308 := I281 & I285
  I303 := I337 & I242(2)
  I279 := ~(I242(5) & I290)
  I296 := ~I243(5) & I279 & I277 & I281
  I302 := I283 & I296 & I285
  
  // ALU logic
  ALUout := Mux(mINSo(2), I236, I238)
  I236 := I235 ^ I242
  I238 := Mux(mINSo(1), I241, I240)
  I241 := Mux(mINSo(0), ~I247, I250)
  I240 := Mux(mINSo(0), I242, I243)
  I250 := I247 | I248
  I242 := I246 ^ I247
  I243 := I246 & I247
  I247 := Mux(I251, Acc_q, 0.U)
  I248 := Mux(I252, DR_q, 0.U)
  I246 := Mux(mINSo(1), ~I248, I248)
  I251 := Mux(io.TESTMODE, qPass1, Pass1)
  I252 := Mux(io.TESTMODE, qPass2, Pass2)
  
  // I235 bit assignments
  I235(0) := mINSo(1)
  I235(1) := ~I287 | I243(0)
  I235(2) := I243(1) | I294
  I235(3) := I243(2) | I303
  I235(4) := I243(3) | I298 | I300
  I235(5) := I315 | I290 | I243(4) | I292
  I235(6) := ~(~I243(5) & I279 & I310 & I308)
  I235(7) := ~I311 | I243(6)
  
  // Output bus logic
  io.outBUS := (MQ_q & Fill(8, io.STMQ)) |
               (DR_q & Fill(8, io.STDR)) |
               (Acc_q & Fill(8, io.STAcc))
  
  I768 := ~io.INS(2) | I679 | io.INS(0) | I681
  I764 := ~(I681 | I745)
  mINSo := Mux(io.TESTMODE, qINSo, INSo)
  mShiftRight := Mux(io.TESTMODE, qShiftRight, ShiftRight)
  LDALUout := ~(io.LDAcc | I794)
  mLDALUout := Mux(io.TESTMODE, qLDALUout, LDALUout)
  I300 := I243(2) & I242(3)
  I294 := I242(1) & ~I330
  I298 := I242(3) & I303
  
  io.oLDALUout := oLDALUout_reg

  // Sequential logic
  when(io.LDDR) {
    DR_q := io.inBUS
  }

  when(mShiftRight) {
    when(io.LDMQ) {
      MQ_q := Cat(ALUout(0), MQ_q(7, 1)) | io.inBUS
    }.otherwise {
      MQ_q := Cat(ALUout(0), MQ_q(7, 1))
    }
  }.elsewhen(io.LDMQ) {
    MQ_q := io.inBUS
  }

  when(mShiftRight || mLDALUout || io.LDAcc) {
    val newAcc = Wire(UInt(8.W))
    newAcc := 0.U
    when(mLDALUout) {
      newAcc := newAcc | ALUout
    }
    when(mShiftRight) {
      newAcc := newAcc | Cat(F, ALUout(7, 1))
    }
    when(io.LDAcc) {
      newAcc := newAcc | io.inBUS
    }
    Acc_q := newAcc
  }

  // Register updates
  I679 := I684
  I680 := I685
  I681 := I686
  I682 := I687
  I683 := I688
  qLDALUout := LDALUout
  oLDALUout_reg := mLDALUout
  qPass1 := Pass1
  qPass2 := Pass2
  qShiftRight := ShiftRight
  qINSo := INSo
}

object VerilogGenerator extends App {
  emitVerilog(new s1269b(), args)
}