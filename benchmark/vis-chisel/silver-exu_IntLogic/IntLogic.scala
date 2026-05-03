package llmverify

import chisel3._
import chisel3.util._

class IntLogic extends Module {
  val io = IO(new Bundle {
    val iFctCode = Input(UInt(Constants.FctWidth.W))
    val iOp1 = Input(UInt(Constants.DataQWidth.W))
    val iOp2 = Input(UInt(Constants.DataQWidth.W))
    val iConst = Input(UInt(Constants.ConstWidth.W))
    val ResData = Output(UInt(Constants.DataQWidth.W))
    val ResAdr = Output(UInt(Constants.DataQWidth.W))
    val Condition = Output(Bool())
  })

  // Register inputs on clock edge
  val fctCodeReg = RegNext(io.iFctCode, 0.U)
  val op1Reg = RegNext(io.iOp1, 0.U)
  val op2Reg = RegNext(io.iOp2, 0.U)
  val constReg = RegNext(io.iConst, 0.U)

  // Input mux
  val aluIn1 = op1Reg
  val constValid = constReg(Constants.C)  // constant valid bit
  val constMsb = constReg(Constants.CM)   // constant msb
  val constN = constReg(Constants.CM, 0)  // constant value
  val aluIn2 = Mux(constValid, Cat(Fill(Constants.Q - Constants.CM + 1, constMsb), constN), op2Reg)

  // Condition evaluator input (extended data width for carry)
  val condIn = Wire(UInt(Constants.DataXWidth.W))
  condIn := Cat(0.U(1.W), aluIn1)  // Extend with carry bit

  // Logic operations
  val aluOut = Wire(UInt(Constants.DataQWidth.W))
  aluOut := 0.U

  switch(fctCodeReg) {
    is(Opcode.INTL_AND) { aluOut := aluIn1 & aluIn2 }
    is(Opcode.INTL_BIC) { aluOut := aluIn1 & ~aluIn2 }
    is(Opcode.INTL_BIS) { aluOut := aluIn1 | aluIn2 }
    is(Opcode.INTL_EQU) { aluOut := aluIn1 ^ ~aluIn2 }
    is(Opcode.INTL_ORNOT) { aluOut := aluIn1 | ~aluIn2 }
    is(Opcode.INTL_XOR) { aluOut := aluIn1 ^ aluIn2 }
    is(Opcode.INTL_CMOVEQ) { aluOut := aluIn2 }
    is(Opcode.INTL_CMOVGE) { aluOut := aluIn2 }
    is(Opcode.INTL_CMOVGT) { aluOut := aluIn2 }
    is(Opcode.INTL_CMOVLBC) { aluOut := aluIn2 }
    is(Opcode.INTL_CMOVLBS) { aluOut := aluIn2 }
    is(Opcode.INTL_CMOVLE) { aluOut := aluIn2 }
    is(Opcode.INTL_CMOVLT) { aluOut := aluIn2 }
    is(Opcode.INTL_CMOVNE) { aluOut := aluIn2 }
  }

  // Condition evaluation
  val condition = Wire(Bool())
  condition := true.B

  switch(fctCodeReg) {
    is(Opcode.INTL_CMOVEQ) { 
      condition := (condIn(Constants.QM, 0) === 0.U) 
    }
    is(Opcode.INTL_CMOVGE) { 
      condition := (condIn(Constants.QM, 0) === 0.U) || (condIn(Constants.QM, 0) =/= 0.U && !condIn(Constants.QM)) 
    }
    is(Opcode.INTL_CMOVGT) { 
      condition := (condIn(Constants.QM, 0) =/= 0.U && !condIn(Constants.QM)) 
    }
    is(Opcode.INTL_CMOVLBC) { 
      condition := !condIn(0) 
    }
    is(Opcode.INTL_CMOVLBS) { 
      condition := condIn(0) 
    }
    is(Opcode.INTL_CMOVLE) { 
      condition := (condIn(Constants.QM, 0) === 0.U) || (condIn(Constants.QM, 0) =/= 0.U && condIn(Constants.QM)) 
    }
    is(Opcode.INTL_CMOVLT) { 
      condition := (condIn(Constants.QM, 0) =/= 0.U && condIn(Constants.QM)) 
    }
    is(Opcode.INTL_CMOVNE) { 
      condition := (condIn(Constants.QM, 0) =/= 0.U) 
    }
  }

  // Output mux
  io.ResData := aluOut
  io.ResAdr := 0.U
  io.Condition := condition
}

object VerilogGenerator extends App {
  emitVerilog(new IntLogic(), args)
}