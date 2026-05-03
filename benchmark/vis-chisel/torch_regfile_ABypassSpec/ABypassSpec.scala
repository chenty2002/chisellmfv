package llmverify

import chisel3._
import chisel3.util._

// Constants based on torch.h (inferred from usage)
object ABypassSpecConstants {
  val VALID = 1.U(1.W)
  val DONT_CARE = 0.U(1.W)
  val NOT_VALID = 0.U(1.W)
  
  // Bit positions
  val VALID_BIT = 0
  val SPEC_BIT = 0
  val HARD_DEST_BIT = 6
  val BOOST_BIT = 7
  val BOOST_VALID_BIT = 8
  val LOAD_BIT = 9
  
  // Bypass selector bits
  val BYPASS_BMEM_BIT = 4
  val BYPASS_AMEM_BIT = 3
  val BYPASS_BEX_BIT = 2
  val BYPASS_AEX_BIT = 1
  val NO_BYPASS_BIT = 0
}

class ABypassSpec extends Module {
  val io = IO(new Bundle {
    // Clocks & Stalls
    val Phi1 = Input(Bool())
    val Stall_s1 = Input(Bool())
    val Stall_s2 = Input(Bool())
    val IStall_s1 = Input(Bool())
    val MemStall_s1 = Input(Bool())
    
    // Register specifiers (from instruction)
    val ARSSpec_s2r = Input(UInt(6.W))
    val ARTSpec_s2r = Input(UInt(6.W))
    val ARDSpec_s2r = Input(UInt(6.W))
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
    val ASDSpec_w = Output(UInt(8.W))
    val ATRSpec_w = Output(UInt(7.W))
    val ASBypassLoad_s1e = Output(Bool())
    val ASBypassLoad_b_s1e = Output(Bool())
    val ATBypassLoad_s1e = Output(Bool())
    val ATBypassLoad_b_s1e = Output(Bool())
    val ASBypassSel_s1e = Output(UInt(5.W))
    val ATBypassSel_s1e = Output(UInt(5.W))
  })
  
  import ABypassSpecConstants._
  
  // Internal signals
  val Phi2 = ~io.Phi1
  
  // Destination register specifier chains
  val ARTSpec_s1e = RegInit(0.U(6.W))
  val ARDSpec_s1e = RegInit(0.U(6.W))
  
  val ADest_s1e = Wire(UInt(9.W))
  val ADest_s2e = RegInit(0.U(9.W))
  val ADest_s1m = RegInit(0.U(9.W))
  val ADest_s2m = RegInit(0.U(9.W))
  val ADest_s1w = RegInit(0.U(9.W))
  
  val BRTSpec_s1e = RegInit(0.U(6.W))
  val BRDSpec_s1e = RegInit(0.U(6.W))
  
  val BDest_s1e = Wire(UInt(10.W))
  val BDest_s2e = RegInit(0.U(10.W))
  val BDest_s1m = RegInit(0.U(10.W))
  val BDest_s2m = RegInit(0.U(10.W))
  val BDest_s1w = RegInit(0.U(10.W))
  
  // Delayed version of Stall
  val IStall_s2 = RegInit(0.U(1.W))
  val MemStall_s2 = RegInit(0.U(1.W))
  
  val ASBypassSel_v2r = Wire(UInt(5.W))
  val ATBypassSel_v2r = Wire(UInt(5.W))
  
  val ASBypassSel_s1e = RegInit(0.U(5.W))
  val ATBypassSel_s1e = RegInit(0.U(5.W))
  
  // Random logic signals
  val ANoDest_s1e = Wire(Bool())
  val BNoDest_s1e = Wire(Bool())
  val ADestValid_s1e = Wire(Bool())
  val BDestValid_s1e = Wire(Bool())
  val ADestIsZero_v1e = Wire(Bool())
  val BDestIsZero_v1e = Wire(Bool())
  val BWasLoad_s1w = Wire(Bool())
  
  // Kill Chain
  val AKill_s2e = RegInit(0.U(1.W))
  val AKill_s1m = RegInit(0.U(1.W))
  val BKill_s2e = RegInit(0.U(1.W))
  val BKill_s1m = RegInit(0.U(1.W))
  
