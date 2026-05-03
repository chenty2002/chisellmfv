package llmverify

import chisel3._
import chisel3.util._

//-----------------------------------------------------------------
//
// module IDU: Instruction Decode Unit for subset of Alpha ISA
//
// Determine the control signals necessary for executing an instruction.
//
// -------------------------------------------------------------

class IDU extends Module {
  val io = IO(new Bundle {
    // Inputs
    val iWork = Input(Bool())           // valid operation here / bubble here
    val iStep = Input(Bool())           // advance pipeline on next clk
    val iReset = Input(Bool())          // processor reset
    val iInsn = Input(UInt(Const.INSN_WIDTH.W))      // insn bus
    val iPC = Input(UInt(Const.PCADR_WIDTH.W))       // program counter (PC+4)
    val iRegAValue = Input(UInt(Const.DATAQ_WIDTH.W)) // register Ra value
    val iRegBValue = Input(UInt(Const.DATAQ_WIDTH.W)) // register Rb value

    // Outputs
    val constOut = Output(UInt(Const.CONST_WIDTH.W))     // immediate operand
    val regAOut = Output(UInt(Const.REG_WIDTH.W))       // register number source operand Ra
    val regBOut = Output(UInt(Const.REG_WIDTH.W))       // register number source operand Rb
    val regDestOut = Output(UInt(Const.REG_WIDTH.W))    // register number destination operand
    val operand1Out = Output(UInt(Const.DATAQ_WIDTH.W))  // operand 1 value
    val operand2Out = Output(UInt(Const.DATAQ_WIDTH.W))  // operand 2 value
    val opCodeOut = Output(UInt(Const.OPC_WIDTH.W))     // opcode
    val fctCodeOut = Output(UInt(Const.FCT_WIDTH.W))    // function code
    val decodeOut = Output(UInt(Decode.DEC_WIDTH.W))     // decoded insn bits
    val exceptionOut = Output(UInt(Const.EXC_WIDTH.W))  // exception request from IDU
  })

  //
  // delayed internal wires
  //
  val I_Work = Wire(Bool())
  val I_Step = Wire(Bool())
  val I_Reset = Wire(Bool())
  val I_Insn = Wire(UInt(Const.INSN_WIDTH.W))
  val I_PC = Wire(UInt(Const.PCADR_WIDTH.W))
  val I_RegAValue = Wire(UInt(Const.DATAQ_WIDTH.W))
  val I_RegBValue = Wire(UInt(Const.DATAQ_WIDTH.W))

  val I_Const = Wire(UInt(Const.CONST_WIDTH.W))
  val I_RegA = Wire(UInt(Const.REG_WIDTH.W))
  val I_RegB = Wire(UInt(Const.REG_WIDTH.W))
  val I_RegDest = Wire(UInt(Const.REG_WIDTH.W))
  val I_Operand1 = Wire(UInt(Const.DATAQ_WIDTH.W))
  val I_Operand2 = Wire(UInt(Const.DATAQ_WIDTH.W))
  val I_OpCode = Wire(UInt(Const.OPC_WIDTH.W))
  val I_FctCode = Wire(UInt(Const.FCT_WIDTH.W))
  val I_Decode = Wire(UInt(Decode.DEC_WIDTH.W))
  val I_Exception = Wire(UInt(Const.EXC_WIDTH.W))

  //
  // registered internal wires
  //
  val R_Work = Wire(Bool())
  val R_Insn = Wire(UInt(Const.INSN_WIDTH.W))
  val R_PC = Wire(UInt(Const.PCADR_WIDTH.W))

  // Register inputs
  val workReg = RegNext(io.iWork, false.B)
  val stepReg = RegNext(io.iStep, false.B)
  val resetReg = RegNext(io.iReset, true.B)
  val insnReg = RegNext(io.iInsn, 0.U)
  val pcReg = RegNext(io.iPC, 0.U)
  val regAValueReg = RegNext(io.iRegAValue, 0.U)
  val regBValueReg = RegNext(io.iRegBValue, 0.U)

