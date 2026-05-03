package llmverify
import chisel3._
import chisel3.util._

class IFetchControl extends Module {
  val io = IO(new Bundle {
    // Global signals
    val MemStall_s1 = Input(Bool())
    val Stall_s1 = Input(Bool())
    val Reset_s1 = Input(Bool())
    val MipsMode_s2e = Input(Bool())
    val MipsMode_b_s2e = Output(Bool())
    val ItlbMiss_v2e = Input(Bool())
    val ExtDataValid_s2 = Input(Bool())
    val L2Miss_s2 = Input(Bool())
    val IStall_s1 = Output(Bool())
    val IFetchStall_s1 = Output(Bool())
    val AKill_s1e = Output(Bool())
    val BKill_s1e = Output(Bool())
    val KillOne_s1e = Output(Bool())
    val Gamma1_s1 = Output(Bool())
    
    // Refill section
    val LatchShiftReg_s1 = Output(Bool())
    val WriteData_s2 = Output(Bool())
    val WriteTag_s2 = Output(Bool())
    val DataEnable_s2 = Output(Bool())
    val TagEnable_s2 = Output(Bool())
    val LatchDataReg_s1 = Output(Bool())
    val ExtMuxSelect_s1 = Output(Bool())
    val WritePack_s2 = Output(UInt(2.W))
    
    // Instruction fetch
    val ADynamicBit_v1r = Input(Bool())
    val BDynamicBit_v1r = Input(Bool())
    val ADrvB_s2r = Output(Bool())
    val BDrvA_s2r = Output(Bool())
    val LatchInstrs_s1r = Output(Bool())
    val LatchTags_s1r = Output(Bool())
    
    // Comparator
    val PCOffset_s2i = Input(Bool())
    val Match_v2r = Input(Bool())
    val ICacheLineValid_s2r = Input(Bool())
    val ICacheMiss_v2r = Output(Bool())
    
    // Non-cacheable
    val NonCacheable_s1 = Input(Bool())
    val LatchNonCachePkt_s2 = Output(Bool())
    val NonCacheableHeld_s1 = Output(Bool())
    
    // PC unit
    val Except_s1w = Input(Bool())
    val BEQnext_s1e = Input(Bool())
    val BNEnext_s1e = Input(Bool())
    val BLEZnext_s1e = Input(Bool())
    val BGTZnext_s1e = Input(Bool())
    val BLTZnext_s1e = Input(Bool())
    val BGEZnext_s1e = Input(Bool())
    val ImmPC_s1e = Input(Bool())
    val RegPC_s1e = Input(Bool())
    val SEqualsT_v1e = Input(Bool())
    val SIsNegative_v1e = Input(Bool())
    val TakenBranch_s2e = Output(Bool())
    val EPCSel_s1m = Input(Bool())
    val EPCNSel_s1m = Input(Bool())
    val SystemBit_s2e = Input(Bool())
    val PC_bit31_s2i = Input(Bool())
    val PCPacketNum_s2i = Input(UInt(2.W))
    val IAddrError_v2i = Output(Bool())
    val RestoreIStallPC_s1 = Output(Bool())
    val PCUnitPsi2_s2 = Output(Bool())
    val latchEPC_s1w = Output(Bool())
    val HoldPC_s2r = Output(Bool())
    val HoldPC_s2e = Output(Bool())
    val EPCBufEnable_s2m = Output(Bool())
    val EPCMuxSel_s2m = Output(Bool())
    val Jump0_s1e = Output(Bool())
    val Jump1_s1e = Output(Bool())
    val Jump2_s1e = Output(Bool())
    val Jump3_s1e = Output(Bool())
    val Jump4_s1e = Output(Bool())
    val Jump5_v1e = Output(Bool())
  })
  
  // State definitions for FSM
  val CACHEHIT = "b0010".U(4.W)
  val WAITExtByte = "b0011".U(4.W)
  val EXTBYTE = "b0111".U(4.W)
  val WAITXfer0 = "b0000".U(4.W)
  val XFER0 = "b0001".U(4.W)
  val WAITXfer1 = "b0100".U(4.W)
  val XFER1 = "b0101".U(4.W)
  val WAITXfer2 = "b1000".U(4.W)
  val XFER2 = "b1001".U(4.W)
  val WAITXfer3 = "b1100".U(4.W)
  val XFER3 = "b1101".U(4.W)
  val REFETCH = "b1111".U(4.W)
  
  // Phase tracking for two-phase clocking simulation
  val phaseReg = RegInit(false.B) // false = Phi1 phase, true = Phi2 phase
  phaseReg := ~phaseReg
  