  // Control Logic
  io.Delta2_q2 := (~IStall_s2 & BDest_s2m(LOAD_BIT)) & Phi2
  
  ANoDest_s1e := ~(io.ADestIsRD_s1e | io.ADestIsRT_s1e | io.ADestIs31_s1e)
  BNoDest_s1e := ~(io.BDestIsRD_s1e | io.BDestIsRT_s1e)
  
  ADestValid_s1e := ~(io.AKill_s1e | ANoDest_s1e)
  BDestValid_s1e := ~(io.BKill_s1e | BNoDest_s1e)
  
  BWasLoad_s1w := BDest_s1w(LOAD_BIT)
  
  io.ASBypassLoad_s1e := ASBypassSel_s1e(BYPASS_BMEM_BIT) & BWasLoad_s1w & ~io.AKill_s1e
  io.ATBypassLoad_s1e := ATBypassSel_s1e(BYPASS_BMEM_BIT) & BWasLoad_s1w & ~io.AKill_s1e
  io.ASBypassLoad_b_s1e := ~(ASBypassSel_s1e(BYPASS_BMEM_BIT) & BWasLoad_s1w) & ~io.AKill_s1e
  io.ATBypassLoad_b_s1e := ~(ATBypassSel_s1e(BYPASS_BMEM_BIT) & BWasLoad_s1w) & ~io.AKill_s1e
  
  // Bypass Compares
  val arsCompare = Module(new compares)
  arsCompare.io.SrcSpec_s2r := Cat(VALID, io.ARSSpec_s2r)
  arsCompare.io.ADest_s2e := ADest_s2e(6, 0)
  arsCompare.io.ADest_s2m := ADest_s2m(6, 0)
  arsCompare.io.BDest_s2e := BDest_s2e(6, 0)
  arsCompare.io.BDest_s2m := BDest_s2m(6, 0)
  ASBypassSel_v2r := arsCompare.io.BypassSel_v2r
  
  val artCompare = Module(new compares)
  artCompare.io.SrcSpec_s2r := Cat(VALID, io.ARTSpec_s2r)
  artCompare.io.ADest_s2e := ADest_s2e(6, 0)
  artCompare.io.ADest_s2m := ADest_s2m(6, 0)
  artCompare.io.BDest_s2e := BDest_s2e(6, 0)
  artCompare.io.BDest_s2m := BDest_s2m(6, 0)
  ATBypassSel_v2r := artCompare.io.BypassSel_v2r
  
  // Latch control signals
  when(Phi2 & ~io.Stall_s2) {
    ASBypassSel_s1e := ASBypassSel_v2r
    ATBypassSel_s1e := ATBypassSel_v2r
  }
  
  // Register File Signals
  val asdSpecW = Module(new MUX2_8)
  asdSpecW.io.in1 := ADest_s1w(7, 0)
  asdSpecW.io.in2 := Cat(DONT_CARE, NOT_VALID, io.ARSSpec_s2r)
  asdSpecW.io.sel := io.Phi1
  io.ASDSpec_w := asdSpecW.io.out
  
  val atrSpecW = Module(new MUX2_7)
  atrSpecW.io.in1 := Cat(ADestValid_s1e, ADest_s1e(5, 0))
  atrSpecW.io.in2 := Cat(NOT_VALID, io.ARTSpec_s2r)
  atrSpecW.io.sel := io.Phi1
  io.ATRSpec_w := atrSpecW.io.out
  
  // A-side pipeline
  when(Phi2 & ~io.Stall_s2) {
    ARTSpec_s1e := io.ARTSpec_s2r
    ARDSpec_s1e := io.ARDSpec_s2r
  }
  
  val aDestV2R = Module(new MUX4_9)
  aDestV2R.io.in1 := 0.U(9.W)
  aDestV2R.io.in2 := Cat(DONT_CARE, DONT_CARE, VALID, 0.U(1.W), 31.U(5.W))
  aDestV2R.io.in3 := Cat(DONT_CARE, DONT_CARE, VALID, ARTSpec_s1e)
  aDestV2R.io.in4 := Cat(DONT_CARE, DONT_CARE, VALID, ARDSpec_s1e)
  aDestV2R.io.sel1 := ANoDest_s1e
  aDestV2R.io.sel2 := io.ADestIs31_s1e
  aDestV2R.io.sel3 := io.ADestIsRT_s1e
  aDestV2R.io.sel4 := io.ADestIsRD_s1e
  ADest_s1e := aDestV2R.io.out
  
