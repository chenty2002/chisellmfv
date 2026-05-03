package llmverify

import chisel3._
import chisel3.util._

// Constants that would normally be in torch.h
object TorchConstants {
  val VALID_BIT = 6
  val SPEC_BIT = 5
  val HARD_DEST_BIT = 4
  val BOOST_BIT = 3
  val BOOST_VALID_BIT = 2
  val LOAD_BIT = 1
  val VALID = 1.U(1.W)
  val DONT_CARE = 0.U(1.W)
  val NOT_VALID = 0.U(1.W)
  val BYPASS_BMEM_BIT = 4
  val BYPASS_AMEM_BIT = 3
  val BYPASS_BEX_BIT = 2
  val BYPASS_AEX_BIT = 1
  val NO_BYPASS_BIT = 0
}

// Helper modules
class COMP_7 extends Module {
  val io = IO(new Bundle {
    val match_out = Output(Bool())
    val in1 = Input(UInt(7.W))
    val in2 = Input(UInt(7.W))
  })
  io.match_out := (io.in1 === io.in2)
}

class COMP_5 extends Module {
  val io = IO(new Bundle {
    val match_out = Output(Bool())
    val in1 = Input(UInt(5.W))
    val in2 = Input(UInt(5.W))
  })
  io.match_out := (io.in1 === io.in2)
}

class PRIORITY_5 extends Module {
  val io = IO(new Bundle {
    val out = Output(UInt(5.W))
    val in = Input(UInt(5.W))
  })
  io.out := MuxCase(0.U, Seq(
    io.in(4) -> "b10000".U,
    io.in(3) -> "b01000".U,
    io.in(2) -> "b00100".U,
    io.in(1) -> "b00010".U,
    io.in(0) -> "b00001".U
  ))
}

class MUX2_8 extends Module {
  val io = IO(new Bundle {
    val out = Output(UInt(8.W))
    val in1 = Input(UInt(8.W))
    val in2 = Input(UInt(8.W))
    val sel = Input(Bool())
  })
  io.out := Mux(io.sel, io.in1, io.in2)
}

class MUX2_7 extends Module {
  val io = IO(new Bundle {
    val out = Output(UInt(7.W))
    val in1 = Input(UInt(7.W))
    val in2 = Input(UInt(7.W))
    val sel = Input(Bool())
  })
  io.out := Mux(io.sel, io.in1, io.in2)
}

class MUX3_10 extends Module {
  val io = IO(new Bundle {
    val out = Output(UInt(10.W))
    val in1 = Input(UInt(10.W))
    val in2 = Input(UInt(10.W))
    val in3 = Input(UInt(10.W))
    val sel1 = Input(Bool())
    val sel2 = Input(Bool())
    val sel3 = Input(Bool())
  })
  io.out := MuxCase(0.U, Seq(
    io.sel1 -> io.in1,
    io.sel2 -> io.in2,
    io.sel3 -> io.in3
  ))
}

class MUX4_9 extends Module {
  val io = IO(new Bundle {
    val out = Output(UInt(9.W))
    val in1 = Input(UInt(9.W))
    val in2 = Input(UInt(9.W))
    val in3 = Input(UInt(9.W))
    val in4 = Input(UInt(9.W))
    val sel1 = Input(Bool())
    val sel2 = Input(Bool())
    val sel3 = Input(Bool())
    val sel4 = Input(Bool())
  })
  io.out := MuxCase(0.U, Seq(
    io.sel1 -> io.in1,
    io.sel2 -> io.in2,
    io.sel3 -> io.in3,
    io.sel4 -> io.in4
  ))
}

class compares extends Module {
  val io = IO(new Bundle {
    val SrcSpec_s2r = Input(UInt(7.W))
    val ADest_s2e = Input(UInt(7.W))
    val ADest_s2m = Input(UInt(7.W))
    val BDest_s2e = Input(UInt(7.W))
    val BDest_s2m = Input(UInt(7.W))
    val BypassSel_v2r = Output(UInt(5.W))
  })
  
  val compareBm = Module(new COMP_7())
  compareBm.io.in1 := io.SrcSpec_s2r
  compareBm.io.in2 := io.BDest_s2m
  
  val compareAm = Module(new COMP_7())
  compareAm.io.in1 := io.SrcSpec_s2r
  compareAm.io.in2 := io.ADest_s2m
  
