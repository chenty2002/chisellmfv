package llmverify

import chisel3._
import chisel3.util._

class Mem extends Module {
  import ConstConstants._
  import OpcodeConstants._
  
  val io = IO(new Bundle {
    val iOpCode = Input(UInt(6.W))
    val iOp1 = Input(UInt(32.W))
    val iOp2 = Input(UInt(32.W))
    val iConst = Input(UInt(21.W))
    val ResData = Output(UInt(32.W))
    val ResAdr = Output(UInt(32.W))
    
    // Debug outputs to preserve internal signals
    val debug_OpCode = Output(UInt(6.W))
    val debug_Op1 = Output(UInt(32.W))
    val debug_Op2 = Output(UInt(32.W))
    val debug_Const = Output(UInt(21.W))
    val debug_AluIn1 = Output(UInt(32.W))
    val debug_AluIn2 = Output(UInt(32.W))
    val debug_AluOut = Output(UInt(32.W))
  })
  
  // Registers to capture inputs on clock edge
  val OpCode = RegInit(0.U(6.W))
  val Op1 = RegInit(0.U(32.W))
  val Op2 = RegInit(0.U(32.W))
  val Const = RegInit(0.U(21.W))
  
  // Combinational signals
  val AluIn1 = Wire(UInt(32.W))
  val AluIn2 = Wire(UInt(32.W))
  val AluOut = Wire(UInt(32.W))
  
  // Capture inputs on clock edge
  OpCode := io.iOpCode
  Op1 := io.iOp1
  Op2 := io.iOp2
  Const := io.iConst
  
  // ALU input 1: sign-extend constant
  // AluIn1 = {{`Q-`CM+1{Const`CONSTM}}, Const`CONSTN};
  AluIn1 := Cat(Fill(Q - CM + 1, Const(CM)), Const)
  AluIn2 := Op2
  
  // ALU operation based on OpCode
  val aluOutRaw = Wire(UInt(32.W))
  when(OpCode === OP_LDA || OpCode === OP_LDL || OpCode === OP_LDQ ||
       OpCode === OP_LDQ_U || OpCode === OP_STL || OpCode === OP_STQ ||
       OpCode === OP_STQ_U) {
    aluOutRaw := AluIn1 + AluIn2
  }.otherwise {
    aluOutRaw := 0.U
  }
  
  // Force alignment for unaligned load/store
  // case(OpCode)
  //   `OP_LDQ_U,
  //   `OP_STQ_U:    AluOut[2:0] = 3'b000;
  when(OpCode === OP_LDQ_U || OpCode === OP_STQ_U) {
    AluOut := aluOutRaw & ~"b111".U(32.W)
  }.otherwise {
    AluOut := aluOutRaw
  }
  
  // Output mux
  // case(OpCode)
  //   `OP_LDAH,
  //   `OP_LDA:  begin // result is data (stored in RegDest)
  //      ResData = AluOut;	// 
  //      ResAdr  = 0;		// 
  //   end
  //   default:  begin // result is address (used in MAU)
  //      ResData = Op1;
  //      ResAdr  = AluOut;
  //      end
  when(OpCode === OP_LDAH || OpCode === OP_LDA) {
    io.ResData := AluOut
    io.ResAdr := 0.U
  }.otherwise {
    io.ResData := Op1
    io.ResAdr := AluOut
  }
  
  // Debug outputs
  io.debug_OpCode := OpCode
  io.debug_Op1 := Op1
  io.debug_Op2 := Op2
  io.debug_Const := Const
  io.debug_AluIn1 := AluIn1
  io.debug_AluIn2 := AluIn2
  io.debug_AluOut := AluOut
}

object VerilogGenerator extends App {
  emitVerilog(new Mem(), args)
}