  val aDestIsZero = Module(new COMP_5)
  aDestIsZero.io.in1 := ADest_s1e(4, 0)
  aDestIsZero.io.in2 := 0.U(5.W)
  ADestIsZero_v1e := aDestIsZero.io.match_out
  
  // A-side pipeline stage assignments
  val ADest_v1e_bits = Wire(UInt(9.W))
  ADest_v1e_bits := Cat(
    io.ADestBoostValid_v1e & ~(io.Commit_s1e | io.Squash_s1e),
    ADest_s1e(BOOST_BIT) & ~io.Commit_s1e,
    ADestValid_s1e & ~(ADestIsZero_v1e | io.Except_s1w),
    io.ADestPtr_v1e,
    ADest_s1e(SPEC_BIT, 0)
  )
  
  when(io.Phi1 & ~io.Stall_s1) {
    ADest_s2e := ADest_v1e_bits
  }
  
  val ADest_v2e_bits = Wire(UInt(9.W))
  ADest_v2e_bits := Cat(
    ADest_s2e(BOOST_VALID_BIT),
    ADest_s2e(HARD_DEST_BIT),
    ADest_s2e(BOOST_BIT),
    ADest_s2e(SPEC_BIT, 0),
    ADest_s2e(VALID_BIT) & ~io.AIgnore_s2e
  )
  
  when(Phi2 & ~io.Stall_s2) {
    ADest_s1m := ADest_v2e_bits
  }
  
  val ADest_v1m_bits = Wire(UInt(9.W))
  ADest_v1m_bits := Cat(
    ADest_s1m(BOOST_VALID_BIT) & ~(io.Commit_s1e | io.Squash_s1e),
    ADest_s1m(BOOST_BIT) ^ (io.Commit_s1e & ADest_s1m(BOOST_VALID_BIT)),
    ADest_s1m(VALID_BIT) & ~io.Except_s1w,
    ADest_s1m(HARD_DEST_BIT),
    ADest_s1m(SPEC_BIT, 0)
  )
  
  when(io.Phi1 & ~io.Stall_s1) {
    ADest_s2m := ADest_v1m_bits
  }
  
  when(Phi2 & ~io.Stall_s2) {
    ADest_s1w := ADest_s2m
  }
  
  // B-side pipeline
  when(Phi2 & ~io.Stall_s2) {
    BRTSpec_s1e := io.BRTSpec_s2r
    BRDSpec_s1e := io.BRDSpec_s2r
  }
  
  val bDestV2R = Module(new MUX3_10)
  bDestV2R.io.in1 := 0.U(10.W)
  bDestV2R.io.in2 := Cat(DONT_CARE, DONT_CARE, DONT_CARE, VALID, BRTSpec_s1e)
  bDestV2R.io.in3 := Cat(DONT_CARE, DONT_CARE, DONT_CARE, VALID, BRDSpec_s1e)
  bDestV2R.io.sel1 := BNoDest_s1e
  bDestV2R.io.sel2 := io.BDestIsRT_s1e
  bDestV2R.io.sel3 := io.BDestIsRD_s1e
  BDest_s1e := bDestV2R.io.out
  
  val bDestIsZero = Module(new COMP_5)
  bDestIsZero.io.in1 := BDest_s1e(4, 0)
  bDestIsZero.io.in2 := 0.U(5.W)
  BDestIsZero_v1e := bDestIsZero.io.match_out
  
  // B-side pipeline stage assignments
  val BDest_v1e_bits = Wire(UInt(10.W))
  BDest_v1e_bits := Cat(
    io.BDestBoostValid_v1e & ~(io.Commit_s1e | io.Squash_s1e),
    BDest_s1e(BOOST_BIT) & ~io.Commit_s1e,
    BDestValid_s1e & ~(BDestIsZero_v1e | io.Except_s1w),
    io.BIsLoad_s1e,
    io.BDestPtr_v1e,
    BDest_s1e(SPEC_BIT, 0)
  )
  
