package llmverify

import chisel3._
import chisel3.util._

// Defines : Bit Assignments for Status Word
class StatusBits {
  val NonCache = 3
  val Dirty = 2
  val Valid = 1
  val Global = 0
}

// Defines : States for cache miss state machine
class TlbStates {
  val stage1e_s1 = 0.U  // IDLE state
  val extReq_s1 = 2.U   // send req to ext interface
  val tlbMiss_s1 = 4.U  // Missed in tlb gen except
  val stallRel_s1 = 6.U // Ifetch released istall
  val L2Miss_s1 = 8.U   // Miss on 2nd level cache
  val tagOp_s1 = 10.U   // tlb operation on miss
  val stage2e_s2 = 1.U  // translation for I$ miss
  val dataVal_s2 = 3.U  // data on it way
  val IStall_s2 = 5.U   // Istall still high on tlb miss
  val stallRel_s2 = 7.U // IStall is now low
  val tlbMissExcp_s2 = 9.U // Generate exception now
  val L2Refill_s2 = 11.U // L2 is now happy
  val idle_s2 = 13.U    // IDLE
  val tlbOp_s2 = 15.U   // second phase of tlb op
}

class tlbControlIO extends Bundle {
  // Clocks and control
  val Phi1 = Input(Clock())
  val MemStall_s1 = Input(Bool())
  val Stall_s1 = Input(Bool())
  val Reset_s1 = Input(Bool())
  
  // Address buses
  val MemAddr_s1m = Input(UInt(3.W))
  val instrAddr_s1e = Input(UInt(3.W))
  
  // TLB Register Select Signals
  val IndexSel_s1m = Input(Bool())
  val RandomSel_s1m = Input(Bool())
  val EntryHiSel_s1m = Input(Bool())
  val EntryLoSel_s1m = Input(Bool())
  val IndexSel_s2m = Output(Bool())
  val RandomSel_s2m = Output(Bool())
  val EntryHiSel_s2m = Output(Bool())
  val EntryLoSel_s2m = Output(Bool())
  
  // ICache Miss Signals
  val IStall_s1 = Input(Bool())
  val ICacheMiss_v2r = Input(Bool())
  val ICacheMiss_s1e = Output(Bool())
  val ICMiss_s1 = Output(Bool())
  val selMemAddr_s1m = Output(Bool())
  val ReqLength_s1 = Output(UInt(6.W))
  val MipsMode_s2e = Input(Bool())
  val NonCacheable_s1 = Output(Bool())
  val ExtRead_s1 = Output(Bool())
  val ExtRequest_s1 = Output(Bool())
  val ExtDataValid_s2 = Input(Bool())
  val L2Miss_s2 = Input(Bool())
  
  // TLB Miss on ICache Miss
  val ItlbMiss_v2e = Output(Bool())
  
  // Decoded Op Codes - TLB Instructions
  val MvToCop0_s1m = Input(Bool())
  val MvFromCop0_s1m = Input(Bool())
  val TLBWriteI_s1m = Input(Bool())
  val TLBWriteR_s1m = Input(Bool())
  val TLBWrite_s1m = Output(Bool())
  val TLBWriteOrProbe_s1m = Output(Bool())
  val TLBProbe_s1m = Input(Bool())
  val TLBRead_s1m = Input(Bool())
  val TLBProbe_s1w = Output(Bool())
  val TLBRead_s1w = Output(Bool())
  val InstrIsLoad_s1m = Input(Bool())
  val InstrIsStore_s1m = Input(Bool())
  
  // TLB Outputs
  val NonCacheable_v2m = Output(Bool())
  
  // TLB Exception signals
  val TLBRefill_v2m = Output(Bool())
  val TLBInvalid_v2m = Output(Bool())
  val TLBModified_v2m = Output(Bool())
  val Except_s1w = Input(Bool())
  
  // CP0 Internal Registers/Signals
  val EntryHi_s2w = Input(UInt(6.W))
  val pid_v2m = Input(UInt(6.W))
  val statusBits_v2m = Input(UInt(4.W))
  val tlbDrive_v2m = Output(Bool())
  val TLBHit_v2m = Input(Bool())
  
