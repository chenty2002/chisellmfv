package llmverify

import chisel3._
import chisel3.util._

class BdecSE1 extends Module {
  val io = IO(new Bundle {
    // Clocks & Stalls (Clock is implicit in Chisel)
    val Stall_s1 = Input(Bool())
    
    // Kill instruction
    val Except_s1w = Input(Bool())
    val BKill_s1e = Input(Bool())
    
    // Instruction
    val BInstr_s2r = Input(UInt(40.W))
    
    // Decoded outputs
    val BImmShift16_s2r = Output(Bool())
    val BImm26Bit_s2r = Output(Bool())
    val BImmSigned_s2r = Output(Bool())
    val BDestIsRT_s1e = Output(Bool())
    val BDestIsRD_s1e = Output(Bool())
    val BIsLoad_s1e = Output(Bool())
    val InstrIsStore_s1m = Output(Bool())
    val InstrIsLoad_s1m = Output(Bool())
    val MemOp_s1m = Output(UInt(3.W))
    val BAddOp_s2e = Output(Bool())
    val BSubOp_s2e = Output(Bool())
    val BSltOp_s2e = Output(Bool())
    val BSltUOp_s2e = Output(Bool())
    val BAndOp_s2e = Output(Bool())
    val BOrOp_s2e = Output(Bool())
    val BXorOp_s2e = Output(Bool())
    val BNorOp_s2e = Output(Bool())
    val BUseT_s1e = Output(Bool())
    val BUseImm_s1e = Output(Bool())
    val Syscall_s2m = Output(Bool())
    val Break_s2m = Output(Bool())
    val MvFromCop0_s1m = Output(Bool())
    val MvToCop0_s1e = Output(Bool())
    val MvToCop0_s1m = Output(Bool())
    val TLBRead_s1m = Output(Bool())
    val TLBWriteI_s1m = Output(Bool())
    val TLBWriteR_s1m = Output(Bool())
    val TLBProbe_s1m = Output(Bool())
    val RetFromExcept_s2e = Output(Bool())
    val BALUDrv_s2e = Output(Bool())
    val BoostedInstr_s1m = Output(Bool())
    val BIsBoosted_s2e = Output(Bool())
    val BAUopSigned_s2e = Output(Bool())
    val BWrong_s1e = Output(Bool())
    val CopRegNum_s1m = Output(UInt(4.W))
  })
  
  // Extract instruction fields for easier access
  val instr = io.BInstr_s2r
  val opcode = instr(31, 26)
  val rs = instr(25, 21)
  val rt = instr(20, 16)
  val rd = instr(15, 11)
  val shamt = instr(10, 6)
  val funct = instr(5, 0)
  
  // Generate immediate select signals
  io.BImm26Bit_s2r := (instr(31, 27) === 5.U) || (instr(31, 28) === 4.U) // J, JAL or COPz
  io.BImmSigned_s2r := !(instr(31, 28) === 3.U) // not ANDI ORI XORI LUI
  io.BImmShift16_s2r := (instr(31, 26) === 15.U) // LUI
  
  // Stage s1e registers (combinational decode)
  val BDestIsRT_s1e = Wire(Bool())
  val BDestIsRD_s1e = Wire(Bool())
  val BIsLoad_s1e = Wire(Bool())
  val InstrIsStore_s1e = Wire(Bool())
  val InstrIsLoad_s1e = Wire(Bool())
  val MemOp_s1e = Wire(UInt(3.W))
  val BAddOp_s1e = Wire(Bool())
  val BSubOp_s1e = Wire(Bool())
  val BSltOp_s1e = Wire(Bool())
  val BSltUOp_s1e = Wire(Bool())
  val BAndOp_s1e = Wire(Bool())
  val BOrOp_s1e = Wire(Bool())
  val BXorOp_s1e = Wire(Bool())
  val BNorOp_s1e = Wire(Bool())
  val BUseImm_s1e = Wire(Bool())
  val Syscall_s1e = Wire(Bool())
  val Break_s1e = Wire(Bool())
  val MvFromCop0_s1e = Wire(Bool())
  val MvToCop0_s1e = Wire(Bool())
  val TLBRead_s1e = Wire(Bool())
  val TLBWriteI_s1e = Wire(Bool())
  val TLBWriteR_s1e = Wire(Bool())
  val TLBProbe_s1e = Wire(Bool())
  val RetFromExcept_s1e = Wire(Bool())
  val BALUDrv_s1e = Wire(Bool())
  val BoostedInstr_s1e = Wire(Bool())
  val BIsBoosted_s1e = Wire(Bool())
  val BAUopSigned_s1e = Wire(Bool())
  val BWrong_s1e = Wire(Bool())
  val CopRegNum_s1e = Wire(UInt(4.W))
  
