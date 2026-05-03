package llmverify

import chisel3._
import chisel3.util._

// 7 Segments Driver
class segment7kk extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(4.W))
    val A = Output(Bool())
    val B = Output(Bool())
    val C = Output(Bool())
    val D = Output(Bool())
    val E = Output(Bool())
    val F = Output(Bool())
    val G = Output(Bool())
  })
  
  // segment encoding
  //       A
  //      ---  
  //   B |   | F
  //      ---   <- G
  //   C |   | E
  //      ---
  //       D
  
  val in = io.in
  io.A := (in === 0.U) || (in === 2.U) || (in === 3.U) || (in === 5.U) || (in === 6.U) ||
           (in === 7.U) || (in === 8.U) || (in === 9.U) || (in === 10.U) || (in === 14.U) || (in === 15.U)
  io.B := (in === 0.U) || (in === 4.U) || (in === 5.U) || (in === 6.U) ||
           (in === 8.U) || (in === 9.U) || (in === 10.U) || (in === 11.U) || (in === 14.U) || (in === 15.U)
  io.C := (in === 0.U) || (in === 2.U) || (in === 6.U) || (in === 8.U) || (in === 10.U) ||
           (in === 11.U) || (in === 12.U) || (in === 13.U) || (in === 14.U) || (in === 15.U)
  io.D := (in === 0.U) || (in === 2.U) || (in === 3.U) || (in === 5.U) || (in === 6.U) ||
           (in === 8.U) || (in === 11.U) || (in === 12.U) || (in === 13.U) || (in === 14.U)
  io.E := (in === 0.U) || (in === 1.U) || (in === 3.U) || (in === 4.U) || (in === 5.U) ||
           (in === 6.U) || (in === 7.U) || (in === 8.U) || (in === 9.U) || (in === 10.U) || (in === 11.U) ||
           (in === 13.U)
  io.F := (in === 0.U) || (in === 1.U) || (in === 2.U) || (in === 3.U) || (in === 4.U) ||
           (in === 7.U) || (in === 8.U) || (in === 9.U) || (in === 10.U) || (in === 13.U) || (in === 14.U)
  io.G := (in === 2.U) || (in === 3.U) || (in === 4.U) || (in === 5.U) || (in === 6.U) ||
           (in === 8.U) || (in === 9.U) || (in === 10.U) || (in === 11.U) || (in === 12.U) ||
           (in === 13.U) || (in === 14.U) || (in === 15.U)
}

// FSM For Access Protocol
class fsmrdwr extends Module {
  val io = IO(new Bundle {
    val wrexb = Input(Bool())
    val rdexb = Input(Bool())
    val wrint = Output(Bool())
    val aleint = Output(Bool())
    val rdint = Output(Bool())
    val nibble = Output(Bool())
    val state = Output(UInt(4.W))  // Expose state as UInt for debugging
  })
  
  // Define states
  object sState extends ChiselEnum {
    val reposo, espescr, escribe, espdir, capdir, esplee0, lee0, esplee1, lee1 = Value
  }
  
  val fsmState = RegInit(sState.reposo)
  val wrintReg = RegInit(false.B)
  val aleintReg = RegInit(false.B)
  val rdintReg = RegInit(false.B)
  val nibbleReg = RegInit(false.B)
  
  io.wrint := wrintReg
  io.aleint := aleintReg
  io.rdint := rdintReg
  io.nibble := nibbleReg
  
  // Expose state as UInt for debugging
  io.state := fsmState.asUInt
  
