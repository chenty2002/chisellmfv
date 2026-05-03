package llmverify

import chisel3._
import chisel3.util._

/**
 * Elementary pipeline.
 * 
 * This pipeline consists of an ALU and a register file.
 * At each clock cycle the pipeline starts the execution of a instruction,
 * which completes in three cycles unless stalled:
 *  1. Read the operands from the register file.
 *  2. Perform the ALU operation.
 *  3. Write result back to the register file.
 * 
 * The pipeline supports bypass of the write-back stage. Therefore, if an
 * instruction depends on the result of the one immediately preceeding it,
 * the pipeline needs to stall for just one cycle.
 */
class palu extends Module {
  val io = IO(new Bundle {
    val stall = Input(Bool())
    val opcode = Input(UInt(3.W))
    val src1 = Input(UInt(2.W))
    val src2 = Input(UInt(2.W))
    val dest = Input(UInt(2.W))
    val aluOut = Output(UInt(4.W))
    
    // Additional outputs to preserve internal state for verification
    val regFile0 = Output(UInt(4.W))
    val regFile1 = Output(UInt(4.W))
    val regFile2 = Output(UInt(4.W))
    val regFile3 = Output(UInt(4.W))
    val bubbleEx = Output(Bool())
    val bubbleWb = Output(Bool())
    val destEx = Output(UInt(2.W))
    val destWb = Output(UInt(2.W))
    val opcodeEx = Output(UInt(3.W))
    val op1 = Output(UInt(4.W))
    val op2 = Output(UInt(4.W))
  })
  
  // Constants
  val ADD = 0.U(3.W)
  val SUB = 1.U(3.W)
  val ONE = 2.U(3.W)
  val AND = 3.U(3.W)
  val NAND = 4.U(3.W)
  val SRL = 5.U(3.W)
  val SRA = 6.U(3.W)
  val CPA = 7.U(3.W)
  
  // Register file - 4 registers, each 4 bits wide
  val regFile = RegInit(VecInit(Seq.fill(4)(0.U(4.W))))
  
  // Pipeline registers
  val bubbleEx = RegInit(false.B)
  val bubbleWb = RegInit(false.B)
  val destEx = RegInit(0.U(2.W))
  val destWb = RegInit(0.U(2.W))
  val opcodeEx = RegInit(0.U(3.W))
  
  // Operand registers - these hold values between pipeline stages
  val op1 = RegInit(0.U(4.W))
  val op2 = RegInit(0.U(4.W))
  val aluOut = RegInit(0.U(4.W))
  
  // ALU function implementation - fixed MuxLookup syntax
  def aluFunction(opc: UInt, o1: UInt, o2: UInt): UInt = {
    MuxLookup(opc, 0.U(4.W))(
      Seq(
        ADD  -> (o1 + o2),
        SUB  -> (o1 - o2),
        ONE  -> 1.U(4.W),
        AND  -> (o1 & o2),
        NAND -> ~(o1 & o2),
        SRL  -> Cat(0.U(1.W), o1(3, 1)),
        SRA  -> Cat(o1(3), o1(3, 1)),
        CPA  -> o1
      )
    )
  }
  
  // Write-back stage: write result to register file
  when(!bubbleWb) {
    regFile(destWb) := aluOut
  }
  
  // Execute stage: perform ALU operation
  when(!bubbleEx) {
    aluOut := aluFunction(opcodeEx, op1, op2)
    destWb := destEx
  }
  
  // Fetch stage: read operands and setup next instruction
  when(!io.stall) {
    // Bypass logic - read operands with possible bypass from write-back stage
    val op1Next = Mux(io.src1 === destWb, aluOut, regFile(io.src1))
    val op2Next = Mux(io.src2 === destWb, aluOut, regFile(io.src2))
    
    op1 := op1Next
    op2 := op2Next
    opcodeEx := io.opcode
    destEx := io.dest
  }
  
  // Update pipe stall registers
  bubbleWb := bubbleEx
  bubbleEx := io.stall
  
  // Connect outputs
  io.aluOut := aluOut
  io.regFile0 := regFile(0)
  io.regFile1 := regFile(1)
  io.regFile2 := regFile(2)
  io.regFile3 := regFile(3)
  io.bubbleEx := bubbleEx
  io.bubbleWb := bubbleWb
  io.destEx := destEx
  io.destWb := destWb
  io.opcodeEx := opcodeEx
  io.op1 := op1
  io.op2 := op2
}

object VerilogGenerator extends App {
  emitVerilog(new palu(), args)
}