  val compareBe = Module(new COMP_7())
  compareBe.io.in1 := io.SrcSpec_s2r
  compareBe.io.in2 := io.BDest_s2e
  
  val compareAe = Module(new COMP_7())
  compareAe.io.in1 := io.SrcSpec_s2r
  compareAe.io.in2 := io.ADest_s2e
  
  // Construct the 5-bit compare result vector
  // Bit 4: BYPASS_BMEM_BIT, Bit 3: BYPASS_AMEM_BIT, Bit 2: BYPASS_BEX_BIT
  // Bit 1: BYPASS_AEX_BIT, Bit 0: NO_BYPASS_BIT
  val compareRes_v2r = Cat(
    compareBm.io.match_out,
    compareAm.io.match_out,
    compareBe.io.match_out,
    compareAe.io.match_out,
    true.B  // NO_BYPASS_BIT is always set
  )
  
  val priority = Module(new PRIORITY_5())
  priority.io.in := compareRes_v2r
  io.BypassSel_v2r := priority.io.out
}

class BBypassSpec extends Module {
  val io = IO(new Bundle {
    // Clocks & Stalls
    val Phi1 = Input(Bool())
    val Stall_s1 = Input(Bool())
    val Stall_s2 = Input(Bool())
    val IStall_s1 = Input(Bool())
    val MemStall_s1 = Input(Bool())
    
    // Register specifiers (from instruction)
    val ARTSpec_s2r = Input(UInt(6.W))
    val ARDSpec_s2r = Input(UInt(6.W))
    val BRSSpec_s2r = Input(UInt(6.W))
    val BRTSpec_s2r = Input(UInt(6.W))
    val BRDSpec_s2r = Input(UInt(6.W))
    
    // Destination register specifier selector
    val ADestIsRD_s1e = Input(Bool())
    val ADestIsRT_s1e = Input(Bool())
    val ADestIs31_s1e = Input(Bool())
    val BDestIsRD_s1e = Input(Bool())
    val BDestIsRT_s1e = Input(Bool())
    val BIsLoad_s1e = Input(Bool())
    
    // Boosting information
    val ADestPtr_v1e = Input(Bool())
    val ADestBoostValid_v1e = Input(Bool())
    val BDestPtr_v1e = Input(Bool())
    val BDestBoostValid_v1e = Input(Bool())
    
    // Instruction WB cancel
    val AKill_s1e = Input(Bool())
    val AIgnore_s2e = Input(Bool())
    val BKill_s1e = Input(Bool())
    val BIgnore_s2e = Input(Bool())
    
    // Branches & Exceptions
    val Commit_s1e = Input(Bool())
    val Squash_s1e = Input(Bool())
    val Except_s1w = Input(Bool())
    
    // Outputs
    val Alpha2_s2e = Output(Bool())
    val Alpha1_s1m = Output(Bool())
    val Alpha2_s2m = Output(Bool())
    val Beta2_s2e = Output(Bool())
    val Beta1_s1m = Output(Bool())
    val Beta2_s2m = Output(Bool())
    val Delta2_q2 = Output(Bool())
    val BSDSpec_w = Output(UInt(8.W))
    val BTRSpec_w = Output(UInt(7.W))
    val BSBypassLoad_s1e = Output(Bool())
    val BSBypassLoad_b_s1e = Output(Bool())
    val BTBypassLoad_s1e = Output(Bool())
    val BTBypassLoad_b_s1e = Output(Bool())
    val BSBypassSel_s1e = Output(UInt(5.W))
    val BTBypassSel_s1e = Output(UInt(5.W))
    val BWasLoad_s1w = Output(Bool())
    val BWasLoad_b_s1w = Output(Bool())
    
    // Additional outputs to preserve internal signals
    val ARTSpec_s1e_debug = Output(UInt(6.W))
    val ARDSpec_s1e_debug = Output(UInt(6.W))
    val BRTSpec_s1e_debug = Output(UInt(6.W))
    val BRDSpec_s1e_debug = Output(UInt(6.W))
    val ADest_s1e_debug = Output(UInt(9.W))
    val BDest_s1e_debug = Output(UInt(10.W))
  })
  
  val Phi2 = ~io.Phi1
  