  // Compute the s1e control signals
  // Check if A side instruction - used for BIgnore signal
  BWrong_s1e := ((instr(31, 28) === 5.U) || // Branch
    ((instr(31, 29) === 0.U) && (instr(28, 26) =/= 0.U)) ||
    // Branch & jumps
    ((instr(31, 26) === 0.U) &&
      ((instr(5, 3) === 0.U) || // Shifts
        (instr(5, 4) === 1.U) || // Mult/Div
        (instr(5, 2) === 2.U)))) // Jr/Jalr
  
  // Determine which of the register specifier fields specifies a destination
  val isSpecial = (opcode === 0.U)
  val isJR = isSpecial && (instr(5, 3) === 1.U) && (instr(2, 0) =/= 1.U)
  val isMultDiv = isSpecial && (instr(5, 3) === 3.U)
  val isMTHI_MTLO = isSpecial && (instr(5, 3) === 2.U) && (instr(1) === 1.U)
  
  when (isSpecial && !isJR && !isMultDiv && !isMTHI_MTLO) {
    BDestIsRD_s1e := true.B
    BDestIsRT_s1e := false.B
  }.elsewhen ((instr(31, 29) === 1.U) || (instr(31, 29) === 4.U) ||
    ((instr(31, 28) === 4.U) && (instr(25, 23) === 0.U))) {
    // All loads and immediate ALU ops, MF, CF
    BDestIsRD_s1e := false.B
    BDestIsRT_s1e := true.B
  }.otherwise {
    // No destination
    BDestIsRD_s1e := false.B
    BDestIsRT_s1e := false.B
  }
  
  // Boosted instruction
  BIsBoosted_s1e := (instr(37) | instr(36)) | (instr(35) | instr(34)) | (instr(33) | instr(32))
  
  // Boosted load
  BoostedInstr_s1e := (instr(31) === 1.U) && BIsBoosted_s1e
  
  // Loads, Stores
  InstrIsLoad_s1e := (instr(31) === 1.U) && (instr(29) === 0.U)
  InstrIsStore_s1e := (instr(31) === 1.U) && (instr(29) === 1.U)
  
  // For both loads and stores - 3 bit opcode specifier
  // Load word coprocessor not supported, set to 7 if not L/S
  MemOp_s1e := Mux(instr(31, 30) === 2.U, instr(28, 26), 7.U)
  
  // Signed ADD/SUB i.e. ADDI, ADD, SUB
  BAUopSigned_s1e := ((opcode === 8.U) || // ADDI
    (isSpecial && (instr(5, 3) === 4.U) && 
      ((funct === 0.U) || (funct === 2.U)))) // ADD or SUB
  
  // ADD, ADDU, ADDI, ADDIU, Loads and Stores
  BAddOp_s1e := ((isSpecial && (instr(5, 1) === 16.U)) ||
    (instr(31, 27) === 4.U) || (instr(31) === 1.U))
  
  // SUB, SUBU
  BSubOp_s1e := (isSpecial && (instr(5, 1) === 17.U))
  
  // SLT, SLTI
  BSltOp_s1e := ((isSpecial && (funct === 42.U)) || (opcode === 10.U))
  
  // SLTU, SLTIU
  BSltUOp_s1e := ((isSpecial && (funct === 43.U)) || (opcode === 11.U))
  
  // AND, ANDI
  BAndOp_s1e := ((isSpecial && (funct === 36.U)) || (opcode === 12.U))
  
  // OR, ORI, LUI
  BOrOp_s1e := ((isSpecial && (funct === 37.U)) || 
    (opcode === 13.U) || (opcode === 15.U))
  
  // XOR, XORI
  BXorOp_s1e := ((isSpecial && (funct === 38.U)) || (opcode === 14.U))
  
  // NOR
  BNorOp_s1e := (isSpecial && (funct === 39.U))
  
