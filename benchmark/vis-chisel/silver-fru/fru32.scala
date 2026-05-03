package llmverify

import chisel3._
import chisel3.util._

/**
 * Forwarding Register Unit (FRU)
 * Retrieves register values as required for IDU, keeps track of data 
 * values and there associated register addresses in flight to REG_file 
 * and forwards to EXU A.R. to avoid idle pipe.
 */
class fru extends Module {
  val io = IO(new Bundle {
    // Inputs
    val iStep_EXU = Input(Bool())
    val iStep_MAU = Input(Bool())
    val iStep_WB = Input(Bool())
    val iWork_EXU = Input(Bool())
    val iWork_MAU = Input(Bool())
    val iWork_WB = Input(Bool())
    val iEXU_Cond = Input(Bool())
    val iMAU_Cond = Input(Bool())
    val iResetn = Input(Bool())
    
    val iRegA = Input(UInt(6.W))
    val iRegB = Input(UInt(6.W))
    val iEXU_Dest = Input(UInt(6.W))
    val iMAU_Dest = Input(UInt(6.W))
    
    val iEXU_ResData = Input(UInt(32.W))
    val iMAU_Data = Input(UInt(32.W))
    
    val iDecode = Input(UInt(decode.DEC_WIDTH.W))
    
    // Outputs
    val Opd1 = Output(UInt(32.W))
    val Opd2 = Output(UInt(32.W))
    val Wait_for_data = Output(Bool())
    
    // Debug outputs to preserve signals
    val debug_EXU_Cond = Output(Bool())
    val debug_MAU_Cond = Output(Bool())
    val debug_RegA = Output(UInt(6.W))
    val debug_RegB = Output(UInt(6.W))
    val debug_EXU_Dest = Output(UInt(6.W))
    val debug_MAU_Dest = Output(UInt(6.W))
    val debug_EXU_ResData = Output(UInt(32.W))
    val debug_MAU_Data = Output(UInt(32.W))
    val debug_Decode = Output(UInt(decode.DEC_WIDTH.W))
    val debug_SEL_A = Output(UInt(2.W))
    val debug_SEL_B = Output(UInt(2.W))
    val debug_WBp_add = Output(UInt(6.W))
    val debug_WBp_Data = Output(UInt(32.W))
    val debug_WBp_Cond = Output(Bool())
    val debug_EXUp_data_source = Output(Bool())
  })
  
  // Constants
  val asserted = true.B
  val R31 = "b11111".U
  val R31_contents = 0.U(32.W)
  
  // MUX selection constants
  val TAKE_EXUp_d = 2.U(2.W)
  val TAKE_MAUp_d = 1.U(2.W)
  val TAKE_WBp_d = 0.U(2.W)
  val TAKE_REG_d = 3.U(2.W)
  
  // Latched inputs
  val LStep_EXU = RegNext(io.iStep_EXU, false.B)
  val LStep_MAU = RegNext(io.iStep_MAU, false.B)
  val LStep_WB = RegNext(io.iStep_WB, false.B)
  val LWork_EXU = RegNext(io.iWork_EXU, false.B)
  val LWork_MAU = RegNext(io.iWork_MAU, false.B)
  val LWork_WB = RegNext(io.iWork_WB, false.B)
  
  val EXU_Cond = RegNext(io.iEXU_Cond, false.B)
  val MAU_Cond = RegNext(io.iMAU_Cond, false.B)
  
  val RegA = RegNext(io.iRegA, 0.U)
  val RegB = RegNext(io.iRegB, 0.U)
  val EXU_Dest = RegNext(io.iEXU_Dest, 0.U)
  val MAU_Dest = RegNext(io.iMAU_Dest, 0.U)
  
  val EXU_ResData = RegNext(io.iEXU_ResData, 0.U)
  val MAU_Data = RegNext(io.iMAU_Data, 0.U)
  val Decode = RegNext(io.iDecode, 0.U)
  
  // Register file (8 registers for simplicity, can be extended to 32)
  val REG_file = RegInit(VecInit(Seq.fill(8)(0.U(32.W))))
  
  // Pipeline registers
  val EXUp_data_source = RegInit(false.B)
  val MAUp_RPCC = RegInit(false.B)
  val WBp_RPCC = RegInit(false.B)
  val WBp_Cond = RegInit(false.B)
  val WBp_add = RegInit(0.U(6.W))
  val WBp_Data = RegInit(0.U(32.W))
  
  // Read register values
  val Read_RegA = Wire(UInt(32.W))
  val Read_RegB = Wire(UInt(32.W))
  
  // MUX selection signals
  val SEL_A = Wire(UInt(2.W))
  val SEL_B = Wire(UInt(2.W))
  
  // MUX outputs with default initialization
  val dOpd1 = WireInit(0.U(32.W))
  val dOpd2 = WireInit(0.U(32.W))
  
  // Wait for data signal
  val dWait_for_data = Wire(Bool())
  
