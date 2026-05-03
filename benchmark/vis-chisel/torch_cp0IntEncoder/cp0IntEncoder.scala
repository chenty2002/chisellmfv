package llmverify

import chisel3._
import chisel3.util._

// Exception codes
object ExceptionCodes {
  val E_Int  = 0.U(5.W)  // external interrupt
  val E_Mod  = 1.U(5.W)  // tlb modified exception
  val E_TLBL = 2.U(5.W)  // tlb exception (load or Ifetch)
  val E_TLBS = 3.U(5.W)  // tlb exception (store)
  val E_AdEL = 4.U(5.W)  // address error (load)
  val E_AdES = 5.U(5.W)  // address error (store)
  val E_Sys  = 8.U(5.W)  // syscall exception
  val E_Bp   = 9.U(5.W)  // breakpoint exception
  val E_OvA  = 12.U(5.W) // arith overflow exception (A-side)
  val E_OvB  = 13.U(5.W) // arith overflow exception (B-side)
  val E_Bst  = 14.U(5.W) // boosted exception
}

class cp0IntEncoder extends Module {
  val io = IO(new Bundle {
    // Clocks & Stalls
    val Phi1 = Input(Bool())
    val Stall_s1 = Input(Bool())
    
    // Exceptions
    val Except_s1w = Input(Bool())
    val AALUOvfl_v2e = Input(Bool())
    val BALUOvfl_v2e = Input(Bool())
    val Syscall_s2m = Input(Bool())
    val Break_s2m = Input(Bool())
    val TLBRefill_v2m = Input(Bool())
    val TLBInvalid_v2m = Input(Bool())
    val TLBModified_v2m = Input(Bool())
    val MemExcept_s2m = Input(Bool())
    val Interrupt_w = Input(UInt(6.W))
    val IntPending_s2 = Input(UInt(2.W))
    
    // Other info needed to process exceptions
    val IntMask_s2 = Input(UInt(8.W)) // Interrupt Mask
    val IEc = Input(Bool()) // Interrupt enable
    val BExTaken_s1w = Input(Bool())
    val InstrIsStore_s1m = Input(Bool())
    val InstrIsLoad_s1m = Input(Bool())
    val AIsBoosted_s2e = Input(Bool()) // Boosted exception?
    val BIsBoosted_s2e = Input(Bool())
    
    // Outputs
    val BoostedExcept_v2 = Output(Bool())
    val SeqExcept_v2 = Output(Bool()) // Type of exception
    val SetBoost_s1w = Output(Bool()) // A or B inst boosted
    val ExceptionCause_s1w = Output(UInt(5.W)) // To status register
    val Int_s1 = Output(UInt(6.W)) // External Interrupt level
    val Reset_s1 = Input(Bool())
    val Reset_s2 = Output(Bool()) // Latched Reset
    val TLBL1_s1w = Output(Bool())
  })
  
  // Internal registers
  val ExceptionCauseTmp_s1w = RegInit(0.U(5.W))
  val ExceptionCause_v2 = RegInit(0.U(5.W))
  val InstrIsLoad_s2m = RegInit(false.B)
  val InstrIsStore_s2m = RegInit(false.B)
  val AALUOvfl_s1m = RegInit(false.B)
  val AALUOvfl_s2m = RegInit(false.B)
  val BALUOvfl_s1m = RegInit(false.B)
  val BALUOvfl_s2m = RegInit(false.B)
  val AIsBoosted_s1m = RegInit(false.B)
  val AIsBoosted_s2m = RegInit(false.B)
  val BIsBoosted_s1m = RegInit(false.B)
  val BIsBoosted_s2m = RegInit(false.B)
  val SetBoost_s1w_reg = RegInit(false.B)
  val Int_s2 = RegInit(0.U(6.W))
  val Int_s1_reg = RegInit(0.U(6.W))
  val Reset_s2_reg = RegInit(false.B)
  val TLBL1_s1w_reg = RegInit(false.B)
  
  // Wire signals
  val TLBL1_v2 = Wire(Bool()) // TLB miss on inst access
  val TLBL2_v2 = Wire(Bool()) // TLB miss on load access
  val TLBS_v2 = Wire(Bool()) // TLB miss on store access
  val AdEL_v2 = Wire(Bool()) // Address error on load
  val AdES_v2 = Wire(Bool()) // Address error on store
  val ExtExcept_s2 = Wire(Bool())
  val Phi2 = Wire(Bool())
  
  // Generate Phi2 as complement of Phi1
  Phi2 := !io.Phi1
  
  // Two-phase clocking simulation using registers
  // Phase 1 operations (Phi1 high)
  when(io.Phi1) {
    Reset_s2_reg := io.Reset_s1
  }
  
  when(io.Phi1 && !io.Stall_s1) {
    Int_s2 := io.Interrupt_w
    AIsBoosted_s2m := AIsBoosted_s1m && !io.Except_s1w
    BIsBoosted_s2m := BIsBoosted_s1m && !io.Except_s1w
    InstrIsLoad_s2m := io.InstrIsLoad_s1m && !io.Except_s1w
    InstrIsStore_s2m := io.InstrIsStore_s1m && !io.Except_s1w
    AALUOvfl_s2m := AALUOvfl_s1m && !io.Except_s1w
    BALUOvfl_s2m := BALUOvfl_s1m && !io.Except_s1w
  }
  