  // ALU Immediates, Loads, Stores, Branches
  BUseImm_s1e := ((instr(31, 29) === 1.U) || (instr(31) === 1.U)) ||
    ((instr(31, 29) === 0.U) && (instr(28, 26) =/= 0.U)) ||
    (opcode === 15.U)
  
  // SYSCALL
  Syscall_s1e := (isSpecial && (funct === 12.U))
  
  // BREAK
  Break_s1e := (isSpecial && (funct === 13.U))
  
  // MFCz, CFCz
  MvFromCop0_s1e := (opcode === 16.U) && (instr(25, 23) === 0.U)
  
  // Regfile reads from MemBus
  BIsLoad_s1e := ((instr(31) === 1.U) && (instr(29) === 0.U)) || 
    ((opcode === 16.U) && (instr(25, 23) === 0.U))
  
  // MTCz, CTCz
  MvToCop0_s1e := (opcode === 16.U) && (instr(25, 23) === 1.U)
  
  // TLBR
  TLBRead_s1e := (opcode === 16.U) && (funct === 1.U)
  
  // TLBWI
  TLBWriteI_s1e := (opcode === 16.U) && (funct === 2.U)
  
  // TLBWR
  TLBWriteR_s1e := (opcode === 16.U) && (funct === 6.U)
  
  // TLBP
  TLBProbe_s1e := (opcode === 16.U) && (funct === 8.U)
  
  // RFE
  RetFromExcept_s1e := (opcode === 16.U) && (funct === 16.U)
  
  // ALU Immediates, SPECIALs excluding Shifts, Loads and Stores
  BALUDrv_s1e := ((instr(31, 29) === 1.U) ||
    ((opcode === 0.U) && (instr(5, 3) =/= 0.U)) ||
    (instr(31, 30) === 2.U))
  
  // Coprocessor 0 Register number
  CopRegNum_s1e := instr(14, 11)
  
  // Derive some control signals from decoded signals
  io.BUseT_s1e := BAddOp_s1e | BAndOp_s1e | BOrOp_s1e | BXorOp_s1e | BNorOp_s1e
  
  // Stage s2e registers (delayed by one cycle)
  val BAddOp_s2e = RegNext(BAddOp_s1e, 0.U)
  val BSubOp_s2e = RegNext(BSubOp_s1e, 0.U)
  val BSltOp_s2e = RegNext(BSltOp_s1e, 0.U)
  val BSltUOp_s2e = RegNext(BSltUOp_s1e, 0.U)
  val BAndOp_s2e = RegNext(BAndOp_s1e, 0.U)
  val BOrOp_s2e = RegNext(BOrOp_s1e, 0.U)
  val BXorOp_s2e = RegNext(BXorOp_s1e, 0.U)
  val BNorOp_s2e = RegNext(BNorOp_s1e, 0.U)
  val BAUopSigned_s2e = RegNext(BAUopSigned_s1e, 0.U)
  
  val BCancel_s1e = !(io.BKill_s1e | io.Except_s1w)
  
  val BALUDrv_s2e = RegEnable(BALUDrv_s1e & BCancel_s1e, !io.Stall_s1)
  val Syscall_s2e = RegEnable(Syscall_s1e & BCancel_s1e, !io.Stall_s1)
  val Break_s2e = RegEnable(Break_s1e & BCancel_s1e, !io.Stall_s1)
  val BIsBoosted_s2e = RegEnable(BIsBoosted_s1e & BCancel_s1e, !io.Stall_s1)
  val BoostedInstr_s2e = RegEnable(BoostedInstr_s1e & BCancel_s1e, !io.Stall_s1)
  val TLBRead_s2e = RegEnable(TLBRead_s1e & BCancel_s1e, !io.Stall_s1)
  val TLBWriteI_s2e = RegEnable(TLBWriteI_s1e & BCancel_s1e, !io.Stall_s1)
  val TLBWriteR_s2e = RegEnable(TLBWriteR_s1e & BCancel_s1e, !io.Stall_s1)
  val TLBProbe_s2e = RegEnable(TLBProbe_s1e & BCancel_s1e, !io.Stall_s1)
  val RetFromExcept_s2e = RegEnable(RetFromExcept_s1e & BCancel_s1e, !io.Stall_s1)
  val MemOp_s2e = RegNext(MemOp_s1e, 0.U)
  val MvToCop0_s2e = RegEnable(MvToCop0_s1e & BCancel_s1e, !io.Stall_s1)
  val MvFromCop0_s2e = RegEnable(MvFromCop0_s1e & BCancel_s1e, !io.Stall_s1)
  val CopRegNum_s2e = RegNext(CopRegNum_s1e, 0.U)
  val InstrIsLoad_s2e = RegEnable(InstrIsLoad_s1e & BCancel_s1e, !io.Stall_s1)
  val InstrIsStore_s2e = RegEnable(InstrIsStore_s1e & BCancel_s1e, !io.Stall_s1)
  