  // State machine logic
  switch(fsmState) {
    is(sState.reposo) { // idle
      wrintReg := false.B
      rdintReg := false.B
      aleintReg := false.B
      nibbleReg := false.B
      when(io.wrexb && io.rdexb) {
        fsmState := sState.reposo
      }.elsewhen(!io.wrexb && io.rdexb) {
        fsmState := sState.espescr
      }.elsewhen(!io.wrexb && !io.rdexb) {
        fsmState := sState.espdir
      }.otherwise {
        fsmState := sState.esplee0
      }
    }
    is(sState.espescr) { // waiting to write
      wrintReg := false.B
      rdintReg := false.B
      aleintReg := false.B
      nibbleReg := false.B
      when(!io.wrexb && io.rdexb) {
        fsmState := sState.espescr
      }.elsewhen(!io.wrexb && !io.rdexb) {
        fsmState := sState.espdir
      }.otherwise {
        fsmState := sState.escribe
      }
    }
    is(sState.escribe) { // writing
      wrintReg := true.B
      rdintReg := false.B
      aleintReg := false.B
      nibbleReg := false.B
      when(io.wrexb && io.rdexb) {
        fsmState := sState.reposo
      }.otherwise {
        fsmState := sState.escribe
      }
    }
    is(sState.espdir) { // waiting for the address
      wrintReg := false.B
      rdintReg := false.B
      aleintReg := false.B
      nibbleReg := false.B
      when(!io.wrexb && !io.rdexb) {
        fsmState := sState.espdir
      }.otherwise {
        fsmState := sState.capdir
      }
    }
    is(sState.capdir) { // latching the address
      wrintReg := false.B
      rdintReg := false.B
      aleintReg := true.B
      nibbleReg := false.B
      when(io.wrexb && io.rdexb) {
        fsmState := sState.reposo
      }.otherwise {
        fsmState := sState.capdir
      }
    }
    is(sState.esplee0) { // waiting to read least significant nibble
      wrintReg := false.B
      rdintReg := false.B
      aleintReg := false.B
      nibbleReg := false.B
      when(io.wrexb && !io.rdexb) {
        fsmState := sState.esplee0
      }.elsewhen(!io.wrexb && !io.rdexb) {
        fsmState := sState.espdir
      }.otherwise {
        fsmState := sState.lee0
      }
    }
    is(sState.lee0) { // read least significant nibble
      wrintReg := false.B
      rdintReg := true.B
      aleintReg := false.B
      nibbleReg := false.B
      when(io.rdexb) {
        fsmState := sState.lee0
      }.otherwise {
        fsmState := sState.esplee1
      }
    }
    is(sState.esplee1) { // waiting to read most significant nibble
      wrintReg := false.B
      rdintReg := false.B
      aleintReg := false.B
      nibbleReg := false.B
      when(io.wrexb && !io.rdexb) {
        fsmState := sState.esplee1
      }.elsewhen(!io.wrexb && !io.rdexb) {
        fsmState := sState.espdir
      }.otherwise {
        fsmState := sState.lee1
      }
    }
    is(sState.lee1) { // read most significant nibble
      wrintReg := false.B
      rdintReg := true.B
      aleintReg := false.B
      nibbleReg := true.B
      when(io.wrexb && io.rdexb) {
        fsmState := sState.reposo
      }.otherwise {
        fsmState := sState.lee1
      }
    }
  }
  
  // Reset logic
  when(reset.asBool) {
    fsmState := sState.reposo
  }
}

// SRAM with parallel port interface for XESS XS40 Boards
class sppinterf extends Module {
  val io = IO(new Bundle {
    val din = Input(UInt(8.W))
    val wrextb = Input(Bool())
    val rdextb = Input(Bool())
    val dout = Output(UInt(4.W))
    val a = Output(Bool())
    val b = Output(Bool())
    val c = Output(Bool())
    val d = Output(Bool())
    val e = Output(Bool())
    val f = Output(Bool())
    val g = Output(Bool())
    // Additional outputs for debugging
    val mem_debug = Output(Vec(4, UInt(8.W)))
    val ad_debug = Output(UInt(8.W))
    val fsm_debug = Output(UInt(4.W))
  })
  
  // Internal registers
  val writeb = RegInit(true.B)
  val readb = RegInit(true.B)
  val ad = RegInit(0.U(8.W))
  val doutReg = RegInit(0.U(4.W))
  
  // Memory array (4 locations of 8 bits each)
  val mem = RegInit(VecInit(Seq.fill(4)(0.U(8.W))))
  
  // FSM instantiation
  val fsm = Module(new fsmrdwr())
  fsm.io.wrexb := writeb
  fsm.io.rdexb := readb
  val wrint = fsm.io.wrint
  val aleint = fsm.io.aleint
  val rdint = fsm.io.rdint
  val nibble = fsm.io.nibble
  
  // Memory address
  val memaddr = ad(1, 0)
  
  // Display data from memory
  val ddisp = mem(memaddr)
  
  // Main logic
  when(reset.asBool) {
    // Reset memory and registers
    for (i <- 0 until 4) {
      mem(i) := 0.U
    }
    ad := 0.U
    doutReg := 0.U
  }.otherwise {
    when(wrint) {
      mem(memaddr) := io.din
    }
    when(aleint) {
      ad := io.din
    }
    when(rdint) {
      when(nibble) {
        doutReg := ddisp(7, 4)
      }.otherwise {
        doutReg := ddisp(3, 0)
      }
    }
  }
  
  // 7-segment display driver instantiation
  val disp = Module(new segment7kk())
  disp.io.in := ad(3, 0)
  io.a := disp.io.A
  io.b := disp.io.B
  io.c := disp.io.C
  io.d := disp.io.D
  io.e := disp.io.E
  io.f := disp.io.F
  io.g := disp.io.G
  
  // Output assignments
  io.dout := doutReg
  
  // Make sure inputs are clean
  writeb := io.wrextb || reset.asBool
  readb := io.rdextb || reset.asBool
  
  // Debug outputs
  io.mem_debug := mem
  io.ad_debug := ad
  io.fsm_debug := fsm.io.state  // Use the exposed state from FSM
}

object VerilogGenerator extends App {
  emitVerilog(new sppinterf(), args)
}