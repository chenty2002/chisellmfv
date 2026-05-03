package llmverify

import chisel3._
import chisel3.util._

// Constants from torch.h
object AdecSE1Constants {
  val OPCODE = 31
  val SPECIAL = 0.U
  val BCOND = 1.U
  val FALSE = false.B
}

class AdecSE1IO extends Bundle {
  // Clocks & Stalls
  val Stall_s1 = Input(Bool())
  val Except_s1w = Input(Bool())
  
  // Inputs
  val AInstr_s2r = Input(UInt(40.W))
  val AKill_s1e = Input(Bool())
  val TakenBranch_s2e = Input(Bool())
  val RetFromExcept_s2e = Input(Bool())
  val SquashBit_s1 = Input(Bool())
  
  // Outputs
  val AImmShift16_s2r = Output(Bool())
  val AImm26Bit_s2r = Output(Bool())
  val AImmSigned_s2r = Output(Bool())
  val ADestIsRT_s1e = Output(Bool())
  val ADestIsRD_s1e = Output(Bool())
  val ADestIs31_s1e = Output(Bool())
  val AAddOp_s2e = Output(Bool())
  val ASubOp_s2e = Output(Bool())
  val ASltOp_s2e = Output(Bool())
  val ASltUOp_s2e = Output(Bool())
  val AAndOp_s2e = Output(Bool())
  val AOrOp_s2e = Output(Bool())
  val AXorOp_s2e = Output(Bool())
  val ANorOp_s2e = Output(Bool())
  val MultOp_s2e = Output(Bool())
  val DivOp_s2e = Output(Bool())
  val SignedMDOp_s2e = Output(Bool())
  val AUseT_s1e = Output(Bool())
  val AUseImm_s1e = Output(Bool())
  val LoadHiLo_s2e = Output(Bool())
  val StoreHiLo_s2e = Output(Bool())
  val HiLo_s2e = Output(Bool())
  val AALUDrv_s2e = Output(Bool())
  val ShiftLeft_s2e = Output(Bool())
  val ShiftArithmetic_s2e = Output(Bool())
  val ShifterDrv_s2e = Output(Bool())
  val PCDrvResult_s2e = Output(Bool())
  val BEQnext_s1e = Output(Bool())
  val BNEnext_s1e = Output(Bool())
  val BLEZnext_s1e = Output(Bool())
  val BGTZnext_s1e = Output(Bool())
  val BLTZnext_s1e = Output(Bool())
  val BGEZnext_s1e = Output(Bool())
  val ImmPC_s1e = Output(Bool())
  val RegPC_s1e = Output(Bool())
  val Commit_s1e = Output(Bool())
  val Squash_s1e = Output(Bool())
  val AIsBoosted_s2e = Output(Bool())
  val AWrong_s1e = Output(Bool())
  val AAUopSigned_s2e = Output(Bool())
}

class AdecSE1 extends Module {
  val io = IO(new AdecSE1IO)
  
  // Import constants
  import AdecSE1Constants._
  
  // Register file signals
  val ADestIsRT_s1e = RegInit(false.B)
  val ADestIsRD_s1e = RegInit(false.B)
  val ADestIs31_s1e = RegInit(false.B)
  
  // AALU signals (s1e stage)
  val AAddOp_s1e = RegInit(false.B)
  val ASubOp_s1e = RegInit(false.B)
  val ASltOp_s1e = RegInit(false.B)
  val ASltUOp_s1e = RegInit(false.B)
  val AAndOp_s1e = RegInit(false.B)
  val AOrOp_s1e = RegInit(false.B)
  val AXorOp_s1e = RegInit(false.B)
  val ANorOp_s1e = RegInit(false.B)
  val MultOp_s1e = RegInit(false.B)
  val DivOp_s1e = RegInit(false.B)
  val SignedMDOp_s1e = RegInit(false.B)
  val AUseImm_s1e = RegInit(false.B)
  val LoadHiLo_s1e = RegInit(false.B)
  val StoreHiLo_s1e = RegInit(false.B)
  val HiLo_s1e = RegInit(false.B)
  val AALUDrv_s1e = RegInit(false.B)
  val AAUopSigned_s1e = RegInit(false.B)
  
  // Shifter signals
  val ShiftLeft_s1e = RegInit(false.B)
  val ShiftArithmetic_s1e = RegInit(false.B)
  val ShifterDrv_s1e = RegInit(false.B)
  
  // Instruction fetch signals
  val PCDrvResult_s1e = RegInit(false.B)
  val PCDrvResult_s2e = RegInit(false.B)
  val immPC_s1e = RegInit(false.B)
  val regPC_s1e = RegInit(false.B)
  val AIsBoosted_s1e = RegInit(false.B)
  val AIsBoosted_s2e = RegInit(false.B)
  val AWrong_s1e = RegInit(false.B)
  val commit_s1e = RegInit(false.B)
  val squash_s1e = RegInit(false.B)
  val predictTaken_b_s1e = RegInit(false.B)
  val predictTaken_b_s2e = RegInit(false.B)
  