  val Phi2 = phaseReg
  val Phi1 = ~phaseReg
  
  // Branch Condition - Define this early since it's used later
  val takenBranch_v1e = Wire(Bool())
  takenBranch_v1e := (io.BEQnext_s1e && io.SEqualsT_v1e) ||
    (io.BNEnext_s1e && ~io.SEqualsT_v1e) ||
    (io.BLEZnext_s1e && (io.SEqualsT_v1e || io.SIsNegative_v1e)) ||
    (io.BGTZnext_s1e && ~io.SEqualsT_v1e && ~io.SIsNegative_v1e) ||
    (io.BLTZnext_s1e && io.SIsNegative_v1e) ||
    (io.BGEZnext_s1e && (io.SEqualsT_v1e || ~io.SIsNegative_v1e))
  
  // Registers
  val IFetchStall_s1Reg = RegInit(false.B)
  val IFetchStall_s2Reg = RegInit(false.B)
  val SecondIssue_s1eReg = RegInit(false.B)
  val SecondIssue_s2eReg = RegInit(false.B)
  val SecondIssueCond_s1eReg = RegInit(false.B)
  val Reset_s2Reg = RegInit(false.B)
  val MipsMode_s1Reg = RegInit(false.B)
  val IStall_s1Reg = RegInit(false.B)
  val ItlbMiss_s1Reg = RegInit(false.B)
  val ItlbMiss_s2Reg = RegInit(false.B)
  val AKill_s1eReg = RegInit(false.B)
  val BKill_s1eReg = RegInit(false.B)
  val LatchShiftReg_s1Reg = RegInit(false.B)
  val SecondIssueMips_s1eReg = RegInit(false.B)
  val LatchDataReg_s1Reg = RegInit(false.B)
  val ExtMuxSelect_s1Reg = RegInit(false.B)
  val WriteCache_s2Reg = RegInit(false.B)
  val PresState_s1Reg = RegInit(CACHEHIT)
  val PrevState_s2Reg = RegInit(CACHEHIT)
  val ReadCache_s2iReg = RegInit(false.B)
  val ReadCache_s1rReg = RegInit(false.B)
  val ReadCache_s2rReg = RegInit(false.B)
  val ADrvB_s2rReg = RegInit(false.B)
  val BDrvA_s2rReg = RegInit(false.B)
  val ADrvB_s1eReg = RegInit(false.B)
  val BDrvA_s1eReg = RegInit(false.B)
  val ADynamicBit_s2rReg = RegInit(false.B)
  val BDynamicBit_s2rReg = RegInit(false.B)
  val ADynamicBit_s1eReg = RegInit(false.B)
  val BDynamicBit_s1eReg = RegInit(false.B)
  val Hold_s1eReg = RegInit(false.B)
  val Hold_s2eReg = RegInit(false.B)
  val PCOffset_s1rReg = RegInit(false.B)
  val PCOffset_s2rReg = RegInit(false.B)
  val LatchNonCachePkt_s2Reg = RegInit(false.B)
  val NonCacheable_s2Reg = RegInit(false.B)
  val NonCacheableHeld_s1Reg = RegInit(false.B)
  val TakenBranch_s2eReg = RegInit(false.B)
  val PCPacketNum_s1rReg = RegInit(0.U(2.W))
  val IStall_s2Reg = RegInit(false.B)
  val Except_s2wReg = RegInit(false.B)
  val IncPC_s2eReg = RegInit(false.B)
  val EPCSel_s2mReg = RegInit(false.B)
  val EPCNSel_s2mReg = RegInit(false.B)
  
  // Global Controls
  val Gamma1_s1 = ~io.MemStall_s1 | IStall_s1Reg
  
  // Stage Reset signal - latched on Phi1
  when(Phi1) {
    Reset_s2Reg := io.Reset_s1
  }
  
  // MipsMode latched on Phi2
  when(Phi2) {
    MipsMode_s1Reg := io.MipsMode_s2e
  }
  
  // Inversion
  io.MipsMode_b_s2e := ~io.MipsMode_s2e
  
  // FSM for IStall
  val IStall_v2 = ~Reset_s2Reg && (io.ICacheMiss_v2r || 
    (IStall_s2Reg && (PrevState_s2Reg =/= CACHEHIT)))
  
  val IFetchStall_v2 = ((io.ICacheMiss_v2r && (PrevState_s2Reg === CACHEHIT)) ||
    (IFetchStall_s2Reg && (PrevState_s2Reg =/= REFETCH))) && ~Reset_s2Reg
  
  when(Phi2) {
    IStall_s1Reg := IStall_v2
    IFetchStall_s1Reg := IFetchStall_v2
  }
  