  // Register specifiers chain
  val ARTSpec_s1e = RegInit(0.U(6.W))
  val ARDSpec_s1e = RegInit(0.U(6.W))
  val BRTSpec_s1e = RegInit(0.U(6.W))
  val BRDSpec_s1e = RegInit(0.U(6.W))
  
  // Destination register specifier chains
  val ADest_s1e = Wire(UInt(9.W))
  val ADest_s2e = RegInit(0.U(9.W))
  val ADest_s1m = RegInit(0.U(9.W))
  val ADest_s2m = RegInit(0.U(9.W))
  
  val BDest_s1e = Wire(UInt(10.W))
  val BDest_s2e = RegInit(0.U(10.W))
  val BDest_s1m = RegInit(0.U(10.W))
  val BDest_s2m = RegInit(0.U(10.W))
  val BDest_s1w = RegInit(0.U(10.W))
  
  // Delayed version of Stall
  val IStall_s2 = RegInit(false.B)
  val MemStall_s2 = RegInit(false.B)
  
  // Bypass selector registers
  val BSBypassSel_s1e = RegInit(0.U(5.W))
  val BTBypassSel_s1e = RegInit(0.U(5.W))
  
  // Control Logic
  val ANoDest_s1e = !(io.ADestIsRD_s1e | io.ADestIsRT_s1e | io.ADestIs31_s1e)
  val BNoDest_s1e = !(io.BDestIsRD_s1e | io.BDestIsRT_s1e)
  
  val ADestValid_s1e = !(io.AKill_s1e | ANoDest_s1e)
  val BDestValid_s1e = !(io.BKill_s1e | BNoDest_s1e)
  
  val BWasLoad_s1w = BDest_s1w(TorchConstants.LOAD_BIT)
  val BWasLoad_b_s1w = !BWasLoad_s1w
  
  io.BSBypassLoad_s1e := BSBypassSel_s1e(TorchConstants.BYPASS_BMEM_BIT) & BWasLoad_s1w
  io.BTBypassLoad_s1e := BTBypassSel_s1e(TorchConstants.BYPASS_BMEM_BIT) & BWasLoad_s1w
  io.BSBypassLoad_b_s1e := !io.BSBypassLoad_s1e
  io.BTBypassLoad_b_s1e := !io.BTBypassLoad_s1e
  
  // Bypass Compares
  val BRSCompare = Module(new compares())
  BRSCompare.io.SrcSpec_s2r := Cat(TorchConstants.VALID, io.BRSSpec_s2r)
  BRSCompare.io.ADest_s2e := ADest_s2e(TorchConstants.VALID_BIT, 0)
  BRSCompare.io.ADest_s2m := ADest_s2m(TorchConstants.VALID_BIT, 0)
  BRSCompare.io.BDest_s2e := BDest_s2e(TorchConstants.VALID_BIT, 0)
  BRSCompare.io.BDest_s2m := BDest_s2m(TorchConstants.VALID_BIT, 0)
  val BSBypassSel_v2r = BRSCompare.io.BypassSel_v2r
  
  val BRTCompare = Module(new compares())
  BRTCompare.io.SrcSpec_s2r := Cat(TorchConstants.VALID, io.BRTSpec_s2r)
  BRTCompare.io.ADest_s2e := ADest_s2e(TorchConstants.VALID_BIT, 0)
  BRTCompare.io.ADest_s2m := ADest_s2m(TorchConstants.VALID_BIT, 0)
  BRTCompare.io.BDest_s2e := BDest_s2e(TorchConstants.VALID_BIT, 0)
  BRTCompare.io.BDest_s2m := BDest_s2m(TorchConstants.VALID_BIT, 0)
  val BTBypassSel_v2r = BRTCompare.io.BypassSel_v2r
  
  // Register File Signals
  val BSDSpec_W = Module(new MUX2_8())
  BSDSpec_W.io.in1 := BDest_s1w(7, 0)
  BSDSpec_W.io.in2 := Cat(TorchConstants.DONT_CARE, TorchConstants.NOT_VALID, io.BRSSpec_s2r)
  BSDSpec_W.io.sel := io.Phi1
  io.BSDSpec_w := BSDSpec_W.io.out
  