  // Additional signals
  val selSaveInstr_s1 = Output(Bool())
  val enabSaveInstrLatch_s1 = Output(Bool())
  val unCacheOrMap_s2e = Output(Bool())
  val drvSharedMemAddr_s1 = Output(Bool())
  val randomEqual8_v1 = Input(Bool())
  val resetRandom_v1 = Output(Bool())
  
  // Latch enable signals
  val enabIndexLatch_s1w = Output(Bool())
  val enabEntryHiLatch_s1w = Output(Bool())
  val enabEntryLoLatch_s1w = Output(Bool())
  val TLBTranslation_s1m = Output(Bool())
  val enabPOLatch_s2m = Output(Bool())
}

class tlbControl extends Module {
  val io = IO(new tlbControlIO)
  
  val statusBits = new StatusBits()
  val states = new TlbStates()
  
  // Internal registers
  val Reset_s2 = RegInit(false.B)
  val restoreAddr_s1 = RegInit(false.B)
  val restoreAddr_s2 = RegInit(false.B)
  
  // TLB Register Select Signals - stage 2
  val IndexSel_s2m = RegInit(false.B)
  val RandomSel_s2m = RegInit(false.B)
  val EntryHiSel_s2m = RegInit(false.B)
  val EntryLoSel_s2m = RegInit(false.B)
  
  // TLB Register Select Signals - stage 1 write
  val IndexSel_s1w = RegInit(false.B)
  val EntryHiSel_s1w = RegInit(false.B)
  val EntryLoSel_s1w = RegInit(false.B)
  
  // ICache Miss Signals
  val ICacheMiss_s1e = RegInit(false.B)
  val ICacheMiss_s1 = RegInit(false.B)
  val ICacheMiss_s2 = RegInit(false.B)
  val MipsMode_s1m = RegInit(false.B)
  val nonCacheable_s1 = RegInit(false.B)
  val NonCacheable_s2 = RegInit(false.B)
  val ExtRequest_s1 = RegInit(false.B)
  
  // State machine
  val tlbState_s1 = RegInit(states.stage1e_s1)
  val tlbState_s2 = RegInit(states.idle_s2)
  
  // TLB instruction registers
  val MvToCop0_s2m = RegInit(false.B)
  val MvFromCop0_s2m = RegInit(false.B)
  val MvToCop0_s1w = RegInit(false.B)
  val TLBProbe_s2m = RegInit(false.B)
  val TLBProbe_s1w = RegInit(false.B)
  val TLBRead_s2m = RegInit(false.B)
  val TLBRead_s1w = RegInit(false.B)
  val InstrIsLoad_s2m = RegInit(false.B)
  val InstrIsStore_s2m = RegInit(false.B)
  
  // Exception registers
  val TLBRefill_v2m = RegInit(false.B)
  val TLBInvalid_v2m = RegInit(false.B)
  val TLBModified_v2m = RegInit(false.B)
  val Except_s2w = RegInit(false.B)
  val Except_s1i = RegInit(false.B)
  
  // Counters and other registers
  val bytesleft_s1 = RegInit(0.U(6.W))
  val bytesleft_s2 = RegInit(0.U(6.W))
  val cycles_s1 = RegInit(0.U(5.W))
  val cycles_s2 = RegInit(0.U(5.W))
  val unCacheMap_s2e = RegInit(false.B)
  val unMapped_s2e = RegInit(false.B)
  val TLBTranslation_s2m = RegInit(false.B)
  
  // Combinational logic
  val tlbValidHit_v2m = io.TLBHit_v2m && io.statusBits_v2m(statusBits.Valid)
  val enabIndexMvToCop0_s1w = MvToCop0_s1w && !io.Except_s1w && !Except_s1i && IndexSel_s1w
  val enabEntryHiMvToCop0_s1w = (MvToCop0_s1w && !io.Except_s1w && !Except_s1i && EntryHiSel_s1w) || io.Reset_s1
  val enabEntryLoMvToCop0_s1w = MvToCop0_s1w && !io.Except_s1w && !Except_s1i && EntryLoSel_s1w
  val enabPhysicalOffset_s2m = (tlbState_s2 === 1.U) || Reset_s2
  val enabPhysicalOffsetPlus2_s2 = (tlbState_s2 === 3.U) && (bytesleft_s2 =/= 0.U) && 
    (((cycles_s2 === 0.U) && (!io.L2Miss_s2 && !NonCacheable_s2)) ||
     (NonCacheable_s2 && io.ExtDataValid_s2))
  
