package llmverify

import chisel3._
import chisel3.util._

/**
 * Integer Arithmetic Unit
 * Converted from IntArith.v
 */
class IntArith extends Module {
  val io = IO(new Bundle {
    val iFctCode = Input(UInt(7.W))
    val iOp1 = Input(UInt(32.W))
    val iOp2 = Input(UInt(32.W))
    val iConst = Input(UInt(21.W))
    val ResData = Output(UInt(32.W))
    val ResAdr = Output(UInt(32.W))
    val Exception = Output(UInt(4.W))
    // Additional outputs to preserve internal signals
    val AluIn1_debug = Output(UInt(32.W))
    val AluIn2_debug = Output(UInt(32.W))
    val AluOut_debug = Output(UInt(32.W))
    val CondIn_debug = Output(UInt(32.W))
    val Carry_debug = Output(Bool())
    val Msb1_debug = Output(Bool())
    val Msb2_debug = Output(Bool())
  })

  // Internal registers
  val fctCodeReg = RegInit(0.U(7.W))
  val op1Reg = RegInit(0.U(32.W))
  val op2Reg = RegInit(0.U(32.W))
  val constReg = RegInit(0.U(21.W))

  // Register inputs on clock edge
  fctCodeReg := io.iFctCode
  op1Reg := io.iOp1
  op2Reg := io.iOp2
  constReg := io.iConst

  // Input mux
  val aluIn1 = Wire(UInt(32.W))
  val aluIn2 = Wire(UInt(32.W))
  aluIn1 := op1Reg
  
  // Constant valid bit is bit 20, constant value is bits 19:0
  val constValid = constReg(20)
  val constValue = constReg(19, 0)
  
  // Sign-extend constant if valid, otherwise use op2
  aluIn2 := Mux(constValid === 1.U, Cat(Fill(12, constValue(19)), constValue), op2Reg)

  // ALU output and condition input
  val aluOut = Wire(UInt(32.W))
  val condIn = Wire(UInt(32.W))
  
  // Default values
  aluOut := 0.U
  condIn := 0.U

  // Adder operation - create wider intermediate values for carry detection
  switch(fctCodeReg) {
    is(opcode.INTA_ADDL) {
      aluOut := aluIn1 + aluIn2
    }
    is(opcode.INTA_ADDLV) {
      aluOut := aluIn1 + aluIn2
    }
    is(opcode.INTA_ADDQ) {
      aluOut := aluIn1 + aluIn2
    }
    is(opcode.INTA_ADDQV) {
      aluOut := aluIn1 + aluIn2
    }
    is(opcode.INTA_CMPEQ) {
      val signedSum = Cat(aluIn1(31), aluIn1) + Cat(~aluIn2(31), ~aluIn2) + 1.U
      condIn := signedSum(31, 0)  // Take lower 32 bits
    }
    is(opcode.INTA_CMPLT) {
      val signedSum = Cat(aluIn1(31), aluIn1) + Cat(~aluIn2(31), ~aluIn2) + 1.U
      condIn := signedSum(31, 0)  // Take lower 32 bits
    }
    is(opcode.INTA_CMPLE) {
      val signedSum = Cat(aluIn1(31), aluIn1) + Cat(~aluIn2(31), ~aluIn2) + 1.U
      condIn := signedSum(31, 0)  // Take lower 32 bits
    }
    is(opcode.INTA_CMPULT) {
      val unsignedSum = Cat(0.U, aluIn1) + Cat(1.U, ~aluIn2) + 1.U
      condIn := unsignedSum(31, 0)  // Take lower 32 bits
    }
    is(opcode.INTA_CMPULE) {
      val unsignedSum = Cat(0.U, aluIn1) + Cat(1.U, ~aluIn2) + 1.U
      condIn := unsignedSum(31, 0)  // Take lower 32 bits
    }
    is(opcode.INTA_SUBL) {
      aluOut := aluIn1 + ~aluIn2 + 1.U
    }
    is(opcode.INTA_SUBLV) {
      aluOut := aluIn1 + ~aluIn2 + 1.U
    }
    is(opcode.INTA_SUBQ) {
      aluOut := aluIn1 + ~aluIn2 + 1.U
    }
    is(opcode.INTA_SUBQV) {
      aluOut := aluIn1 + ~aluIn2 + 1.U
    }
  }