  // Stage s1m registers (delayed by another cycle)
  val Syscall_s1m = RegNext(Syscall_s2e, 0.U)
  val Break_s1m = RegNext(Break_s2e, 0.U)
  val BoostedInstr_s1m = RegNext(BoostedInstr_s2e, 0.U)
  val TLBRead_s1m = RegNext(TLBRead_s2e, 0.U)
  val TLBWriteI_s1m = RegNext(TLBWriteI_s2e, 0.U)
  val TLBWriteR_s1m = RegNext(TLBWriteR_s2e, 0.U)
  val TLBProbe_s1m = RegNext(TLBProbe_s2e, 0.U)
  val MemOp_s1m_reg = RegNext(MemOp_s2e, 0.U)
  val MvToCop0_s1m_reg = RegNext(MvToCop0_s2e, 0.U)
  val MvFromCop0_s1m_reg = RegNext(MvFromCop0_s2e, 0.U)
  val CopRegNum_s1m_reg = RegNext(CopRegNum_s2e, 0.U)
  val InstrIsLoad_s1m_reg = RegNext(InstrIsLoad_s2e, 0.U)
  val InstrIsStore_s1m_reg = RegNext(InstrIsStore_s2e, 0.U)
  
  // Stage s2m registers (delayed by another cycle)
  val Syscall_s2m_reg = RegNext(Syscall_s1m, 0.U)
  val Break_s2m_reg = RegNext(Break_s1m, 0.U)
  
  // Connect outputs
  io.BDestIsRT_s1e := BDestIsRT_s1e
  io.BDestIsRD_s1e := BDestIsRD_s1e
  io.BIsLoad_s1e := BIsLoad_s1e
  io.BWrong_s1e := BWrong_s1e
  io.BUseImm_s1e := BUseImm_s1e
  io.MvToCop0_s1e := MvToCop0_s1e
  
  io.BAddOp_s2e := BAddOp_s2e
  io.BSubOp_s2e := BSubOp_s2e
  io.BSltOp_s2e := BSltOp_s2e
  io.BSltUOp_s2e := BSltUOp_s2e
  io.BAndOp_s2e := BAndOp_s2e
  io.BOrOp_s2e := BOrOp_s2e
  io.BXorOp_s2e := BXorOp_s2e
  io.BNorOp_s2e := BNorOp_s2e
  io.BALUDrv_s2e := BALUDrv_s2e
  io.BAUopSigned_s2e := BAUopSigned_s2e
  io.BIsBoosted_s2e := BIsBoosted_s2e
  io.RetFromExcept_s2e := RetFromExcept_s2e
  
  io.InstrIsStore_s1m := InstrIsStore_s1m_reg
  io.InstrIsLoad_s1m := InstrIsLoad_s1m_reg
  io.MemOp_s1m := MemOp_s1m_reg
  io.MvFromCop0_s1m := MvFromCop0_s1m_reg
  io.MvToCop0_s1m := MvToCop0_s1m_reg
  io.TLBRead_s1m := TLBRead_s1m
  io.TLBWriteI_s1m := TLBWriteI_s1m
  io.TLBWriteR_s1m := TLBWriteR_s1m
  io.TLBProbe_s1m := TLBProbe_s1m
  io.BoostedInstr_s1m := BoostedInstr_s1m
  io.CopRegNum_s1m := CopRegNum_s1m_reg
  
  io.Syscall_s2m := Syscall_s2m_reg
  io.Break_s2m := Break_s2m_reg
}

object VerilogGenerator extends App {
  emitVerilog(new BdecSE1(), args)
}