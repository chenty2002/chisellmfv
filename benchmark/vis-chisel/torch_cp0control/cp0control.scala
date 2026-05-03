package llmverify

import chisel3._
import chisel3.util._

class cp0control extends Module {
  val io = IO(new Bundle {
    // Clocks & Stalls (Clock is implicit in Chisel)
    val Stall_s1 = Input(Bool())
    
    // Latched Reset Signal
    val Reset_s1 = Input(Bool())
    val Reset_s2 = Input(Bool())
    
    // info needed to process exceptions
    val BoostedExcept_v2 = Input(Bool())
    val SeqExcept_v2 = Input(Bool())
    val TLBL1_s1w = Input(Bool())
    val MPc_s2 = Input(Bool())
    val MPp_s2 = Input(Bool())
    val MPo_s2 = Input(Bool())
    val KUc_s2 = Input(Bool())
    val KUp_s2 = Input(Bool())
    val KUo_s2 = Input(Bool())
    val BEP_s2 = Input(Bool())
    val BEV_s1 = Input(Bool())
    val BSp_s2 = Input(Bool())
    val BSc_s2 = Input(Bool())
    val Commit_s1e = Input(Bool())
    val Squash_s1e = Input(Bool())
    val SetBoost_s1w = Input(Bool())
    
    // Information needed for moves from/to cp0 registers
    val CopRegNum_s1m = Input(UInt(4.W))
    
    // Decoded instructions
    val RetFromExcept_s2e = Input(Bool())
    val MvToCop0_s1m = Input(Bool())
    val MvFromCop0_s1m = Input(Bool())
    
    // Exception taken
    val Except_s1w = Output(Bool())
    val BExTaken_s1w = Output(Bool())
    val ExceptVector_s1i = Output(UInt(3.W))
    
    // Decoded instructions
    val writeContext_s1w = Output(Bool())
    val defContext_s1w = Output(Bool())
    val writeCause_s1w = Output(Bool())
    val defCause_s1w = Output(Bool())
    val writeStatus_s1w = Output(Bool())
    val defStatus_s1w = Output(Bool())
    val newBSc_s1 = Output(Bool())
    val newBEP_s1 = Output(Bool())
    
    // Other Outputs
    val BrDelaySlot_s1w = Output(Bool())
    val Squash_s1w = Output(Bool())
    val PushStatus_s1w = Output(Bool())
    val PopStatus_s1w = Output(Bool())
    val MipsMode_s2e = Output(Bool())
    val SystemBit_s2e = Output(Bool())
    val drvCp0Bus_q2m = Output(Bool())
    
    // Decoded Outputs
    val IndexSel_s1m = Output(Bool())
    val RandomSel_s1m = Output(Bool())
    val EntryLoSel_s1m = Output(Bool())
    val EntryHiSel_s1m = Output(Bool())
    val ContextSel_s2m = Output(Bool())
    val BadVAddrSel_s2m = Output(Bool())
    val StatusSel_s2m = Output(Bool())
    val CauseSel_s2m = Output(Bool())
    val EPCSel_s1m = Output(Bool())
    val EPCNSel_s1m = Output(Bool())
  })
  
  // Exception vectors
  val RST = 0.U(2.W)
  val CEV = 1.U(2.W)
  val TLBR = 2.U(2.W)
  val BEX = 3.U(2.W)
  
  // Internal registers
  val ExceptVector_s1i = RegInit(0.U(3.W))
  val RetFromExcept_s1m = RegInit(false.B)
  val RetFromExcept_s2m = RegInit(false.B)
  
  // Move from/to cp0 register
  val ContextSel_s2m = RegInit(false.B)
  val BadVAddrSel_s2m = RegInit(false.B)
  val StatusSel_s2m = RegInit(false.B)
  val CauseSel_s2m = RegInit(false.B)
  
  // Commit/Squash pipeline
  val Commit_s2e = RegInit(false.B)
  val Commit_s1m = RegInit(false.B)
  val Commit_s2m = RegInit(false.B)
  val Squash_s2e = RegInit(false.B)
  val Squash_s1m = RegInit(false.B)
  val Squash_s2m = RegInit(false.B)
  val Squash_s1w = RegInit(false.B)
  
  val BrDelaySlot_s2m = Wire(Bool())
  val BrDelaySlot_s1w = RegInit(false.B)
  