  val BTRSpec_W = Module(new MUX2_7())
  BTRSpec_W.io.in1 := Cat(BDestValid_s1e, BDest_s1e(5, 0))
  BTRSpec_W.io.in2 := Cat(TorchConstants.NOT_VALID, io.BRTSpec_s2r)
  BTRSpec_W.io.sel := io.Phi1
  io.BTRSpec_w := BTRSpec_W.io.out
  
  // Destination Chain - A-side
  val ADest_V2R = Module(new MUX4_9())
  ADest_V2R.io.in1 := 0.U
  ADest_V2R.io.in2 := Cat(TorchConstants.DONT_CARE, TorchConstants.DONT_CARE, TorchConstants.VALID, 0.U, 31.U)
  ADest_V2R.io.in3 := Cat(TorchConstants.DONT_CARE, TorchConstants.DONT_CARE, TorchConstants.VALID, ARTSpec_s1e)
  ADest_V2R.io.in4 := Cat(TorchConstants.DONT_CARE, TorchConstants.DONT_CARE, TorchConstants.VALID, ARDSpec_s1e)
  ADest_V2R.io.sel1 := ANoDest_s1e
  ADest_V2R.io.sel2 := io.ADestIs31_s1e
  ADest_V2R.io.sel3 := io.ADestIsRT_s1e
  ADest_V2R.io.sel4 := io.ADestIsRD_s1e
  ADest_s1e := ADest_V2R.io.out
  
  val ADestIsZero_v1e = (ADest_s1e(TorchConstants.SPEC_BIT, 0) === 0.U)
  
  // Destination Chain - B-side
  val BDest_V2R = Module(new MUX3_10())
  BDest_V2R.io.in1 := 0.U
  BDest_V2R.io.in2 := Cat(TorchConstants.DONT_CARE, TorchConstants.DONT_CARE, TorchConstants.DONT_CARE, TorchConstants.VALID, BRTSpec_s1e)
  BDest_V2R.io.in3 := Cat(TorchConstants.DONT_CARE, TorchConstants.DONT_CARE, TorchConstants.DONT_CARE, TorchConstants.VALID, BRDSpec_s1e)
  BDest_V2R.io.sel1 := BNoDest_s1e
  BDest_V2R.io.sel2 := io.BDestIsRT_s1e
  BDest_V2R.io.sel3 := io.BDestIsRD_s1e
  BDest_s1e := BDest_V2R.io.out
  
  val BDestIsZero_v1e = (BDest_s1e(TorchConstants.SPEC_BIT, 0) === 0.U)
  
  // Random Logic - ADest_v1e (construct complete value in one go)
  val ADest_v1e = Cat(
    ADest_s1e(8, 7),  // DONT_CARE bits
    io.ADestPtr_v1e,
    (ADest_s1e(TorchConstants.BOOST_BIT) & !io.Commit_s1e),
    (io.ADestBoostValid_v1e & !(io.Commit_s1e | io.Squash_s1e)),
    0.U,  // LOAD_BIT (not used for A-side)
    (ADestValid_s1e & !(ADestIsZero_v1e | io.Except_s1w)),
    ADest_s1e(4, 0)   // SPEC_BIT down to 0
  )
  
  // Random Logic - ADest_v2e
  val ADest_v2e = Cat(
    ADest_s2e(8, 7),  // Upper bits unchanged
    ADest_s2e(6, 2),  // Bits 6-2 unchanged
    (ADest_s2e(TorchConstants.VALID_BIT) & !io.AIgnore_s2e),
    ADest_s2e(1, 0)   // Lower bits unchanged
  )
  
  // Random Logic - ADest_v1m
  val ADest_v1m = Cat(
    ADest_s1m(8, 7),  // Upper bits unchanged
    ADest_s1m(6, 4),  // Bits 6-4 unchanged
    (ADest_s1m(TorchConstants.BOOST_BIT) ^ (io.Commit_s1e & ADest_s1m(TorchConstants.BOOST_BIT))),
    (ADest_s1m(TorchConstants.BOOST_VALID_BIT) & !(io.Commit_s1e | io.Squash_s1e)),
    ADest_s1m(1, 0),  // Lower bits unchanged
    (ADest_s1m(TorchConstants.VALID_BIT) & !io.Except_s1w),
    ADest_s1m(4, 0)   // SPEC_BIT down to 0
  )
  