  // Output assignments
  io.IndexSel_s2m := IndexSel_s2m
  io.RandomSel_s2m := RandomSel_s2m
  io.EntryHiSel_s2m := EntryHiSel_s2m
  io.EntryLoSel_s2m := EntryLoSel_s2m
  io.ICacheMiss_s1e := ICacheMiss_s1e
  io.ICMiss_s1 := (ICacheMiss_s1e || ICacheMiss_s1) && !io.TLBWriteOrProbe_s1m
  io.selMemAddr_s1m := !io.TLBWriteOrProbe_s1m && !io.ICMiss_s1
  io.NonCacheable_s1 := ICacheMiss_s1 && nonCacheable_s1
  io.ExtRead_s1 := ICacheMiss_s1
  io.ExtRequest_s1 := ExtRequest_s1
  io.ItlbMiss_v2e := (tlbState_s2 === states.IStall_s2) || 
    (!(tlbValidHit_v2m || unMapped_s2e) && (tlbState_s2 === states.stage2e_s2))
  io.NonCacheable_v2m := unCacheMap_s2e || 
    (io.TLBHit_v2m && io.statusBits_v2m(statusBits.NonCache) && TLBTranslation_s2m)
  io.TLBWrite_s1m := (io.TLBWriteI_s1m || io.TLBWriteR_s1m) && (tlbState_s1 === states.stage1e_s1)
  io.TLBWriteOrProbe_s1m := (io.TLBWriteI_s1m || io.TLBWriteR_s1m || io.TLBProbe_s1m) && (tlbState_s1 === states.stage1e_s1)
  io.TLBProbe_s1w := TLBProbe_s1w
  io.TLBRead_s1w := TLBRead_s1w
  io.selSaveInstr_s1 := restoreAddr_s1
  io.enabSaveInstrLatch_s1 := io.Except_s1w || (tlbState_s1 === 0.U)
  io.unCacheOrMap_s2e := unMapped_s2e || unCacheMap_s2e
  io.drvSharedMemAddr_s1 := ExtRequest_s1
  io.resetRandom_v1 := io.randomEqual8_v1 || io.Reset_s1
  io.enabIndexLatch_s1w := enabIndexMvToCop0_s1w || TLBProbe_s1w
  io.enabEntryHiLatch_s1w := enabEntryHiMvToCop0_s1w || TLBRead_s1w
  io.enabEntryLoLatch_s1w := enabEntryLoMvToCop0_s1w || TLBRead_s1w
  io.TLBTranslation_s1m := (tlbState_s1 === states.stage1e_s1) && 
    (io.InstrIsLoad_s1m || io.InstrIsStore_s1m || ICacheMiss_s1e)
  io.enabPOLatch_s2m := enabPhysicalOffset_s2m || enabPhysicalOffsetPlus2_s2
  io.tlbDrive_v2m := MvFromCop0_s2m && (IndexSel_s2m || EntryHiSel_s2m || EntryLoSel_s2m || RandomSel_s2m)
  
  // Request length calculation
  io.ReqLength_s1 := Mux(ICacheMiss_s1, 
    Mux(io.NonCacheable_s1, 8.U, Mux(MipsMode_s1m, 32.U, 40.U)), 
    0.U)
  
  // Exception signal propagation
  when(io.Phi1.asBool) {
    Except_s2w := io.Except_s1w
  }
  
  when(!io.Phi1.asBool) { // Phi2
    Except_s1i := Except_s2w
  }
  