  // assigning delayed wires
  I_Work := workReg
  I_Step := stepReg
  I_Reset := resetReg
  I_Insn := insnReg
  I_PC := pcReg
  I_RegAValue := regAValueReg
  I_RegBValue := regBValueReg

  // Pipeline submodule
  val pipeline = Module(new IDU_Pipeline())
  pipeline.io.Clk := this.clock
  pipeline.io.iStep := I_Step
  pipeline.io.iReset := I_Reset
  pipeline.io.iWork := I_Work
  pipeline.io.iInsn := I_Insn
  pipeline.io.iPC := I_PC
  R_Work := pipeline.io.R_Work
  R_Insn := pipeline.io.R_Insn
  R_PC := pipeline.io.R_PC

  // Logic submodule
  val logic = Module(new IDU_Logic())
  logic.io.iWork := R_Work
  logic.io.iInsn := R_Insn
  logic.io.iPC := R_PC
  logic.io.iRegAValue := I_RegAValue
  logic.io.iRegBValue := I_RegBValue
  I_Const := logic.io.constOut
  I_Operand1 := logic.io.operand1Out
  I_Operand2 := logic.io.operand2Out
  I_RegDest := logic.io.regDestOut
  I_OpCode := logic.io.opCodeOut
  I_FctCode := logic.io.fctCodeOut
  I_Decode := logic.io.decodeOut
  I_Exception := logic.io.exceptionOut
  I_RegA := logic.io.regAOut
  I_RegB := logic.io.regBOut

  // Assign outputs
  io.constOut := I_Const
  io.regAOut := I_RegA
  io.regBOut := I_RegB
  io.regDestOut := I_RegDest
  io.operand1Out := I_Operand1
  io.operand2Out := I_Operand2
  io.opCodeOut := I_OpCode
  io.fctCodeOut := I_FctCode
  io.decodeOut := I_Decode
  io.exceptionOut := I_Exception
}

//----------------------------------------------------------------------------
// synchronous interface stage of IDU
// latch incoming signals on rising edge of clock if pipeline stage is enabled
//----------------------------------------------------------------------------
class IDU_Pipeline extends Module {
  val io = IO(new Bundle {
    // Inputs
    val Clk = Input(Clock())          // system clock
    val iStep = Input(Bool())          // latch new input on clk
    val iReset = Input(Bool())         // reset
    val iWork = Input(Bool())          // valid operation here / bubble here
    val iInsn = Input(UInt(Const.INSN_WIDTH.W))  // instruction bus
    val iPC = Input(UInt(Const.PCADR_WIDTH.W))   // program counter (PC+4)

    // Outputs
    val R_Work = Output(Bool())        // latched work
    val R_Insn = Output(UInt(Const.INSN_WIDTH.W)) // latched instruction
    val R_PC = Output(UInt(Const.PCADR_WIDTH.W))  // latched PC
  })

  // Internal registers
  val rWorkReg = RegInit(false.B)
  val rInsnReg = RegInit(0.U(Const.INSN_WIDTH.W))
  val rPCReg = RegInit(0.U(Const.PCADR_WIDTH.W))

  // When reset, purge pipeline stage
  when(io.iReset) {
    rWorkReg := Const.FALSE
  }.elsewhen(io.iStep) {
    rWorkReg := io.iWork
    rInsnReg := io.iInsn
    rPCReg := io.iPC
  }.otherwise {
    rWorkReg := io.iWork
  }

  // Assign outputs
  io.R_Work := rWorkReg
  io.R_Insn := rInsnReg
  io.R_PC := rPCReg
}