  when(Phi1) {
    IStall_s2Reg := IStall_s1Reg
    IFetchStall_s2Reg := IFetchStall_s1Reg
  }
  
  // TLB Signals
  when(Phi2) {
    ItlbMiss_s1Reg := (io.ItlbMiss_v2e || ItlbMiss_s2Reg) && ~Except_s2wReg && ~Reset_s2Reg
  }
  
  when(Phi1) {
    ItlbMiss_s2Reg := ItlbMiss_s1Reg
  }
  
  // Second Issue
  val SecondIssueCond_s2r = ~PCOffset_s2rReg && IncPC_s2eReg && ~SecondIssue_s2eReg &&
    ~IFetchStall_s2Reg && ~Except_s2wReg
  
  val SecondIssue_s2r = (ADynamicBit_s2rReg || BDynamicBit_s2rReg) && SecondIssueCond_s2r
  
  // Control Signals To the Outside World
  val AKill_v2r = ~io.MipsMode_s2e && ~IFetchStall_s2Reg &&
    ((ADynamicBit_s2rReg && ~PCOffset_s2rReg && ~SecondIssue_s2eReg) ||
     (PCOffset_s2rReg && ~ADynamicBit_s2rReg && ~BDynamicBit_s2rReg)) ||
    Except_s2wReg || Reset_s2Reg
  
  val BKill_v2r = ~io.MipsMode_s2e && ~IFetchStall_s2Reg &&
    (BDynamicBit_s2rReg && ~PCOffset_s2rReg && ~SecondIssue_s2eReg) ||
    Except_s2wReg || Reset_s2Reg
  
  io.KillOne_s1e := ADrvB_s1eReg || BDrvA_s1eReg
  
  when(Phi2) {
    AKill_s1eReg := AKill_v2r
    BKill_s1eReg := BKill_v2r
  }
  
  // Refill Section
  when(Phi2) {
    ExtMuxSelect_s1Reg := (PresState_s1Reg === EXTBYTE)
    LatchShiftReg_s1Reg := (PresState_s1Reg === EXTBYTE) ||
      (PresState_s1Reg === XFER1) || (PresState_s1Reg === XFER2) || (PresState_s1Reg === XFER3)
    LatchDataReg_s1Reg := (PresState_s1Reg === XFER0) || (PresState_s1Reg === XFER1) ||
      (PresState_s1Reg === XFER2) || (PresState_s1Reg === XFER3)
  }
  
  // State Machine for Refill - ensure complete initialization
  val PresState_v2 = Wire(UInt(4.W))
  PresState_v2 := CACHEHIT // Default assignment
  
  // State transition logic
  when(Reset_s2Reg) {
    PresState_v2 := CACHEHIT
  }.elsewhen(ItlbMiss_s2Reg && (PrevState_s2Reg =/= REFETCH) && (PrevState_s2Reg =/= CACHEHIT)) {
    PresState_v2 := REFETCH
  }.elsewhen(io.L2Miss_s2) {
    PresState_v2 := WAITExtByte
  }.otherwise {
    switch(PrevState_s2Reg) {
      is(CACHEHIT) {
        PresState_v2 := Mux(~io.ICacheMiss_v2r, CACHEHIT,
          Mux(~io.MipsMode_s2e, WAITExtByte, WAITXfer0))
      }
      is(WAITExtByte) {
        PresState_v2 := Mux(io.ExtDataValid_s2, EXTBYTE, WAITExtByte)
      }
      is(EXTBYTE) {
        PresState_v2 := Mux(io.ExtDataValid_s2, XFER0, WAITXfer0)
      }
      is(WAITXfer0) {
        PresState_v2 := Mux(io.ExtDataValid_s2, XFER0, WAITXfer0)
      }
      is(XFER0) {
        PresState_v2 := Mux(io.ExtDataValid_s2, XFER1, WAITXfer1)
      }
      is(WAITXfer1) {
        PresState_v2 := Mux(io.ExtDataValid_s2, XFER1, WAITXfer1)
      }
      is(XFER1) {
        PresState_v2 := Mux(io.ExtDataValid_s2, XFER2, WAITXfer2)
      }
      is(WAITXfer2) {
        PresState_v2 := Mux(io.ExtDataValid_s2, XFER2, WAITXfer2)
      }
      is(XFER2) {
        PresState_v2 := Mux(io.ExtDataValid_s2, XFER3, WAITXfer3)
      }
      is(WAITXfer3) {
        PresState_v2 := Mux(io.ExtDataValid_s2, XFER3, WAITXfer3)
      }
      is(XFER3) {
        PresState_v2 := REFETCH
      }
      is(REFETCH) {
        PresState_v2 := CACHEHIT
      }
    }
  }
  