  // Delayed signals (s2e stage)
  val AAddOp_s2e = RegInit(false.B)
  val ASubOp_s2e = RegInit(false.B)
  val ASltOp_s2e = RegInit(false.B)
  val ASltUOp_s2e = RegInit(false.B)
  val AAndOp_s2e = RegInit(false.B)
  val AOrOp_s2e = RegInit(false.B)
  val AXorOp_s2e = RegInit(false.B)
  val ANorOp_s2e = RegInit(false.B)
  val MultOp_s2e = RegInit(false.B)
  val DivOp_s2e = RegInit(false.B)
  val SignedMDOp_s2e = RegInit(false.B)
  val LoadHiLo_s2e = RegInit(false.B)
  val StoreHiLo_s2e = RegInit(false.B)
  val HiLo_s2e = RegInit(false.B)
  val AALUDrv_s2e = RegInit(false.B)
  val AAUopSigned_s2e = RegInit(false.B)
  val ShiftLeft_s2e = RegInit(false.B)
  val ShiftArithmetic_s2e = RegInit(false.B)
  val ShifterDrv_s2e = RegInit(false.B)
  
  // MEM Stage
  val RetFromExcept_s1m = RegInit(false.B)
  
  // Local signals
  val branch_s1e = RegInit(false.B)
  val branch_s2e = RegInit(false.B)
  
  // Decoded branch signals
  val instrIsBEQ_s1e = RegInit(false.B)
  val instrIsBNE_s1e = RegInit(false.B)
  val instrIsBLEZ_s1e = RegInit(false.B)
  val instrIsBGTZ_s1e = RegInit(false.B)
  val instrIsBLTZ_s1e = RegInit(false.B)
  val instrIsBGEZ_s1e = RegInit(false.B)
  
  // Instruction fields for easier access
  val opcode = io.AInstr_s2r(31, 26)
  val rs = io.AInstr_s2r(25, 21)
  val rt = io.AInstr_s2r(20, 16)
  val rd = io.AInstr_s2r(15, 11)
  val shamt = io.AInstr_s2r(10, 6)
  val funct = io.AInstr_s2r(5, 0)
  
  // Generate immediate select signals
  io.AImm26Bit_s2r := (io.AInstr_s2r(31, 27) === 5.U) || (io.AInstr_s2r(31, 28) === 4.U)
  io.AImmSigned_s2r := !(io.AInstr_s2r(31, 28) === 3.U)
  io.AImmShift16_s2r := (opcode === 15.U)
  
  // Derive control signals
  io.AUseT_s1e := AAddOp_s1e || AAndOp_s1e || AOrOp_s1e || AXorOp_s1e || ANorOp_s1e
  
  // PC control signals
  val killBrOrJ_s1e = io.AKill_s1e || (AIsBoosted_s1e && io.Squash_s1e) || io.Except_s1w
  io.RegPC_s1e := regPC_s1e && !killBrOrJ_s1e
  io.ImmPC_s1e := immPC_s1e && !killBrOrJ_s1e
  
  // Branch control signals
  io.BEQnext_s1e := instrIsBEQ_s1e && !killBrOrJ_s1e
  io.BNEnext_s1e := instrIsBNE_s1e && !killBrOrJ_s1e
  io.BLEZnext_s1e := instrIsBLEZ_s1e && !killBrOrJ_s1e
  io.BGTZnext_s1e := instrIsBGTZ_s1e && !killBrOrJ_s1e
  io.BLTZnext_s1e := instrIsBLTZ_s1e && !killBrOrJ_s1e
  io.BGEZnext_s1e := instrIsBGEZ_s1e && !killBrOrJ_s1e
  
  // Commit and Squash signals
  commit_s1e := (io.TakenBranch_s2e && !predictTaken_b_s2e) ||
                (branch_s2e && !io.TakenBranch_s2e && predictTaken_b_s2e)
  squash_s1e := (io.TakenBranch_s2e && predictTaken_b_s2e) ||
                (branch_s2e && !io.TakenBranch_s2e && !predictTaken_b_s2e)
  
  io.Commit_s1e := commit_s1e
  io.Squash_s1e := squash_s1e || (RetFromExcept_s1m && io.SquashBit_s1)
  