//----------------------------------------------------------------------------
//
// This module determines the control signals necessary
// for execution of the present instruction.
//
//----------------------------------------------------------------------------
class IDU_Logic extends Module {
  val io = IO(new Bundle {
    // Inputs
    val iWork = Input(Bool())          // valid insn in pipeline
    val iInsn = Input(UInt(Const.INSN_WIDTH.W))  // instruction bus
    val iPC = Input(UInt(Const.PCADR_WIDTH.W))   // program counter (PC+4)
    val iRegAValue = Input(UInt(Const.DATAQ_WIDTH.W)) // register Ra value
    val iRegBValue = Input(UInt(Const.DATAQ_WIDTH.W)) // register Rb value

    // Outputs
    val constOut = Output(UInt(Const.CONST_WIDTH.W))     // immediate operand
    val regAOut = Output(UInt(Const.REG_WIDTH.W))       // register number operand A
    val regBOut = Output(UInt(Const.REG_WIDTH.W))       // register number operand B
    val regDestOut = Output(UInt(Const.REG_WIDTH.W))    // register number destination operand
    val operand1Out = Output(UInt(Const.DATAQ_WIDTH.W))  // operand 1 value
    val operand2Out = Output(UInt(Const.DATAQ_WIDTH.W))  // operand 2 value
    val opCodeOut = Output(UInt(Const.OPC_WIDTH.W))     // opcode
    val fctCodeOut = Output(UInt(Const.FCT_WIDTH.W))    // opcode sub-function
    val decodeOut = Output(UInt(Decode.DEC_WIDTH.W))     // decoded insn
    val exceptionOut = Output(UInt(Const.EXC_WIDTH.W))  // exception request from IDU
  })

  // Register inputs
  val workReg = RegNext(io.iWork, false.B)
  val insnReg = RegNext(io.iInsn, 0.U)
  val pcReg = RegNext(io.iPC, 0.U)
  val regAValueReg = RegNext(io.iRegAValue, 0.U)
  val regBValueReg = RegNext(io.iRegBValue, 0.U)

  // Internal signals
  val constReg = RegInit(0.U(Const.CONST_WIDTH.W))
  val regAIntReg = RegInit(0.U(Const.REG_WIDTH.W))
  val regBIntReg = RegInit(0.U(Const.REG_WIDTH.W))
  val regDestIntReg = RegInit(0.U(Const.REG_WIDTH.W))
  val operand1Reg = RegInit(0.U(Const.DATAQ_WIDTH.W))
  val operand2Reg = RegInit(0.U(Const.DATAQ_WIDTH.W))
  val opCodeReg = RegInit(0.U(Const.OPC_WIDTH.W))
  val fctCodeReg = RegInit(0.U(Const.FCT_WIDTH.W))
  val decodeReg = RegInit(0.U(Decode.DEC_WIDTH.W))
  val exceptionReg = RegInit(Const.EXC_NONE)

  // opcode is always at same position
  opCodeReg := insnReg(Decode.POS_OPCODE._1, Decode.POS_OPCODE._2)

  // exceptions
  when(workReg) {
    val workAndOpCode = Cat(workReg, opCodeReg)
    exceptionReg := MuxCase(Const.EXC_NONE, Seq(
      // PAL codes
      (workAndOpCode === "b1_00_0000".U) -> Const.EXC_PAL,
      (workAndOpCode === "b1_01_1110".U) -> Const.EXC_PAL,
      
      // reserved opcodes
      (workAndOpCode === "b1_00_1101".U) -> Const.EXC_RESV,
      
      // floating point opcodes
      (workAndOpCode === "b1_11_0001".U) -> Const.EXC_FP,
      (workAndOpCode === "b1_11_0010".U) -> Const.EXC_FP,
      
      // load locked / store conditional
      (workAndOpCode === "b1_10_1010".U) -> Const.EXC_LDLSTC,
      (workAndOpCode === "b1_10_1011".U) -> Const.EXC_LDLSTC,
      (workAndOpCode === "b1_10_1110".U) -> Const.EXC_LDLSTC,
      (workAndOpCode === "b1_10_1111".U) -> Const.EXC_LDLSTC
    ))
  }.otherwise {
    exceptionReg := Const.EXC_NONE
  }

