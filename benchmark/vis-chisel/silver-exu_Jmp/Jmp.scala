package llmverify

import chisel3._
import chisel3.util._

class Jmp extends Module {
  val io = IO(new Bundle {
    val iFctCode = Input(UInt(7.W))
    val iOp1 = Input(UInt(32.W))
    val iOp2 = Input(UInt(32.W))
    val iConst = Input(UInt(21.W))
    val ResData = Output(UInt(32.W))
    val ResAdr = Output(UInt(32.W))
  })

  // Internal registers
  val FctCode = RegInit(0.U(7.W))
  val Op1 = RegInit(0.U(32.W))
  val Op2 = RegInit(0.U(32.W))
  val Const = RegInit(0.U(21.W))
  
  // ALU inputs and outputs
  val AluIn1 = Wire(UInt(32.W))
  val AluIn2 = Wire(UInt(32.W))
  val AluOut = Wire(UInt(32.W))
  
  // Clock edge logic
  when(true.B) { // Always on positive edge
    FctCode := io.iFctCode
    Op1 := io.iOp1
    Op2 := io.iOp2
    Const := io.iConst
  }
  
  // Input mux
  AluIn1 := Op1
  AluIn2 := Op2
  
  // Combinational logic based on function code
  // Extract the lower 2 bits for jump operation comparison
  val jumpOp = FctCode(1, 0)
  
  AluOut := MuxCase(0.U(32.W), Seq(
    (jumpOp === OpcodeConstants.JMP_JMP) -> Cat(AluIn2(31,2), 0.U(2.W)),
    (jumpOp === OpcodeConstants.JMP_JSR) -> Cat(AluIn2(31,2), 0.U(2.W)),
    (jumpOp === OpcodeConstants.JMP_JSR_COROUTINE) -> Cat(AluIn2(31,2), 0.U(2.W)),
    (jumpOp === OpcodeConstants.JMP_RET) -> Cat(AluIn2(31,2), 0.U(2.W))
  ))
  
  // Output mux
  io.ResData := AluOut
  io.ResAdr := 0.U
}

object VerilogGenerator extends App {
  emitVerilog(new Jmp(), args)
}