  // Register file reads
  when(RegA(5) && (RegA(4,0) < 8.U)) {
    Read_RegA := REG_file(RegA(4,0))
  }.otherwise {
    Read_RegA := 0.U
  }
  
  when(RegB(5) && (RegB(4,0) < 8.U)) {
    Read_RegB := REG_file(RegB(4,0))
  }.otherwise {
    Read_RegB := 0.U
  }
  
  // Pipeline stage updates
  when(LStep_EXU) {
    EXUp_data_source := Decode(decode.DEC_MEM) && Decode(decode.DEC_MEM_ACC) && !Decode(decode.DEC_MEM_ST)
  }
  
  when(LStep_MAU) {
    MAUp_RPCC := false.B // EXUp_RPCC (not implemented)
  }
  
  when(LStep_WB) {
    WBp_Cond := MAU_Cond
    WBp_add := MAU_Dest
    WBp_Data := MAU_Data
    WBp_RPCC := MAUp_RPCC
  }
  
  // Register file writes
  when(WBp_add(5) && WBp_Cond && LWork_WB && (WBp_add(4,0) < 8.U)) {
    REG_file(WBp_add(4,0)) := WBp_Data
  }
  
  // MUX control logic for operand A
  when(RegA(5) && (RegA === EXU_Dest) && !EXUp_data_source && EXU_Cond && LWork_EXU && (RegA(4,0) =/= R31)) {
    SEL_A := TAKE_EXUp_d
  }.elsewhen(RegA(5) && (RegA === MAU_Dest) && MAU_Cond && LWork_MAU && (RegA(4,0) =/= R31)) {
    SEL_A := TAKE_MAUp_d
  }.elsewhen(RegA(5) && (RegA === WBp_add) && LWork_WB && WBp_Cond && (RegA(4,0) =/= R31)) {
    SEL_A := TAKE_WBp_d
  }.otherwise {
    SEL_A := TAKE_REG_d
  }
  
  // MUX control logic for operand B
  when(RegB(5) && (RegB === EXU_Dest) && !EXUp_data_source && EXU_Cond && LWork_EXU && (RegB(4,0) =/= R31)) {
    SEL_B := TAKE_EXUp_d
  }.elsewhen(RegB(5) && (RegB === MAU_Dest) && MAU_Cond && LWork_MAU && (RegB(4,0) =/= R31)) {
    SEL_B := TAKE_MAUp_d
  }.elsewhen(RegB(5) && (RegB === WBp_add) && LWork_WB && WBp_Cond && (RegB(4,0) =/= R31)) {
    SEL_B := TAKE_WBp_d
  }.otherwise {
    SEL_B := TAKE_REG_d
  }
  
  // Wait for data logic
  when(((RegA(5) && (RegA === EXU_Dest) && EXUp_data_source && LWork_EXU && (RegA(4,0) =/= R31)) ||
         (RegB(5) && (RegB === EXU_Dest) && EXUp_data_source && LWork_EXU && (RegB(4,0) =/= R31)))) {
    dWait_for_data := asserted
  }.otherwise {
    dWait_for_data := !asserted
  }
  
  // MUX for operand A
  when(RegA(4,0) === R31) {
    dOpd1 := R31_contents
  }.otherwise {
    switch(SEL_A) {
      is(TAKE_EXUp_d) { dOpd1 := EXU_ResData }
      is(TAKE_MAUp_d) { dOpd1 := MAU_Data }
      is(TAKE_WBp_d)  { dOpd1 := WBp_Data }
      is(TAKE_REG_d)  { dOpd1 := Read_RegA }
    }
  }
  
  // MUX for operand B
  when(RegB(4,0) === R31) {
    dOpd2 := R31_contents
  }.otherwise {
    switch(SEL_B) {
      is(TAKE_EXUp_d) { dOpd2 := EXU_ResData }
      is(TAKE_MAUp_d) { dOpd2 := MAU_Data }
      is(TAKE_WBp_d)  { dOpd2 := WBp_Data }
      is(TAKE_REG_d)  { dOpd2 := Read_RegB }
    }
  }
  
  // Assign outputs
  io.Opd1 := dOpd1
  io.Opd2 := dOpd2
  io.Wait_for_data := dWait_for_data
  
  // Debug outputs to preserve signals
  io.debug_EXU_Cond := EXU_Cond
  io.debug_MAU_Cond := MAU_Cond
  io.debug_RegA := RegA
  io.debug_RegB := RegB
  io.debug_EXU_Dest := EXU_Dest
  io.debug_MAU_Dest := MAU_Dest
  io.debug_EXU_ResData := EXU_ResData
  io.debug_MAU_Data := MAU_Data
  io.debug_Decode := Decode
  io.debug_SEL_A := SEL_A
  io.debug_SEL_B := SEL_B
  io.debug_WBp_add := WBp_add
  io.debug_WBp_Data := WBp_Data
  io.debug_WBp_Cond := WBp_Cond
  io.debug_EXUp_data_source := EXUp_data_source
}

object VerilogGenerator extends App {
  emitVerilog(new fru(), args)
}