  // Register Move To/From Instructions
  when(io.Phi1.asBool) {
    IndexSel_s2m := io.IndexSel_s1m
    RandomSel_s2m := io.RandomSel_s1m
    EntryHiSel_s2m := io.EntryHiSel_s1m
    EntryLoSel_s2m := io.EntryLoSel_s1m
    MvToCop0_s2m := io.MvToCop0_s1m
    MvFromCop0_s2m := io.MvFromCop0_s1m
  }
  
  when(!io.Phi1.asBool) { // Phi2
    IndexSel_s1w := IndexSel_s2m
    EntryHiSel_s1w := EntryHiSel_s2m
    EntryLoSel_s1w := EntryLoSel_s2m
    MvToCop0_s1w := MvToCop0_s2m
  }
  
  // TLB Read, Write Index, Write Random, and Probe instructions
  when(io.Phi1.asBool && !io.Stall_s1) {
    TLBRead_s2m := io.TLBRead_s1m
    TLBProbe_s2m := io.TLBProbe_s1m
  }
  
  when(!io.Phi1.asBool) { // Phi2
    TLBRead_s1w := TLBRead_s2m
    TLBProbe_s1w := TLBProbe_s2m
  }
  
  // Setting up the address for translation
  when(io.Phi1.asBool) {
    when(ICacheMiss_s1e || ICacheMiss_s1) {
      unCacheMap_s2e := (io.instrAddr_s1e === 5.U)
      unMapped_s2e := (io.instrAddr_s1e(2, 1) === 2.U) // Using bits 2:1 for 31:30 equivalent
    }.otherwise {
      unCacheMap_s2e := (io.MemAddr_s1m === 5.U)
      unMapped_s2e := (io.MemAddr_s1m(2, 1) === 2.U) // Using bits 2:1 for 31:30 equivalent
    }
  }
  
  // TLB Translation
  when(io.Phi1.asBool) {
    InstrIsStore_s2m := io.InstrIsStore_s1m
    InstrIsLoad_s2m := io.InstrIsLoad_s1m
    TLBTranslation_s2m := io.TLBTranslation_s1m
  }
  
  // Priority Encoder for exceptions
  when(!io.Phi1.asBool) { // Phi2
    when(Except_s2w) {
      TLBRefill_v2m := false.B
      TLBModified_v2m := false.B
      TLBInvalid_v2m := false.B
    }.elsewhen(tlbState_s2 === states.tlbMissExcp_s2) {
      TLBRefill_v2m := true.B
      TLBModified_v2m := false.B
      TLBInvalid_v2m := false.B
    }.elsewhen(TLBTranslation_s2m) {
      when(!unMapped_s2e) {
        when(!io.TLBHit_v2m && (InstrIsStore_s2m || InstrIsLoad_s2m)) {
          TLBRefill_v2m := true.B
          TLBModified_v2m := false.B
          TLBInvalid_v2m := false.B
        }.elsewhen(io.TLBHit_v2m && 
          (!io.statusBits_v2m(statusBits.Global)) &&
          (io.EntryHi_s2w =/= io.pid_v2m) &&
          (InstrIsStore_s2m || InstrIsLoad_s2m || tlbState_s2 === states.stage2e_s2)) {
          TLBRefill_v2m := true.B
          TLBModified_v2m := false.B
          TLBInvalid_v2m := false.B
        }.elsewhen(io.TLBHit_v2m && 
          (!io.statusBits_v2m(statusBits.Valid)) &&
          (InstrIsStore_s2m || InstrIsLoad_s2m || tlbState_s2 === states.stage2e_s2)) {
          TLBInvalid_v2m := true.B
          TLBRefill_v2m := false.B
          TLBModified_v2m := false.B
        }.elsewhen(io.TLBHit_v2m && 
          (!io.statusBits_v2m(statusBits.Dirty)) &&
          (InstrIsStore_s2m && tlbState_s2 === states.stage2e_s2)) {
          TLBModified_v2m := true.B
          TLBRefill_v2m := false.B
          TLBInvalid_v2m := false.B
        }.otherwise {
          TLBModified_v2m := false.B
          TLBRefill_v2m := false.B
          TLBInvalid_v2m := false.B
        }
      }.otherwise {
        TLBModified_v2m := false.B
        TLBRefill_v2m := false.B
        TLBInvalid_v2m := false.B
      }
    }.otherwise {
      TLBModified_v2m := false.B
      TLBRefill_v2m := false.B
      TLBInvalid_v2m := false.B
    }
  }
  