  // generate the decode bits
  decodeReg := 0.U
  when(workReg) {
    // horizontal encoding (1 bit per insn class)
    switch(opCodeReg) {
      is(Opcode.OP_INTA, Opcode.OP_INTL, Opcode.OP_INTS, Opcode.OP_INTM) {
        decodeReg(Decode.DEC_ALU) := Const.TRUE
      }
      is(Opcode.OP_LDA, Opcode.OP_LDAH, Opcode.OP_LDL, Opcode.OP_LDQ, 
         Opcode.OP_LDL_L, Opcode.OP_LDQ_L, Opcode.OP_LDQ_U,
         Opcode.OP_STL, Opcode.OP_STQ, Opcode.OP_STL_C, Opcode.OP_STQ_C,
         Opcode.OP_STQ_U) {
        decodeReg(Decode.DEC_MEM) := Const.TRUE
      }
      is(Opcode.OP_BLBC, Opcode.OP_BEQ, Opcode.OP_BLT, Opcode.OP_BLE,
         Opcode.OP_BLBS, Opcode.OP_BNE, Opcode.OP_BGE, Opcode.OP_BGT,
         Opcode.OP_BR, Opcode.OP_BSR, Opcode.OP_JMP) {
        decodeReg(Decode.DEC_CTR) := Const.TRUE
      }
    }

    // vertical encoding (shared bits)
    switch(opCodeReg) {
      is(Opcode.OP_INTM) {
        decodeReg(Decode.DEC_ALU_MULT) := Const.TRUE
      }
      is(Opcode.OP_STL, Opcode.OP_STQ, Opcode.OP_STL_C, Opcode.OP_STQ_C, Opcode.OP_STQ_U) {
        decodeReg(Decode.DEC_MEM_ST) := Const.TRUE
      }
      is(Opcode.OP_BLBC, Opcode.OP_BEQ, Opcode.OP_BLT, Opcode.OP_BLE,
         Opcode.OP_BLBS, Opcode.OP_BNE, Opcode.OP_BGE, Opcode.OP_BGT) {
        decodeReg(Decode.DEC_CTR_COND) := Const.TRUE
      }
    }

    switch(opCodeReg) {
      is(Opcode.OP_LDA, Opcode.OP_LDAH, Opcode.OP_LDQ, Opcode.OP_LDQ_L,
         Opcode.OP_LDQ_U, Opcode.OP_STQ, Opcode.OP_STQ_C, Opcode.OP_STQ_U) {
        decodeReg(Decode.DEC_MEM_QW) := Const.TRUE
      }
      is(Opcode.OP_BLBC, Opcode.OP_BEQ, Opcode.OP_BLT, Opcode.OP_BLE,
         Opcode.OP_BLBS, Opcode.OP_BNE, Opcode.OP_BGE, Opcode.OP_BGT,
         Opcode.OP_BR, Opcode.OP_BSR) {
        decodeReg(Decode.DEC_CTR_PC) := Const.TRUE
      }
    }

    switch(opCodeReg) {
      is(Opcode.OP_LDL, Opcode.OP_LDQ, Opcode.OP_LDL_L, Opcode.OP_LDQ_L,
         Opcode.OP_LDQ_U, Opcode.OP_STL, Opcode.OP_STQ, Opcode.OP_STL_C,
         Opcode.OP_STQ_C, Opcode.OP_STQ_U) {
        decodeReg(Decode.DEC_MEM_ACC) := Const.TRUE
      }
    }
  }

  // mux register numbers
  when((decodeReg(Decode.DEC_CTR) && !decodeReg(Decode.DEC_CTR_COND)) || // uncond ctr
       (decodeReg(Decode.DEC_MEM) && !decodeReg(Decode.DEC_MEM_ST))) {    // ld
    regAIntReg := Cat(Const.FALSE, insnReg(Decode.POS_REGA._1, Decode.POS_REGA._2))
  }.otherwise {
    regAIntReg := Cat(Const.TRUE, insnReg(Decode.POS_REGA._1, Decode.POS_REGA._2))
  }

  // Rb
  when((decodeReg(Decode.DEC_CTR) && decodeReg(Decode.DEC_CTR_PC)) || // bra encoding
       (decodeReg(Decode.DEC_ALU) && insnReg(12))) {                    // op immediate encoding
    regBIntReg := Cat(Const.FALSE, insnReg(Decode.POS_REGB._1, Decode.POS_REGB._2))
  }.otherwise {
    regBIntReg := Cat(Const.TRUE, insnReg(Decode.POS_REGB._1, Decode.POS_REGB._2))
  }

