package llmverify

import chisel3._
import chisel3.util._

// Very Simple Architecture 16-bit. Revision A.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>
//
// This very simple microprocessor is vaguely inspired to Hennessy and
// Patterson's DLX. It has been further simplified to make it amenable
// to reachability analysis.
//
// This processor has no pipelining and no interrupts.
// The instruction sets consists of a handful of must-have:
//     LW
//     SW
//     BEQZ
//     ADD(I)  [i.e., both ADD and ADDI]
//     SUB(I)
//     AND
//     OR
//     XOR
//     SRL
//
// All instructions are 16 bits and they have one of two formats (width of
// the fields in parentheses):
//
// R-format: opcode (3) source1 (2) source2 (2) destination (2) function (7)
// I-format: opcode (3) source1 (2) destination (2) immediate (9)
//
// The R-format instructions are: ADD, SUB, AND, OR, XOR, SRL. 
// The other instructions are I-format.
//
// There are 3 general-purpose 16-bit registers (R1, R2, R3) that can act as
// source or destinations for the various instructions. R0 is always 0.
//
// All instructions execute in exactly 5 clock cycles.
// The program counter has only 12 bits to reduce the sequential depth
// of the FSM.

class vsa16a extends Module {
  val io = IO(new Bundle {
    val PC = Output(UInt(12.W))
    val instruction = Input(UInt(16.W))
    val ALUOutput = Output(UInt(16.W))
    val datain = Input(UInt(16.W))
    val dataout = Output(UInt(16.W))
    val wr = Output(Bool())
    
    // Additional outputs to preserve internal signals
    val NPC = Output(UInt(12.W))
    val IR = Output(UInt(16.W))
    val A = Output(UInt(16.W))
    val B = Output(UInt(16.W))
    val Cond = Output(Bool())
    val LMD = Output(UInt(16.W))
    val State = Output(UInt(3.W))
    val Registers = Output(Vec(4, UInt(16.W)))
  })

  // Register file
  val Registers = RegInit(VecInit(Seq.fill(4)(0.U(16.W))))
  
  // Internal registers
  val PC = RegInit(0.U(12.W))
  val NPC = RegInit(0.U(12.W))
  val IR = RegInit(0.U(16.W))
  val A = RegInit(0.U(16.W))
  val B = RegInit(0.U(16.W))
  val ALUOutput = RegInit(0.U(16.W))
  val Cond = RegInit(false.B)
  val LMD = RegInit(0.U(16.W))
  val State = RegInit(0.U(3.W))

  // Interesting fields of the instruction register.
  val opcode = IR(2, 0)
  val adFld1 = IR(4, 3)
  val adFld2 = IR(6, 5)
  val adFld3 = IR(8, 7)
  val immFld = IR(15, 7)
  val funFld = IR(15, 9)

  // Control states.
  val IF = 0.U(3.W)
  val ID = 1.U(3.W)
  val EX = 2.U(3.W)
  val MEM = 3.U(3.W)
  val WB = 4.U(3.W)

  // Opcodes.
  val LW = 0.U(3.W)
  val SW = 1.U(3.W)
  val BEQZ = 2.U(3.W)
  val ALUop = 3.U(3.W)
  val ADDI = 4.U(3.W)
  val SUBI = 5.U(3.W)

  // ALU function codes.
  val ADD = 0.U(7.W)
  val SUB = 1.U(7.W)
  val AND = 2.U(7.W)
  val OR = 3.U(7.W)
  val XOR = 4.U(7.W)
  val SRL = 5.U(7.W)

  // Decoding of the instruction type.
  val memRef = (opcode === LW) || (opcode === SW)
  val regRegALU = (opcode === ALUop)
  val regImmALU = (opcode === ADDI) || (opcode === SUBI)
  val branch = (opcode === BEQZ)
  
  // Immediate operand with sign extension.
  val Imm = Cat(Fill(8, immFld(8)), immFld(7, 0))

  // Combinational outputs.
  io.dataout := B
  io.wr := (State === MEM) && (opcode === SW)

  // State machine
  switch(State) {
    is(IF) {
      NPC := PC + 2.U
      IR := io.instruction
    }
    is(ID) {
      A := Registers(adFld1)
      B := Registers(adFld2)
    }
    is(EX) {
      when(memRef) {
        ALUOutput := A + Imm
      } .elsewhen(regRegALU) {
        when(funFld === ADD) {
          ALUOutput := A + B
        } .elsewhen(funFld === SUB) {
          ALUOutput := A - B
        } .elsewhen(funFld === AND) {
          ALUOutput := A & B
        } .elsewhen(funFld === OR) {
          ALUOutput := A | B
        } .elsewhen(funFld === XOR) {
          ALUOutput := A ^ B
        } .elsewhen(funFld === SRL) {
          ALUOutput := Cat(0.U(1.W), A(15, 1))
        }
      } .elsewhen(regImmALU) {
        when(opcode === ADDI) {
          ALUOutput := A + Imm
        } .elsewhen(opcode === SUBI) {
          ALUOutput := A - Imm
        }
      } .elsewhen(branch) {
        ALUOutput := Cat(0.U(4.W), NPC) + Cat(Imm, 0.U(1.W))
        Cond := (A === 0.U(16.W))
      }
    }
    is(MEM) {
      when(memRef) {
        when(opcode === LW) {
          LMD := io.datain
        }
      }
      when(branch) {
        when(Cond) {
          PC := ALUOutput(11, 0)
        } .otherwise {
          PC := NPC
        }
      } .otherwise {
        PC := NPC
      }
    }
    is(WB) {
      when(regRegALU) {
        when(adFld3 =/= 0.U) {
          Registers(adFld3) := ALUOutput
        }
      } .elsewhen(regImmALU) {
        when(adFld2 =/= 0.U) {
          Registers(adFld2) := ALUOutput
        }
      } .elsewhen(opcode === LW) {
        when(adFld2 =/= 0.U) {
          Registers(adFld2) := LMD
        }
      }
    }
  }

  // State update.
  when(State === 4.U) {
    State := 0.U
  } .otherwise {
    State := State + 1.U
  }

  // Connect outputs
  io.PC := PC
  io.ALUOutput := ALUOutput
  io.NPC := NPC
  io.IR := IR
  io.A := A
  io.B := B
  io.Cond := Cond
  io.LMD := LMD
  io.State := State
  io.Registers := Registers
}

object VerilogGenerator extends App {
  emitVerilog(new vsa16a(), args)
}