  // Exceptions
  val PopStatus_s1w = RegInit(false.B)
  val PushStatus_s1w = Wire(Bool())
  val SetBEP_s1w = RegInit(false.B)
  val BExTaken_s1w_reg = RegInit(false.B)
  val SEx_s1w = RegInit(false.B)
  
  // Determine Exception type
  val BExNoted_v2 = Wire(Bool())
  val BExTaken_v2 = Wire(Bool())
  val SEx_v2 = Wire(Bool())
  val BES_v2 = Wire(Bool())
  val SES_v2 = Wire(Bool())
  val SENS_v2 = Wire(Bool())
  val CBSc_v2 = Wire(Bool())
  val CBEP_v2 = Wire(Bool())
  
  val MipsMode_s2e = Wire(Bool())
  val SystemBit_s2e = Wire(Bool())
  
  // Delayed signals
  val MvToCop0_s2m = RegInit(false.B)
  val MvToCop0_s1w = RegInit(false.B)
  val MvFromCop0_s2m = RegInit(false.B)
  val ContextSel_s1w = RegInit(false.B)
  val StatusSel_s1w = RegInit(false.B)
  val CauseSel_s1w = RegInit(false.B)
  val BEP_s1 = RegInit(false.B)
  val BSc_s1 = RegInit(false.B)
  
  // Phi2 signal (inverse of clock phase)
  val Phi2 = RegInit(true.B)
  Phi2 := !Phi2
  
  // Determine if WB of a branch delay slot
  BrDelaySlot_s2m := Commit_s2m || Squash_s2m
  
  // Determine exception type during Phi2-MEM
  BES_v2 := io.BoostedExcept_v2 && Commit_s2m
  SES_v2 := io.SeqExcept_v2 && BrDelaySlot_s2m
  SENS_v2 := io.SeqExcept_v2 && !BrDelaySlot_s2m
  CBSc_v2 := Commit_s2m && io.BSc_s2
  CBEP_v2 := Commit_s2m && (io.BEP_s2 && !RetFromExcept_s2m)
  
  BExNoted_v2 := (io.BSp_s2 && RetFromExcept_s2m) || (io.BoostedExcept_v2 && !SENS_v2)
  BExTaken_v2 := BES_v2 || CBEP_v2 || (SES_v2 && CBSc_v2)
  SEx_v2 := SENS_v2 || (SES_v2 && !CBSc_v2)
  
  // Generate exception control signals during Phi1-WB
  // Using RegNext to simulate Phi2 timing
  when(Phi2) {
    SEx_s1w := SEx_v2
    BExTaken_s1w_reg := BExTaken_v2
    SetBEP_s1w := BExNoted_v2 && !BExTaken_v2
  }
  
  PushStatus_s1w := SEx_s1w || BExTaken_s1w_reg
  io.Except_s1w := PushStatus_s1w || io.Reset_s1
  
  // Exception vector encoder
  when(io.Reset_s1) {
    ExceptVector_s1i := Cat(io.BEV_s1, RST)
  }.elsewhen(BExTaken_s1w_reg) {
    ExceptVector_s1i := Cat(io.BEV_s1, BEX)
  }.elsewhen(io.TLBL1_s1w) {
    ExceptVector_s1i := Cat(io.BEV_s1, TLBR)
  }.otherwise {
    ExceptVector_s1i := Cat(io.BEV_s1, CEV)
  }
  
  // RFE handling (Return From Exception)
  when(!io.Stall_s1) {
    RetFromExcept_s2m := RetFromExcept_s1m && !io.Except_s1w
  }
  
  // Using Phi2 timing
  when(Phi2) {
    RetFromExcept_s1m := io.RetFromExcept_s2e
    PopStatus_s1w := RetFromExcept_s2m
  }
  
  // System Bit/Mips Mode Handling
  MipsMode_s2e := !io.Reset_s2 && Mux(
    (!io.RetFromExcept_s2e && !RetFromExcept_s2m),
    io.MPc_s2,
    Mux(
      (io.RetFromExcept_s2e && RetFromExcept_s2m),
      io.MPo_s2,
      io.MPp_s2
    )
  )
  
  SystemBit_s2e := !io.Reset_s2 && Mux(
    (!io.RetFromExcept_s2e && !RetFromExcept_s2m),
    io.KUc_s2,
    Mux(
      (io.RetFromExcept_s2e && RetFromExcept_s2m),
      io.KUo_s2,
      io.KUp_s2
    )
  )
  