  when(io.Phi1 & ~io.Stall_s1) {
    BDest_s2e := BDest_v1e_bits
  }
  
  val BDest_v2e_bits = Wire(UInt(10.W))
  BDest_v2e_bits := Cat(
    BDest_s2e(BOOST_VALID_BIT),
    BDest_s2e(LOAD_BIT),
    BDest_s2e(HARD_DEST_BIT),
    BDest_s2e(BOOST_BIT),
    BDest_s2e(SPEC_BIT, 0),
    BDest_s2e(VALID_BIT) & ~io.BIgnore_s2e
  )
  
  when(Phi2 & ~io.Stall_s2) {
    BDest_s1m := BDest_v2e_bits
  }
  
  val BDest_v1m_bits = Wire(UInt(10.W))
  BDest_v1m_bits := Cat(
    BDest_s1m(BOOST_VALID_BIT) & ~(io.Commit_s1e | io.Squash_s1e),
    BDest_s1m(BOOST_BIT) ^ (io.Commit_s1e & BDest_s1m(BOOST_VALID_BIT)),
    BDest_s1m(VALID_BIT) & ~io.Except_s1w,
    BDest_s1m(LOAD_BIT),
    BDest_s1m(HARD_DEST_BIT),
    BDest_s1m(SPEC_BIT, 0)
  )
  
  when(io.Phi1 & ~io.Stall_s1) {
    BDest_s2m := BDest_v1m_bits
  }
  
  when(Phi2 & ~io.Stall_s2) {
    BDest_s1w := BDest_s2m
  }
  
  // Delay IStall
  when(io.Phi1) {
    IStall_s2 := io.IStall_s1
    MemStall_s2 := io.MemStall_s1
  }
  
  // Kill Chain - Qualified clocks
  io.Alpha2_s2e := ADest_s2e(VALID_BIT) & ~io.Stall_s2
  io.Alpha1_s1m := ADest_s1m(VALID_BIT) & ~io.Stall_s1
  io.Alpha2_s2m := ADest_s2m(VALID_BIT) & ~io.Stall_s2
  
  io.Beta2_s2e := BDest_s2e(VALID_BIT) & ~io.Stall_s2
  io.Beta1_s1m := BDest_s1m(VALID_BIT) & ~io.Stall_s1
  io.Beta2_s2m := BDest_s2m(VALID_BIT) & ~io.Stall_s2
  
  // Output bypass selections
  io.ASBypassSel_s1e := ASBypassSel_s1e
  io.ATBypassSel_s1e := ATBypassSel_s1e
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
  
  import ABypassSpecConstants._
  
  // Individual comparison results
  val compareBm_match = (io.SrcSpec_s2r === io.BDest_s2m)
  val compareAm_match = (io.SrcSpec_s2r === io.ADest_s2m)
  val compareBe_match = (io.SrcSpec_s2r === io.BDest_s2e)
  val compareAe_match = (io.SrcSpec_s2r === io.ADest_s2e)
  
  // Build compare result vector
  val compareRes_v2r = Cat(
    compareBm_match,
    compareAm_match,
    compareBe_match,
    compareAe_match,
    true.B  // NO_BYPASS_BIT always true
  )
  
  val bypassSelV2R = Module(new PRIORITY_5)
  bypassSelV2R.io.in := compareRes_v2r
  io.BypassSel_v2r := bypassSelV2R.io.out
}

class PRIORITY_5 extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(5.W))
    val out = Output(UInt(5.W))
  })
  
  io.out := MuxCase(0.U(5.W), Seq(
    io.in(4) -> "b10000".U,
    io.in(3) -> "b01000".U,
    io.in(2) -> "b00100".U,
    io.in(1) -> "b00010".U,
    io.in(0) -> "b00001".U
  ))
}

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
  
  io.out := MuxCase(0.U(9.W), Seq(
    io.sel1 -> io.in1,
    io.sel2 -> io.in2,
    io.sel3 -> io.in3,
    io.sel4 -> io.in4
  ))
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
  
  io.out := MuxCase(0.U(10.W), Seq(
    io.sel1 -> io.in1,
    io.sel2 -> io.in2,
    io.sel3 -> io.in3
  ))
}

object VerilogGenerator extends App {
  emitVerilog(new ABypassSpec(), args)
}