  // State machine - Phi1 transitions
  when(io.Phi1.asBool && !io.MemStall_s1) {
    when(io.Reset_s1) {
      tlbState_s2 := states.idle_s2
    }.otherwise {
      when(tlbState_s1 === states.stage1e_s1) {
        tlbState_s2 := Mux(io.TLBWriteOrProbe_s1m, states.tlbOp_s2, 
          Mux(ICacheMiss_s1e, states.stage2e_s2, states.idle_s2))
      }.elsewhen(tlbState_s1 === states.tagOp_s1) {
        tlbState_s2 := Mux(ICacheMiss_s1, states.stage2e_s2, states.idle_s2)
      }.elsewhen(tlbState_s1 === states.extReq_s1) {
        tlbState_s2 := Mux(ICacheMiss_s1, states.dataVal_s2, states.idle_s2)
      }.elsewhen(tlbState_s1 === states.tlbMiss_s1) {
        tlbState_s2 := Mux(io.IStall_s1, states.IStall_s2, states.stallRel_s2)
      }.elsewhen(tlbState_s1 === states.stallRel_s1) {
        tlbState_s2 := states.tlbMissExcp_s2
      }.elsewhen(tlbState_s1 === states.L2Miss_s1) {
        tlbState_s2 := states.L2Refill_s2
      }.otherwise {
        tlbState_s2 := states.idle_s2
      }
    }
  }
  
  // State machine - Phi2 transitions
  when(!io.Phi1.asBool) { // Phi2
    when(tlbState_s2 === states.stage2e_s2) {
      tlbState_s1 := Mux(tlbValidHit_v2m || unMapped_s2e, states.extReq_s1, states.tlbMiss_s1)
    }.elsewhen(tlbState_s2 === states.dataVal_s2) {
      tlbState_s1 := Mux(io.L2Miss_s2, states.L2Miss_s1, states.extReq_s1)
    }.elsewhen(tlbState_s2 === states.IStall_s2) {
      tlbState_s1 := states.tlbMiss_s1
    }.elsewhen(tlbState_s2 === states.stallRel_s2) {
      tlbState_s1 := states.stallRel_s1
    }.elsewhen(tlbState_s2 === states.tlbMissExcp_s2) {
      tlbState_s1 := states.stage1e_s1
    }.elsewhen(tlbState_s2 === states.L2Refill_s2) {
      tlbState_s1 := Mux(io.L2Miss_s2, states.L2Miss_s1, states.stage1e_s1)
    }.elsewhen(tlbState_s2 === states.idle_s2) {
      tlbState_s1 := states.stage1e_s1
    }.elsewhen(tlbState_s2 === states.tlbOp_s2) {
      tlbState_s1 := states.tagOp_s1
    }.otherwise {
      tlbState_s1 := states.stage1e_s1
    }
  }
  
  // ExtRequest generation from state machine
  when(!io.Phi1.asBool) { // Phi2
    when(tlbState_s2 === states.L2Refill_s2) {
      ExtRequest_s1 := !io.L2Miss_s2
    }.elsewhen(tlbState_s2 === states.idle_s2) {
      ExtRequest_s1 := false.B
    }.elsewhen(tlbState_s2 === states.stage2e_s2) {
      ExtRequest_s1 := tlbValidHit_v2m || unMapped_s2e
    }.elsewhen(tlbState_s2 === states.dataVal_s2) {
      when(((cycles_s2 === 0.U) && (!io.L2Miss_s2 && !NonCacheable_s2)) ||
        (NonCacheable_s2 && io.ExtDataValid_s2)) {
        ExtRequest_s1 := Mux(bytesleft_s2 === 0.U, false.B, NonCacheable_s2)
      }.elsewhen(!io.L2Miss_s2) {
        ExtRequest_s1 := false.B
      }.otherwise {
        ExtRequest_s1 := false.B
      }
    }.otherwise {
      ExtRequest_s1 := false.B
    }
  }
  