  // Phase 2 operations (Phi2 high)
  when(Phi2) {
    Int_s1_reg := Int_s2
    AIsBoosted_s1m := io.AIsBoosted_s2e
    BIsBoosted_s1m := io.BIsBoosted_s2e
    SetBoost_s1w_reg := AIsBoosted_s2m || BIsBoosted_s2m
    AALUOvfl_s1m := io.AALUOvfl_v2e
    BALUOvfl_s1m := io.BALUOvfl_v2e
    ExceptionCauseTmp_s1w := ExceptionCause_v2
    TLBL1_s1w_reg := TLBL1_v2
  }
  
  // Exception cause selection
  io.ExceptionCause_s1w := Mux(io.BExTaken_s1w, ExceptionCodes.E_Bst, ExceptionCauseTmp_s1w)
  
  // Exception detection logic
  TLBL1_v2 := (io.TLBRefill_v2m || io.TLBInvalid_v2m) && 
              !(InstrIsLoad_s2m || InstrIsStore_s2m)
  TLBL2_v2 := (io.TLBRefill_v2m || io.TLBInvalid_v2m) && InstrIsLoad_s2m
  TLBS_v2 := (io.TLBRefill_v2m || io.TLBInvalid_v2m) && InstrIsStore_s2m
  AdEL_v2 := io.MemExcept_s2m && InstrIsLoad_s2m
  AdES_v2 := io.MemExcept_s2m && InstrIsStore_s2m
  
  val maskedInterrupts = Cat(Int_s2(5,0), io.IntPending_s2(1,0)) & io.IntMask_s2(7,0)
  val interruptEnable = io.IEc
  ExtExcept_s2 := maskedInterrupts.orR() && interruptEnable
  
  // Boosted and sequential exception logic
  io.BoostedExcept_v2 := !Reset_s2_reg && 
    ((AIsBoosted_s2m && AALUOvfl_s2m) ||
     (BIsBoosted_s2m && (BALUOvfl_s2m || io.Syscall_s2m || io.Break_s2m || 
                         AdEL_v2 || AdES_v2 || TLBL2_v2 || TLBS_v2 || 
                         io.TLBModified_v2m)))
  
  io.SeqExcept_v2 := Reset_s2_reg || ExtExcept_s2 || TLBL1_v2 || 
    ((!AIsBoosted_s2m && AALUOvfl_s2m) ||
     (!BIsBoosted_s2m && (BALUOvfl_s2m || io.Syscall_s2m || io.Break_s2m || 
                          AdEL_v2 || AdES_v2 || TLBL2_v2 || TLBS_v2 || 
                          io.TLBModified_v2m)))
  
  // Priority encoder
  when(Reset_s2_reg) {
    ExceptionCause_v2 := ExceptionCodes.E_Int
  }.elsewhen(TLBL1_v2) {
    ExceptionCause_v2 := ExceptionCodes.E_TLBL
  }.elsewhen(!AIsBoosted_s2m && AALUOvfl_s2m) {
    ExceptionCause_v2 := ExceptionCodes.E_OvA
  }.elsewhen(!BIsBoosted_s2m && BALUOvfl_s2m) {
    ExceptionCause_v2 := ExceptionCodes.E_OvB
  }.elsewhen(!BIsBoosted_s2m && io.Syscall_s2m) {
    ExceptionCause_v2 := ExceptionCodes.E_Sys
  }.elsewhen(!BIsBoosted_s2m && io.Break_s2m) {
    ExceptionCause_v2 := ExceptionCodes.E_Bp
  }.elsewhen(!BIsBoosted_s2m && AdEL_v2) {
    ExceptionCause_v2 := ExceptionCodes.E_AdEL
  }.elsewhen(!BIsBoosted_s2m && AdES_v2) {
    ExceptionCause_v2 := ExceptionCodes.E_AdES
  }.elsewhen(!BIsBoosted_s2m && TLBL2_v2) {
    ExceptionCause_v2 := ExceptionCodes.E_TLBL
  }.elsewhen(!BIsBoosted_s2m && TLBS_v2) {
    ExceptionCause_v2 := ExceptionCodes.E_TLBS
  }.elsewhen(!BIsBoosted_s2m && io.TLBModified_v2m) {
    ExceptionCause_v2 := ExceptionCodes.E_Mod
  }.elsewhen(ExtExcept_s2) {
    ExceptionCause_v2 := ExceptionCodes.E_Int
  }.otherwise {
    ExceptionCause_v2 := 31.U(5.W) // 5'b11111
  }
  
  // Output assignments
  io.SetBoost_s1w := SetBoost_s1w_reg
  io.Int_s1 := Int_s1_reg
  io.Reset_s2 := Reset_s2_reg
  io.TLBL1_s1w := TLBL1_s1w_reg
}

object VerilogGenerator extends App {
  emitVerilog(new cp0IntEncoder(), args)
}