  // Output control signals
  val ExtMuxSelect_v2 = (PresState_v2 === EXTBYTE)
  val LatchShiftReg_v2 = (PresState_v2 === EXTBYTE) ||
    (PresState_v2 === XFER1) || (PresState_v2 === XFER2) || (PresState_v2 === XFER3)
  val LatchDataReg_v2 = (PresState_v2 === XFER0) || (PresState_v2 === XFER1) ||
    (PresState_v2 === XFER2) || (PresState_v2 === XFER3)
  
  val LatchNonCachePkt_v1 = LatchDataReg_s1Reg &&
    (PCPacketNum_s1rReg === PresState_s1Reg(3,2))
  
  val WriteCache_v1 = ((PresState_s1Reg === XFER0) || (PresState_s1Reg === XFER1) ||
    (PresState_s1Reg === XFER2) || (PresState_s1Reg === XFER3)) && ~NonCacheableHeld_s1Reg
  
  val ReadCache_v1i = ((PresState_s1Reg === CACHEHIT) || (PresState_s1Reg === REFETCH)) && ~NonCacheableHeld_s1Reg
  
  when(Phi1 && ~io.MemStall_s1) {
    PrevState_s2Reg := PresState_s1Reg
  }
  
  when(Phi2) {
    PresState_s1Reg := PresState_v2
  }
  
  // ICache Instruction Fetch
  val ADrvB_v1r = MipsMode_s1Reg && ~PCOffset_s1rReg ||
    ~MipsMode_s1Reg && ((ADynamicBit_s1eReg && ~BDynamicBit_s1eReg && SecondIssueCond_s1eReg) ||
      (io.ADynamicBit_v1r && ~io.BDynamicBit_v1r && PCOffset_s1rReg))
  
  val BDrvA_v1r = MipsMode_s1Reg && PCOffset_s1rReg ||
    ~MipsMode_s1Reg && ((BDynamicBit_s1eReg && ~ADynamicBit_s1eReg && SecondIssueCond_s1eReg) ||
      (io.BDynamicBit_v1r && ~io.ADynamicBit_v1r && PCOffset_s1rReg))
  
  when(Phi1 && ~io.MemStall_s1) {
    ADrvB_s2rReg := ADrvB_v1r
    BDrvA_s2rReg := BDrvA_v1r
    ADynamicBit_s2rReg := io.ADynamicBit_v1r
    BDynamicBit_s2rReg := io.BDynamicBit_v1r
  }
  
  when(Phi1 && ~io.MemStall_s1) {
    Hold_s2eReg := Hold_s1eReg
    SecondIssue_s2eReg := SecondIssue_s1eReg
  }
  
  when(Phi2) {
    ADrvB_s1eReg := ADrvB_s2rReg
    BDrvA_s1eReg := BDrvA_s2rReg
    SecondIssue_s1eReg := SecondIssue_s2r
    SecondIssueCond_s1eReg := SecondIssueCond_s2r
    ADynamicBit_s1eReg := ADynamicBit_s2rReg
    BDynamicBit_s1eReg := BDynamicBit_s2rReg
    Hold_s1eReg := SecondIssue_s2r
  }
  
  when(Phi2) {
    PCPacketNum_s1rReg := io.PCPacketNum_s2i
  }
  
  // ICACHE Control
  when(Phi1) {
    ReadCache_s2iReg := ReadCache_v1i
    ReadCache_s2rReg := ReadCache_s1rReg
  }
  
  when(Phi1) {
    WriteCache_s2Reg := WriteCache_v1
  }
  
  val SecondIssueMips_s2r = io.PCOffset_s2i && io.MipsMode_s2e && (PrevState_s2Reg === CACHEHIT)
  
  when(Phi2) {
    ReadCache_s1rReg := ReadCache_s2iReg
    SecondIssueMips_s1eReg := SecondIssueMips_s2r && IncPC_s2eReg
  }
  
  // Comparator Section
  when(Phi1 && ~io.MemStall_s1) {
    PCOffset_s2rReg := PCOffset_s1rReg
  }
  
  when(Phi2) {
    PCOffset_s1rReg := io.PCOffset_s2i
  }
  
  io.ICacheMiss_v2r := ~(io.ICacheLineValid_s2r && io.Match_v2r) &&
    ~Hold_s2eReg && ~NonCacheable_s2Reg && ~ItlbMiss_s2Reg &&
    ~Except_s2wReg && (PrevState_s2Reg =/= REFETCH)
  