  // RDest
  when(decodeReg(Decode.DEC_ALU)) {
    regDestIntReg(4, 0) := insnReg(Decode.POS_REGC._1, Decode.POS_REGC._2)
    when(insnReg(Decode.POS_REGC._1, Decode.POS_REGC._2) === 31.U) {
      regDestIntReg(5) := Const.FALSE
    }.otherwise {
      regDestIntReg(5) := Const.TRUE
    }
  }.elsewhen((decodeReg(Decode.DEC_MEM) && !decodeReg(Decode.DEC_MEM_ST)) ||
             (decodeReg(Decode.DEC_CTR) && !decodeReg(Decode.DEC_CTR_COND))) {
    regDestIntReg(4, 0) := insnReg(Decode.POS_REGA._1, Decode.POS_REGA._2)
    when(insnReg(Decode.POS_REGA._1, Decode.POS_REGA._2) === 31.U) {
      regDestIntReg(5) := Const.FALSE
    }.otherwise {
      regDestIntReg(5) := Const.TRUE
    }
  }.otherwise {
    regDestIntReg(5) := Const.FALSE
  }

  // mux function code bus
  when(decodeReg(Decode.DEC_CTR) && !decodeReg(Decode.DEC_CTR_PC)) {
    fctCodeReg := insnReg(Decode.POS_HINT._1, Decode.POS_HINT._2)
  }.otherwise {
    fctCodeReg := insnReg(Decode.POS_FUNCTION._1, Decode.POS_FUNCTION._2)
  }

  // mux constant bus
  when(decodeReg(Decode.DEC_ALU)) {
    when(insnReg(12)) {
      constReg(Const.CONST_WIDTH-1) := Const.TRUE
      constReg(Const.CONSTN_WIDTH-1, 0) := insnReg(Decode.POS_IMMEDIATE._1, Decode.POS_IMMEDIATE._2)
    }.otherwise {
      constReg(Const.CONST_WIDTH-1) := Const.FALSE
    }
  }.elsewhen(decodeReg(Decode.DEC_MEM)) {
    constReg(Const.CONSTN_WIDTH-1, 0) := Cat(Fill(5, insnReg(Decode.POS_DISPHI)),
                                            insnReg(Decode.POS_DISP._1, Decode.POS_DISP._2))
  }.elsewhen(decodeReg(Decode.DEC_CTR) && decodeReg(Decode.DEC_CTR_PC)) {
    constReg(Const.CONSTN_WIDTH-1, 0) := insnReg(Decode.POS_IMMEDIATE._1, Decode.POS_IMMEDIATE._2)
  }

  // mux operand busses
  when(decodeReg(Decode.DEC_CTR)) {
    when(decodeReg(Decode.DEC_CTR_COND)) {
      operand1Reg := regAValueReg
      operand2Reg := pcReg
    }.otherwise {
      operand1Reg := pcReg
      operand2Reg := regBValueReg
    }
  }.otherwise {
    operand1Reg := regAValueReg
    operand2Reg := regBValueReg
  }

  // qualify register valid signals
  val regAWire = Wire(UInt(Const.REG_WIDTH.W))
  val regBWire = Wire(UInt(Const.REG_WIDTH.W))
  val regDestWire = Wire(UInt(Const.REG_WIDTH.W))

  regAWire := Cat(regAIntReg(5) && workReg, regAIntReg(4, 0))
  regBWire := Cat(regBIntReg(5) && workReg, regBIntReg(4, 0))
  regDestWire := Cat(regDestIntReg(5) && workReg, regDestIntReg(4, 0))

  // Assign outputs
  io.constOut := constReg
  io.regAOut := regAWire
  io.regBOut := regBWire
  io.regDestOut := regDestWire
  io.operand1Out := operand1Reg
  io.operand2Out := operand2Reg
  io.opCodeOut := opCodeReg
  io.fctCodeOut := fctCodeReg
  io.decodeOut := decodeReg
  io.exceptionOut := exceptionReg
}

object IDUVerilogGenerator extends App {
  emitVerilog(new IDU(), args)
}