  // --- Register Decoder ---
  io.IndexSel_s1m := (io.CopRegNum_s1m === 0.U)
  io.RandomSel_s1m := (io.CopRegNum_s1m === 1.U)
  io.EntryLoSel_s1m := (io.CopRegNum_s1m === 2.U)
  io.EntryHiSel_s1m := (io.CopRegNum_s1m === 10.U)
  io.EPCSel_s1m := (io.CopRegNum_s1m === 14.U) && io.MvFromCop0_s1m
  io.EPCNSel_s1m := (io.CopRegNum_s1m === 11.U) && io.MvFromCop0_s1m
  
  when(!io.Stall_s1) {
    ContextSel_s2m := (io.CopRegNum_s1m === 4.U)
    BadVAddrSel_s2m := (io.CopRegNum_s1m === 8.U)
    StatusSel_s2m := (io.CopRegNum_s1m === 12.U)
    CauseSel_s2m := (io.CopRegNum_s1m === 13.U)
  }
  
  // Using Phi2 timing
  when(Phi2) {
    ContextSel_s1w := ContextSel_s2m
    StatusSel_s1w := StatusSel_s2m
    CauseSel_s1w := CauseSel_s2m
  }
  
  // --- Datapath Control ---
  // Context Register
  io.writeContext_s1w := !io.Except_s1w && MvToCop0_s1w && ContextSel_s1w
  io.defContext_s1w := !io.writeContext_s1w && !io.Except_s1w
  
  // Cause Register
  io.writeCause_s1w := !io.Except_s1w && MvToCop0_s1w && CauseSel_s1w
  io.defCause_s1w := !io.writeCause_s1w && !io.Except_s1w
  
  // Status register
  io.writeStatus_s1w := !io.Except_s1w && !PopStatus_s1w && MvToCop0_s1w && StatusSel_s1w
  io.defStatus_s1w := !io.writeStatus_s1w && !PushStatus_s1w && !PopStatus_s1w
  io.newBSc_s1 := !BrDelaySlot_s1w && ((SetBEP_s1w && !PushStatus_s1w) || (BSc_s1 && !BExTaken_s1w_reg))
  io.newBEP_s1 := SetBEP_s1w || BEP_s1
  
  // Cp0 Bus driver
  io.drvCp0Bus_q2m := Phi2 && MvFromCop0_s2m
  
  // --- Delayed Signals ---
  when(!io.Stall_s1) {
    Commit_s2e := io.Commit_s1e && !io.Except_s1w
    Commit_s2m := Commit_s1m && !io.Except_s1w
    Squash_s2e := io.Squash_s1e && !io.Except_s1w
    Squash_s2m := Squash_s1m && !io.Except_s1w
  }
  
  // Using Phi2 timing
  when(Phi2) {
    Squash_s1m := Squash_s2e
    Squash_s1w := Squash_s2m
    Commit_s1m := Commit_s2e
    BrDelaySlot_s1w := BrDelaySlot_s2m
  }
  
  when(!io.Stall_s1) {
    MvToCop0_s2m := io.MvToCop0_s1m && !io.Except_s1w
    MvFromCop0_s2m := io.MvFromCop0_s1m && !io.Except_s1w
  }
  
  when(Phi2) {
    MvToCop0_s1w := MvToCop0_s2m
  }
  
  when(Phi2) {
    BEP_s1 := io.BEP_s2
    BSc_s1 := io.BSc_s2
  }
  
  // Connect outputs
  io.ExceptVector_s1i := ExceptVector_s1i
  io.BExTaken_s1w := BExTaken_s1w_reg
  io.BrDelaySlot_s1w := BrDelaySlot_s1w
  io.Squash_s1w := Squash_s1w
  io.PushStatus_s1w := PushStatus_s1w
  io.PopStatus_s1w := PopStatus_s1w
  io.MipsMode_s2e := MipsMode_s2e
  io.SystemBit_s2e := SystemBit_s2e
  io.ContextSel_s2m := ContextSel_s2m
  io.BadVAddrSel_s2m := BadVAddrSel_s2m
  io.StatusSel_s2m := StatusSel_s2m
  io.CauseSel_s2m := CauseSel_s2m
}

object VerilogGenerator extends App {
  emitVerilog(new cp0control(), args)
}