  // Non-Cacheable Section
  when(Phi1) {
    LatchNonCachePkt_s2Reg := LatchNonCachePkt_v1
  }
  
  when(Phi2) {
    NonCacheableHeld_s1Reg := NonCacheable_s2Reg
  }
  
  when(Phi1) {
    NonCacheable_s2Reg := io.NonCacheable_s1 ||
      (NonCacheableHeld_s1Reg && IStall_s1Reg && ~io.Reset_s1)
  }
  
  // PC Unit Control Signals
  when(Phi1 && ~io.Stall_s1) {
    Except_s2wReg := io.Except_s1w
  }
  
  when(Phi1 && ~io.Stall_s1) {
    IncPC_s2eReg := ~io.ImmPC_s1e && ~io.RegPC_s1e && ~takenBranch_v1e
  }
  
  // PC select signals
  io.Jump0_s1e := ~io.Except_s1w && ~IStall_s1Reg && ~io.ImmPC_s1e && ~io.RegPC_s1e
  io.Jump1_s1e := ~io.Except_s1w && ~IStall_s1Reg && io.ImmPC_s1e
  io.Jump2_s1e := ~io.Except_s1w && ~IStall_s1Reg && io.RegPC_s1e
  io.Jump3_s1e := io.Except_s1w && ~IStall_s1Reg
  io.Jump4_s1e := IStall_s1Reg
  io.Jump5_v1e := (~io.Except_s1w && ~IStall_s1Reg) && takenBranch_v1e
  
  when(Phi1 && ~io.Stall_s1) {
    TakenBranch_s2eReg := takenBranch_v1e
  }
  
  // Address Violations
  io.IAddrError_v2i := io.PC_bit31_s2i && io.SystemBit_s2e
  
  // PC Chain
  when(Phi1) {
    EPCSel_s2mReg := io.EPCSel_s1m
    EPCNSel_s2mReg := io.EPCNSel_s1m
  }
  
  // Assign outputs
  io.Gamma1_s1 := Gamma1_s1
  io.IFetchStall_s1 := IFetchStall_s1Reg
  io.IStall_s1 := IStall_s1Reg
  io.AKill_s1e := AKill_s1eReg
  io.BKill_s1e := BKill_s1eReg
  io.LatchShiftReg_s1 := LatchShiftReg_s1Reg
  io.WriteData_s2 := (WriteCache_s2Reg && ~io.L2Miss_s2)
  io.WriteTag_s2 := (PrevState_s2Reg === XFER3)
  io.DataEnable_s2 := io.WriteData_s2 || (ReadCache_s2iReg && (~SecondIssue_s2eReg && ~SecondIssueMips_s1eReg || ~IncPC_s2eReg))
  io.TagEnable_s2 := io.WriteTag_s2 || (ReadCache_s2iReg && (~SecondIssue_s2eReg && ~SecondIssueMips_s1eReg || ~IncPC_s2eReg))
  io.LatchDataReg_s1 := LatchDataReg_s1Reg
  io.ExtMuxSelect_s1 := ExtMuxSelect_s1Reg
  io.WritePack_s2 := Cat((PrevState_s2Reg === XFER2) || (PrevState_s2Reg === XFER3),
    (PrevState_s2Reg === XFER1) || (PrevState_s2Reg === XFER3))
  io.ADrvB_s2r := ADrvB_s2rReg
  io.BDrvA_s2r := BDrvA_s2rReg
  io.LatchInstrs_s1r := (~Hold_s1eReg && ~SecondIssueMips_s1eReg) && ~IFetchStall_s1Reg && ~io.MemStall_s1
  io.LatchTags_s1r := ~io.MemStall_s1 && ReadCache_s1rReg
  io.LatchNonCachePkt_s2 := LatchNonCachePkt_s2Reg
  io.NonCacheableHeld_s1 := NonCacheableHeld_s1Reg
  io.TakenBranch_s2e := TakenBranch_s2eReg
  io.RestoreIStallPC_s1 := IStall_s1Reg && ~IFetchStall_s1Reg
  io.PCUnitPsi2_s2 := Phi2 && ~IStall_s2Reg
  io.latchEPC_s1w := Phi1 && io.Except_s1w
  io.HoldPC_s2r := SecondIssue_s2r
  io.HoldPC_s2e := Hold_s2eReg
  io.EPCBufEnable_s2m := Phi2 && (EPCSel_s2mReg || EPCNSel_s2mReg)
  io.EPCMuxSel_s2m := EPCSel_s2mReg
}

object VerilogGenerator extends App {
  emitVerilog(new IFetchControl(), args)
}