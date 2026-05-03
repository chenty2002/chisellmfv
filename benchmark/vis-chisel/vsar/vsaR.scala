package llmverify

import chisel3._
import chisel3.util._

// Very Simple Architecture 12-bit. Revision A.
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
//     NOT
//     SRL
//     SRA
//
// All instructions are 12 bits and they have one of two formats (width of
// the fields in parentheses):
//
// R-format: opcode (3) source1 (2) source2 (2) destination (2) function (3)
// I-format: opcode (3) source1 (2) destination (2) immediate (5)
//
// The R-format instructions are: ADD, SUB, AND, OR, XOR, NOT, SRL, and SRA.
// The other instructions are I-format.
//
// There are 3 general-purpose 5-bit registers (R1, R2, R3) that can act as
// source or destinations for the various instructions. R0 is always 0.
//
// All instructions execute in exactly 5 clock cycles.
// The program counter has only 5 bits to reduce the sequential depth
// of the FSM.

class vsaR extends Module {
  val io = IO(new Bundle {
    val PC = Output(UInt(5.W))
    val instruction = Input(UInt(12.W))
    val ALUOutput = Output(UInt(5.W))
    val datain = Input(UInt(5.W))
    val dataout = Output(UInt(5.W))
    val wr = Output(Bool())
    // Additional outputs to preserve internal signals
    val IR = Output(UInt(12.W))
    val State = Output(UInt(3.W))
    val A = Output(UInt(5.W))
    val B = Output(UInt(5.W))
    val LMD = Output(UInt(5.W))
    val Cond = Output(Bool())
    val NPC = Output(UInt(5.W))
  })

  // Register file - 4 registers of 5 bits each
  val Registers = RegInit(VecInit(Seq.fill(4)(0.U(5.W))))
  
  // Program counter and next program counter
  val PC = RegInit(0.U(5.W))
  val NPC = RegInit(0.U(5.W))
  
  // Instruction register
  val IR = RegInit(0.U(12.W))
  
  // ALU operands and output
  val A = RegInit(0.U(5.W))
  val B = RegInit(0.U(5.W))
  val ALUOutput = RegInit(0.U(5.W))
  
  // Comparison result
  val Cond = RegInit(false.B)
  
  // Load memory data register
  val LMD = RegInit(0.U(5.W))
  
  // State machine
  val State = RegInit(0.U(3.W))

  // Instruction fields
  val opcode = IR(11, 9)
  val adFld1 = IR(8, 7)
  val adFld2 = IR(6, 5)
  val adFld3 = IR(4, 3)
  val funFld = IR(2, 0)
  val immFld = IR(4, 0)

  // Control states
  val IF = 0.U(3.W)
  val ID = 1.U(3.W)
  val EX = 2.U(3.W)
  val MEM = 3.U(3.W)
  val WB = 4.U(3.W)

  // Opcodes
  val LW = 0.U(3.W)
  val SW = 1.U(3.W)
  val BEQZ = 2.U(3.W)
  val ALUop = 3.U(3.W)
  val ADDI = 4.U(3.W)
  val SUBI = 5.U(3.W)

  // ALU function codes
  val ADD = 0.U(3.W)
  val SUB = 1.U(3.W)
  val AND = 2.U(3.W)
  val OR = 3.U(3.W)
  val XOR = 4.U(3.W)
  val NOT = 5.U(3.W)
  val SRL = 6.U(3.W)
  val SRA = 7.U(3.W)

  // Decoding of the instruction type
  val memRef = (opcode === LW) || (opcode === SW)
  val regRegALU = (opcode === ALUop)
  val regImmALU = (opcode === ADDI) || (opcode === SUBI)
  val branch = (opcode === BEQZ)
  
  // Immediate operand
  val Imm = immFld

  // Combinational outputs
  io.dataout := B
  io.wr := (State === MEM) && (opcode === SW)

  // State machine and register updates
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
      }.elsewhen(regRegALU) {
        switch(funFld) {
          is(ADD) { ALUOutput := A + B }
          is(SUB) { ALUOutput := A - B }
          is(AND) { ALUOutput := A & B }
          is(OR)  { ALUOutput := A | B }
          is(XOR) { ALUOutput := A ^ B }
          is(NOT) { ALUOutput := ~A }
          is(SRL) { ALUOutput := Cat(0.U(1.W), A(4, 1)) }
          is(SRA) { ALUOutput := Cat(A(4), A(4, 1)) }
        }
      }.elsewhen(regImmALU) {
        when(opcode === ADDI) {
          ALUOutput := A + Imm
        }.elsewhen(opcode === SUBI) {
          ALUOutput := A - Imm
        }
      }.elsewhen(branch) {
        ALUOutput := NPC + Cat(immFld(3, 0), 0.U(1.W))
        Cond := (A === 0.U)
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
          PC := ALUOutput
        }.otherwise {
          PC := NPC
        }
      }.otherwise {
        PC := NPC
      }
    }
    is(WB) {
      when(regRegALU) {
        when(adFld3 =/= 0.U) {
          Registers(adFld3) := ALUOutput
        }
      }.elsewhen(regImmALU) {
        when(adFld2 =/= 0.U) {
          Registers(adFld2) := ALUOutput
        }
      }.elsewhen(opcode === LW) {
        when(adFld2 =/= 0.U) {
          Registers(adFld2) := LMD
        }
      }
    }
  }

  // State update
  when(State === 4.U) {
    State := 0.U
  }.otherwise {
    State := State + 1.U
  }

  // Output assignments
  io.PC := PC
  io.ALUOutput := ALUOutput
  io.IR := IR
  io.State := State
  io.A := A
  io.B := B
  io.LMD := LMD
  io.Cond := Cond
  io.NPC := NPC
}

object VerilogGenerator extends App {
  emitVerilog(new vsaR(), args)
}