  // Decode instruction on Phi2 (negative edge equivalent)
  when(true.B) { // This represents the Phi2 condition
    // Check if B side instruction
    AWrong_s1e := (io.AInstr_s2r(31) === 1.U) ||
                  (io.AInstr_s2r(31, 28) === 4.U) ||
                  (opcode === SPECIAL && funct(5, 2) === 3.U)
    
    // Destination register determination
    when(opcode === SPECIAL &&
          !((funct(5, 3) === 1.U && funct(2, 0) =/= 1.U) ||
            funct(5, 3) === 3.U ||
            (funct(5, 3) === 2.U && funct(0) === 1.U))) {
      ADestIsRT_s1e := FALSE
      ADestIsRD_s1e := true.B
      ADestIs31_s1e := FALSE
    }.elsewhen(io.AInstr_s2r(31, 29) === 1.U) {
      ADestIsRT_s1e := true.B
      ADestIsRD_s1e := FALSE
      ADestIs31_s1e := FALSE
    }.elsewhen((opcode === 3.U) ||
               (opcode === 1.U && io.AInstr_s2r(20, 19) === 2.U)) {
      ADestIsRT_s1e := FALSE
      ADestIsRD_s1e := FALSE
      ADestIs31_s1e := true.B
    }.otherwise {
      ADestIsRT_s1e := FALSE
      ADestIsRD_s1e := FALSE
      ADestIs31_s1e := FALSE
    }
    
    // Boosted instruction
    AIsBoosted_s1e := io.AInstr_s2r(37) || io.AInstr_s2r(36) ||
                      io.AInstr_s2r(35) || io.AInstr_s2r(34) ||
                      io.AInstr_s2r(33) || io.AInstr_s2r(32)
    
    // Signed operations
    AAUopSigned_s1e := (opcode === 8.U) || // ADDI
                       (opcode === SPECIAL && funct(5, 3) === 4.U &&
                        (funct(2, 0) === 0.U || funct(2, 0) === 2.U))
    
    // ALU operations
    AAddOp_s1e := (opcode === SPECIAL && funct(5, 1) === 16.U) ||
                  (io.AInstr_s2r(31, 27) === 4.U) ||
                  (io.AInstr_s2r(31) === 1.U)
    
    ASubOp_s1e := (opcode === SPECIAL && funct(5, 1) === 17.U)
    
    ASltOp_s1e := (opcode === SPECIAL && funct === 42.U) || (opcode === 10.U)
    
    ASltUOp_s1e := (opcode === SPECIAL && funct === 43.U) || (opcode === 11.U)
    
    AAndOp_s1e := (opcode === SPECIAL && funct === 36.U) || (opcode === 12.U)
    
    AOrOp_s1e := (opcode === SPECIAL && funct === 37.U) ||
                 (opcode === 13.U) || (opcode === 15.U)
    
    AXorOp_s1e := (opcode === SPECIAL && funct === 38.U) || (opcode === 14.U)
    
    ANorOp_s1e := (opcode === SPECIAL && funct === 39.U)
    
    MultOp_s1e := (opcode === SPECIAL && funct(5, 1) === 12.U)
    
    DivOp_s1e := (opcode === SPECIAL && funct(5, 1) === 13.U)
    
    SignedMDOp_s1e := (opcode === SPECIAL && funct(5, 2) === 6.U && funct(0) === 0.U)
    
    LoadHiLo_s1e := (opcode === SPECIAL && funct(5, 2) === 4.U && funct(0) === 0.U)
    
    StoreHiLo_s1e := (opcode === SPECIAL && funct(5, 2) === 4.U && funct(0) === 1.U)
    
    HiLo_s1e := (opcode === SPECIAL && funct(5, 2) === 4.U && !funct(1))
    
    AUseImm_s1e := (io.AInstr_s2r(31, 29) === 1.U) ||
                   (io.AInstr_s2r(31, 29) === 0.U && io.AInstr_s2r(28, 26) =/= 0.U) ||
                   (opcode === 15.U)
    
    AALUDrv_s1e := (io.AInstr_s2r(31, 29) === 1.U) ||
                   (opcode === SPECIAL && funct(5, 3) =/= 0.U) ||
                   (io.AInstr_s2r(31, 30) === 2.U)
    
    ShiftLeft_s1e := (opcode === SPECIAL && funct(5, 3) === 0.U && funct(1, 0) === 0.U)
    
    ShiftArithmetic_s1e := (funct(1, 0) === 3.U)
    
    ShifterDrv_s1e := (opcode === SPECIAL && funct(5, 3) === 0.U)
    
    // Branch instructions
    instrIsBEQ_s1e := (!io.AInstr_s2r(31) && !io.AInstr_s2r(29) &&
                       (io.AInstr_s2r(28, 26) === 4.U))
    
    instrIsBNE_s1e := (!io.AInstr_s2r(31) && !io.AInstr_s2r(29) &&
                       (io.AInstr_s2r(28, 26) === 5.U))
    
    instrIsBLEZ_s1e := (!io.AInstr_s2r(31) && !io.AInstr_s2r(29) &&
                        (io.AInstr_s2r(28, 26) === 6.U))
    
    instrIsBGTZ_s1e := (!io.AInstr_s2r(31) && !io.AInstr_s2r(29) &&
                        (io.AInstr_s2r(28, 26) === 7.U))
    
    instrIsBLTZ_s1e := (opcode === BCOND) && (io.AInstr_s2r(16) === 0.U)
    
    instrIsBGEZ_s1e := (opcode === BCOND) && (io.AInstr_s2r(16) === 1.U)
    
    branch_s1e := (!io.AInstr_s2r(31) && !io.AInstr_s2r(29) && io.AInstr_s2r(28)) ||
                   (opcode === BCOND)
    
    predictTaken_b_s1e := io.AInstr_s2r(30) ||
                          ((opcode === BCOND) && io.AInstr_s2r(19))
    
    PCDrvResult_s1e := (opcode === 3.U) ||
                       (opcode === 1.U && io.AInstr_s2r(20, 19) === 2.U) ||
                       (opcode === SPECIAL && funct === 9.U)
    
    immPC_s1e := (io.AInstr_s2r(31, 27) === 1.U)
    
    regPC_s1e := (opcode === SPECIAL && funct(5, 1) === 4.U)
  }
  