  // ICacheMiss_s1 signal latch logic
  when(!io.Phi1.asBool) { // Phi2
    when(tlbState_s2 === states.stage2e_s2) {
      ICacheMiss_s1 := tlbValidHit_v2m || unMapped_s2e
    }.elsewhen(tlbState_s2 === states.dataVal_s2) {
      ICacheMiss_s1 := !(bytesleft_s2 === 0.U)
    }.otherwise {
      ICacheMiss_s1 := ICacheMiss_s2
    }
  }
  
  // Counter to watch bytes returning from external interface
  when(!io.Phi1.asBool) { // Phi2
    when((tlbState_s2 === states.stage2e_s2) && (tlbValidHit_v2m || unMapped_s2e)) {
      bytesleft_s1 := Mux(io.MipsMode_s2e, 32.U, 40.U)
    }.elsewhen(bytesleft_s2 =/= 0.U && io.ExtDataValid_s2) {
      bytesleft_s1 := bytesleft_s2 - 8.U
    }.elsewhen(bytesleft_s2 =/= 0.U) {
      bytesleft_s1 := bytesleft_s2
    }.otherwise {
      bytesleft_s1 := 0.U
    }
  }
  
  // Counter for cycles between address translation for ICache miss
  when(!io.Phi1.asBool) { // Phi2
    when(tlbState_s2 === states.stage2e_s2) {
      cycles_s1 := Mux(tlbValidHit_v2m || unMapped_s2e, 31.U, cycles_s2) // Assuming RATE = 32
    }.elsewhen(tlbState_s2 === states.dataVal_s2) {
      when((cycles_s2 === 0.U && !io.L2Miss_s2 && !NonCacheable_s2) ||
        (NonCacheable_s2 && io.ExtDataValid_s2)) {
        cycles_s1 := Mux(bytesleft_s2 === 0.U, cycles_s2, 31.U) // Assuming RATE = 32
      }.elsewhen(!io.L2Miss_s2) {
        cycles_s1 := Mux(cycles_s2 =/= 0.U, cycles_s2 - 1.U, 0.U)
      }.otherwise {
        cycles_s1 := cycles_s2
      }
    }.otherwise {
      cycles_s1 := cycles_s2
    }
  }
  
  // Restore address logic
  when(!io.Phi1.asBool) { // Phi2
    when((tlbState_s2 === states.L2Refill_s2) && !io.L2Miss_s2) {
      restoreAddr_s1 := true.B
    }.elsewhen((tlbState_s2 === states.idle_s2) || (tlbState_s2 === states.stage2e_s2)) {
      restoreAddr_s1 := false.B
    }.otherwise {
      restoreAddr_s1 := restoreAddr_s2
    }
  }
  
  // Latches - Phi1
  when(io.Phi1.asBool) {
    when(io.Except_s1w) {
      ICacheMiss_s2 := false.B
      restoreAddr_s2 := false.B
      NonCacheable_s2 := false.B
    }.otherwise {
      ICacheMiss_s2 := ICacheMiss_s1
      restoreAddr_s2 := restoreAddr_s1
      NonCacheable_s2 := nonCacheable_s1
    }
    bytesleft_s2 := bytesleft_s1
    cycles_s2 := cycles_s1
    Reset_s2 := io.Reset_s1
  }
  
  // Latches - Phi2
  when(!io.Phi1.asBool) { // Phi2
    nonCacheable_s1 := (io.NonCacheable_v2m && TLBTranslation_s2m) ||
      (NonCacheable_s2 && !TLBTranslation_s2m)
    MipsMode_s1m := io.MipsMode_s2e
    ICacheMiss_s1e := io.ICacheMiss_v2r
  }
  
  // Output exception signals
  io.TLBRefill_v2m := TLBRefill_v2m
  io.TLBInvalid_v2m := TLBInvalid_v2m
  io.TLBModified_v2m := TLBModified_v2m
}

object VerilogGenerator extends App {
  emitVerilog(new tlbControl(), args)
}