  // Condition computation - need to reconstruct carry from the original computation
  switch(fctCodeReg) {
    is(opcode.INTA_CMPBGE) {
      // Byte comparison - simplified version
      aluOut := condIn(7, 0)
    }
    is(opcode.INTA_CMPEQ) {
      // For equality, check if the result is zero
      val signedSum = Cat(aluIn1(31), aluIn1) + Cat(~aluIn2(31), ~aluIn2) + 1.U
      aluOut := Mux(signedSum === 0.U, 1.U, 0.U)
    }
    is(opcode.INTA_CMPLT) {
      // For signed less than, check if carry bit is set
      val signedSum = Cat(aluIn1(31), aluIn1) + Cat(~aluIn2(31), ~aluIn2) + 1.U
      aluOut := Mux(signedSum(32) === 1.U, 1.U, 0.U)
    }
    is(opcode.INTA_CMPULT) {
      // For unsigned less than, check if carry bit is set
      val unsignedSum = Cat(0.U, aluIn1) + Cat(1.U, ~aluIn2) + 1.U
      aluOut := Mux(unsignedSum(32) === 1.U, 1.U, 0.U)
    }
    is(opcode.INTA_CMPLE) {
      // For less than or equal, check if result is zero OR carry is set
      val signedSum = Cat(aluIn1(31), aluIn1) + Cat(~aluIn2(31), ~aluIn2) + 1.U
      aluOut := Mux((signedSum === 0.U) || (signedSum(32) === 1.U), 1.U, 0.U)
    }
    is(opcode.INTA_CMPULE) {
      // For unsigned less than or equal, check if result is zero OR carry is set
      val unsignedSum = Cat(0.U, aluIn1) + Cat(1.U, ~aluIn2) + 1.U
      aluOut := Mux((unsignedSum === 0.U) || (unsignedSum(32) === 1.U), 1.U, 0.U)
    }
  }

  // Overflow checking
  val carry = Wire(Bool())
  val msb1 = Wire(Bool())
  val msb2 = Wire(Bool())
  val exception = Wire(UInt(4.W))
  
  carry := false.B
  msb1 := false.B
  msb2 := false.B
  exception := const.EXC_NONE

  switch(fctCodeReg) {
    is(opcode.INTA_ADDLV) {
      val sum = Cat(0.U(1.W), aluIn1(30, 0)) + Cat(0.U(1.W), aluIn2(30, 0))
      carry := sum(31)
      msb1 := aluIn1(31)
      msb2 := aluIn2(31)
    }
    is(opcode.INTA_SUBLV) {
      val sum = Cat(0.U(1.W), aluIn1(30, 0)) + Cat(0.U(1.W), ~aluIn2(30, 0)) + 1.U
      carry := sum(31)
      msb1 := aluIn1(31)
      msb2 := ~aluIn2(31)
    }
  }

  // Check overflow
  switch(fctCodeReg) {
    is(opcode.INTA_ADDLV) {
      exception := Mux((carry && !msb1 && !msb2) || (!carry && msb1 && msb2), 
                      const.EXC_OVFL, const.EXC_NONE)
    }
    is(opcode.INTA_ADDQV) {
      exception := Mux((carry && !msb1 && !msb2) || (!carry && msb1 && msb2), 
                      const.EXC_OVFL, const.EXC_NONE)
    }
    is(opcode.INTA_SUBLV) {
      exception := Mux((carry && !msb1 && !msb2) || (!carry && msb1 && msb2), 
                      const.EXC_OVFL, const.EXC_NONE)
    }
    is(opcode.INTA_SUBQV) {
      exception := Mux((carry && !msb1 && !msb2) || (!carry && msb1 && msb2), 
                      const.EXC_OVFL, const.EXC_NONE)
    }
  }

  // Output mux
  io.ResData := aluOut
  io.ResAdr := 0.U
  io.Exception := exception

  // Debug outputs
  io.AluIn1_debug := aluIn1
  io.AluIn2_debug := aluIn2
  io.AluOut_debug := aluOut
  io.CondIn_debug := condIn
  io.Carry_debug := carry
  io.Msb1_debug := msb1
  io.Msb2_debug := msb2
}

object VerilogGenerator extends App {
  emitVerilog(new IntArith(), args)
}