  // Pipeline registers on Phi1 (positive edge equivalent)
  when(!io.Stall_s1 && !io.AKill_s1e) {
    AAddOp_s2e := AAddOp_s1e
    ASubOp_s2e := ASubOp_s1e
    ASltOp_s2e := ASltOp_s1e
    ASltUOp_s2e := ASltUOp_s1e
    AAndOp_s2e := AAndOp_s1e
    AOrOp_s2e := AOrOp_s1e
    AXorOp_s2e := AXorOp_s1e
    ANorOp_s2e := ANorOp_s1e
    MultOp_s2e := MultOp_s1e
    DivOp_s2e := DivOp_s1e
    SignedMDOp_s2e := SignedMDOp_s1e
    LoadHiLo_s2e := LoadHiLo_s1e
    StoreHiLo_s2e := StoreHiLo_s1e
    HiLo_s2e := HiLo_s1e
    AAUopSigned_s2e := AAUopSigned_s1e
    ShiftLeft_s2e := ShiftLeft_s1e
    ShiftArithmetic_s2e := ShiftArithmetic_s1e
  }
  
  when(!io.Stall_s1) {
    AALUDrv_s2e := AALUDrv_s1e && !io.AKill_s1e
    ShifterDrv_s2e := ShifterDrv_s1e && !io.AKill_s1e
    branch_s2e := branch_s1e && !killBrOrJ_s1e
    predictTaken_b_s2e := predictTaken_b_s1e
    PCDrvResult_s2e := PCDrvResult_s1e && !io.AKill_s1e
    AIsBoosted_s2e := AIsBoosted_s1e
  }
  
  // MEM stage register
  RetFromExcept_s1m := io.RetFromExcept_s2e
  
  // Connect outputs
  io.ADestIsRT_s1e := ADestIsRT_s1e
  io.ADestIsRD_s1e := ADestIsRD_s1e
  io.ADestIs31_s1e := ADestIs31_s1e
  io.AAddOp_s2e := AAddOp_s2e
  io.ASubOp_s2e := ASubOp_s2e
  io.ASltOp_s2e := ASltOp_s2e
  io.ASltUOp_s2e := ASltUOp_s2e
  io.AAndOp_s2e := AAndOp_s2e
  io.AOrOp_s2e := AOrOp_s2e
  io.AXorOp_s2e := AXorOp_s2e
  io.ANorOp_s2e := ANorOp_s2e
  io.MultOp_s2e := MultOp_s2e
  io.DivOp_s2e := DivOp_s2e
  io.SignedMDOp_s2e := SignedMDOp_s2e
  io.AUseImm_s1e := AUseImm_s1e
  io.LoadHiLo_s2e := LoadHiLo_s2e
  io.StoreHiLo_s2e := StoreHiLo_s2e
  io.HiLo_s2e := HiLo_s2e
  io.AALUDrv_s2e := AALUDrv_s2e
  io.AAUopSigned_s2e := AAUopSigned_s2e
  io.ShiftLeft_s2e := ShiftLeft_s2e
  io.ShiftArithmetic_s2e := ShiftArithmetic_s2e
  io.ShifterDrv_s2e := ShifterDrv_s2e
  io.PCDrvResult_s2e := PCDrvResult_s2e
  io.AIsBoosted_s2e := AIsBoosted_s2e
  io.AWrong_s1e := AWrong_s1e
}

object VerilogGenerator extends App {
  emitVerilog(new AdecSE1(), args)
}