  // Random Logic - BDest_v1e (construct complete value in one go)
  val BDest_v1e = Cat(
    BDest_s1e(9, 7),  // DONT_CARE bits
    io.BDestPtr_v1e,
    (BDest_s1e(TorchConstants.BOOST_BIT) & !io.Commit_s1e),
    (io.BDestBoostValid_v1e & !(io.Commit_s1e | io.Squash_s1e)),
    io.BIsLoad_s1e,
    (BDestValid_s1e & !(BDestIsZero_v1e | io.Except_s1w)),
    BDest_s1e(4, 0)   // SPEC_BIT down to 0
  )
  
  // Random Logic - BDest_v2e
  val BDest_v2e = Cat(
    BDest_s2e(9, 7),  // Upper bits unchanged
    BDest_s2e(6, 2),  // Bits 6-2 unchanged
    (BDest_s2e(TorchConstants.VALID_BIT) & !io.BIgnore_s2e),
    BDest_s2e(1, 0)   // Lower bits unchanged
  )
  
  // Random Logic - BDest_v1m
  val BDest_v1m = Cat(
    BDest_s1m(9, 7),  // Upper bits unchanged
    BDest_s1m(6, 4),  // Bits 6-4 unchanged
    (BDest_s1m(TorchConstants.BOOST_BIT) ^ (io.Commit_s1e & BDest_s1m(TorchConstants.BOOST_VALID_BIT))),
    (BDest_s1m(TorchConstants.BOOST_VALID_BIT) & !(io.Commit_s1e | io.Squash_s1e)),
    BDest_s1m(1, 0),  // Lower bits unchanged
    (BDest_s1m(TorchConstants.VALID_BIT) & !io.Except_s1w),
    BDest_s1m(4, 0)   // SPEC_BIT down to 0
  )
  
  // Register updates on clock edges
  when(Phi2 & !io.Stall_s2) {
    ARTSpec_s1e := io.ARTSpec_s2r
    ARDSpec_s1e := io.ARDSpec_s2r
    BRTSpec_s1e := io.BRTSpec_s2r
    BRDSpec_s1e := io.BRDSpec_s2r
    ADest_s2e := ADest_v1e
    ADest_s1m := ADest_v2e
    BDest_s2e := BDest_v1e
    BDest_s1m := BDest_v2e
    BDest_s1w := BDest_s2m
    BSBypassSel_s1e := BSBypassSel_v2r
    BTBypassSel_s1e := BTBypassSel_v2r
  }
  
  when(io.Phi1 & !io.Stall_s1) {
    ADest_s2m := ADest_v1m
    BDest_s2m := BDest_v1m
  }
  
  when(io.Phi1) {
    IStall_s2 := io.IStall_s1
    MemStall_s2 := io.MemStall_s1
  }
  
  // Output assignments
  io.Delta2_q2 := Phi2 & (!IStall_s2 & BDest_s2m(TorchConstants.LOAD_BIT))
  
  io.Alpha2_s2e := ADest_s2e(TorchConstants.VALID_BIT) & !io.Stall_s2
  io.Alpha1_s1m := ADest_s1m(TorchConstants.VALID_BIT) & !io.Stall_s1
  io.Alpha2_s2m := ADest_s2m(TorchConstants.VALID_BIT) & !io.Stall_s2
  
  io.Beta2_s2e := BDest_s2e(TorchConstants.VALID_BIT) & !io.Stall_s2
  io.Beta1_s1m := BDest_s1m(TorchConstants.VALID_BIT) & !io.Stall_s1
  io.Beta2_s2m := BDest_s2m(TorchConstants.VALID_BIT) & !io.Stall_s2
  
  io.BSBypassSel_s1e := BSBypassSel_s1e
  io.BTBypassSel_s1e := BTBypassSel_s1e
  io.BWasLoad_s1w := BWasLoad_s1w
  io.BWasLoad_b_s1w := BWasLoad_b_s1w
  
  // Debug outputs
  io.ARTSpec_s1e_debug := ARTSpec_s1e
  io.ARDSpec_s1e_debug := ARDSpec_s1e
  io.BRTSpec_s1e_debug := BRTSpec_s1e
  io.BRDSpec_s1e_debug := BRDSpec_s1e
  io.ADest_s1e_debug := ADest_s1e
  io.BDest_s1e_debug := BDest_s1e
}

object VerilogGenerator extends App {
  emitVerilog(new BBypassSpec(), args)
}