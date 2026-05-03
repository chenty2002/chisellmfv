package llmverify

import chisel3._
import chisel3.util._

// FSM For Access Protocol
object FsmState extends ChiselEnum {
  val reposo, espescr, escribe, espdir, capdir, esplee0, lee0, esplee1, lee1 = Value
}

class fsmrdwr extends Module {
  val io = IO(new Bundle {
    val wrexb = Input(Bool())
    val rdexb = Input(Bool())
    val wrint = Output(Bool())
    val aleint = Output(Bool())
    val rdint = Output(Bool())
    val nibble = Output(Bool())
  })
  
  val fsmState = RegInit(FsmState.reposo)
  
  // Default outputs
  io.wrint := false.B
  io.aleint := false.B
  io.rdint := false.B
  io.nibble := false.B
  
  switch(fsmState) {
    is(FsmState.reposo) {
      io.wrint := false.B
      io.rdint := false.B
      io.aleint := false.B
      io.nibble := false.B
      when(io.wrexb && io.rdexb) {
        fsmState := FsmState.reposo
      }.elsewhen(!io.wrexb && io.rdexb) {
        fsmState := FsmState.espescr
      }.elsewhen(!io.wrexb && !io.rdexb) {
        fsmState := FsmState.espdir
      }.otherwise {
        fsmState := FsmState.esplee0
      }
    }
    is(FsmState.espescr) {
      io.wrint := false.B
      io.rdint := false.B
      io.aleint := false.B
      io.nibble := false.B
      when(!io.wrexb && io.rdexb) {
        fsmState := FsmState.espescr
      }.elsewhen(!io.wrexb && !io.rdexb) {
        fsmState := FsmState.espdir
      }.otherwise {
        fsmState := FsmState.escribe
      }
    }
    is(FsmState.escribe) {
      io.wrint := true.B
      io.rdint := false.B
      io.aleint := false.B
      io.nibble := false.B
      when(io.wrexb && io.rdexb) {
        fsmState := FsmState.reposo
      }.otherwise {
        fsmState := FsmState.escribe
      }
    }
    is(FsmState.espdir) {
      io.wrint := false.B
      io.rdint := false.B
      io.aleint := false.B
      io.nibble := false.B
      when(!io.wrexb && !io.rdexb) {
        fsmState := FsmState.espdir
      }.otherwise {
        fsmState := FsmState.capdir
      }
    }
    is(FsmState.capdir) {
      io.wrint := false.B
      io.rdint := false.B
      io.aleint := true.B
      io.nibble := false.B
      when(io.wrexb && io.rdexb) {
        fsmState := FsmState.reposo
      }.otherwise {
        fsmState := FsmState.capdir
      }
    }
    is(FsmState.esplee0) {
      io.wrint := false.B
      io.rdint := false.B
      io.aleint := false.B
      io.nibble := false.B
      when(io.wrexb && !io.rdexb) {
        fsmState := FsmState.esplee0
      }.elsewhen(!io.wrexb && !io.rdexb) {
        fsmState := FsmState.espdir
      }.otherwise {
        fsmState := FsmState.lee0
      }
    }
    is(FsmState.lee0) {
      io.wrint := false.B
      io.rdint := true.B
      io.aleint := false.B
      io.nibble := false.B
      when(io.rdexb) {
        fsmState := FsmState.lee0
      }.otherwise {
        fsmState := FsmState.esplee1
      }
    }
    is(FsmState.esplee1) {
      io.wrint := false.B
      io.rdint := false.B
      io.aleint := false.B
      io.nibble := false.B
      when(io.wrexb && !io.rdexb) {
        fsmState := FsmState.esplee1
      }.elsewhen(!io.wrexb && !io.rdexb) {
        fsmState := FsmState.espdir
      }.otherwise {
        fsmState := FsmState.lee1
      }
    }
    is(FsmState.lee1) {
      io.wrint := false.B
      io.aleint := false.B
      io.rdint := true.B
      io.nibble := true.B
      when(io.wrexb && io.rdexb) {
        fsmState := FsmState.reposo
      }.otherwise {
        fsmState := FsmState.lee1
      }
    }
  }
}

// Generic register
class RegSinc(width: Int = 8) extends Module {
  val io = IO(new Bundle {
    val reset = Input(Bool())
    val load = Input(Bool())
    val din = Input(UInt(width.W))
    val dout = Output(UInt(width.W))
  })
  
  val reg = RegInit(0.U(width.W))
  
  when(io.reset) {
    reg := 0.U
  }.elsewhen(io.load) {
    reg := io.din
  }
  
  io.dout := reg
}

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
  
  val in = io.in
  
  io.A := (in === 0.U || in === 2.U || in === 3.U || in === 5.U || in === 6.U ||
           in === 7.U || in === 8.U || in === 9.U || in === 10.U || in === 14.U || in === 15.U)
  io.B := (in === 0.U || in === 4.U || in === 5.U || in === 6.U ||
           in === 8.U || in === 9.U || in === 10.U || in === 11.U || in === 14.U || in === 15.U)
  io.C := (in === 0.U || in === 2.U || in === 6.U || in === 8.U || in === 10.U ||
           in === 11.U || in === 12.U || in === 13.U || in === 14.U || in === 15.U)
  io.D := (in === 0.U || in === 2.U || in === 3.U || in === 5.U || in === 6.U ||
           in === 8.U || in === 11.U || in === 12.U || in === 13.U || in === 14.U)
  io.E := (in === 0.U || in === 1.U || in === 3.U || in === 4.U || in === 5.U ||
           in === 6.U || in === 7.U || in === 8.U || in === 9.U || in === 10.U || in === 11.U || in === 13.U)
  io.F := (in === 0.U || in === 1.U || in === 2.U || in === 3.U || in === 4.U ||
           in === 7.U || in === 8.U || in === 9.U || in === 10.U || in === 13.U || in === 14.U)
  io.G := (in === 2.U || in === 3.U || in === 4.U || in === 5.U || in === 6.U ||
           in === 8.U || in === 9.U || in === 10.U || in === 11.U || in === 12.U ||
           in === 13.U || in === 14.U || in === 15.U)
}

// Mux for read byte
class Mux4(width: Int = 8) extends Module {
  val io = IO(new Bundle {
    val r0 = Input(UInt(width.W))
    val r1 = Input(UInt(width.W))
    val r2 = Input(UInt(width.W))
    val r3 = Input(UInt(width.W))
    val paddr = Input(UInt(4.W))
    val rd = Output(UInt(width.W))
  })
  
  io.rd := MuxCase(0.U, Seq(
    io.paddr(0) -> io.r0,
    io.paddr(1) -> io.r1,
    io.paddr(2) -> io.r2,
    io.paddr(3) -> io.r3
  ))
}

// Read Nibbles Mux
class Muxsal extends Module {
  val io = IO(new Bundle {
    val I = Input(UInt(8.W))
    val Nibble = Input(Bool())
    val datard = Output(UInt(5.W))
  })
  
  io.datard := Cat(io.Nibble, Mux(io.Nibble, io.I(7,4), io.I(3,0)))
}

// Decoder for Address Selection
class decod extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val address = Input(UInt(2.W))
    val pointers = Output(UInt(4.W))
  })
  
  io.pointers := Mux(io.enable, 
    MuxCase(0.U, Seq(
      (io.address === 0.U) -> 1.U,
      (io.address === 1.U) -> 2.U,
      (io.address === 2.U) -> 4.U,
      (io.address === 3.U) -> 8.U
    )),
    0.U
  )
}

// FF for glitch filtering
class FFcleaner extends Module {
  val io = IO(new Bundle {
    val D = Input(Bool())
    val RST = Input(Bool())
    val Q = Output(Bool())
  })
  
  val qReg = RegInit(true.B)
  
  when(io.RST) {
    qReg := true.B
  }.otherwise {
    qReg := io.D
  }
  
  io.Q := qReg
}

// Main SRAM Parallel Port Interface module
class sppinterf extends Module {
  val io = IO(new Bundle {
    val rst = Input(Bool())
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
    
    // Additional outputs to preserve internal signals
    val writeb = Output(Bool())
    val readb = Output(Bool())
    val wrint = Output(Bool())
    val aleint = Output(Bool())
    val rdint = Output(Bool())
    val nibble = Output(Bool())
    val ad = Output(UInt(8.W))
    val ddisp = Output(UInt(8.W))
  })
  
  val data = io.din
  
  // FF cleaners for input signals
  val ffcln1 = Module(new FFcleaner())
  ffcln1.io.D := io.wrextb
  ffcln1.io.RST := io.rst
  val writeb = ffcln1.io.Q
  
  val ffcln2 = Module(new FFcleaner())
  ffcln2.io.D := io.rdextb
  ffcln2.io.RST := io.rst
  val readb = ffcln2.io.Q
  
  // FSM instance
  val fsm = Module(new fsmrdwr())
  fsm.io.wrexb := writeb
  fsm.io.rdexb := readb
  
  // Address Register
  val rega = Module(new RegSinc(8))
  rega.io.reset := io.rst
  rega.io.load := fsm.io.aleint
  rega.io.din := data
  val ad = rega.io.dout
  
  // Decoder for write Address
  val decaw = Module(new decod())
  decaw.io.enable := fsm.io.wrint
  decaw.io.address := ad(1,0)
  val pw = decaw.io.pointers
  
  // Decoder for Read Address
  val decar = Module(new decod())
  decar.io.enable := fsm.io.rdint
  decar.io.address := ad(1,0)
  val pr = decar.io.pointers
  
  // Four Registers for RAM emulation
  val reg0 = Module(new RegSinc(8))
  reg0.io.reset := io.rst
  reg0.io.load := pw(0)
  reg0.io.din := data
  
  val reg1 = Module(new RegSinc(8))
  reg1.io.reset := io.rst
  reg1.io.load := pw(1)
  reg1.io.din := data
  
  val reg2 = Module(new RegSinc(8))
  reg2.io.reset := io.rst
  reg2.io.load := pw(2)
  reg2.io.din := data
  
  val reg3 = Module(new RegSinc(8))
  reg3.io.reset := io.rst
  reg3.io.load := pw(3)
  reg3.io.din := data
  
  // Multiplexer for Read Selection
  val selrd = Module(new Mux4(8))
  selrd.io.r0 := reg0.io.dout
  selrd.io.r1 := reg1.io.dout
  selrd.io.r2 := reg2.io.dout
  selrd.io.r3 := reg3.io.dout
  selrd.io.paddr := pr
  val ddisp = selrd.io.rd
  
  // Multiplexer for Nibble selection
  val muxrd = Module(new Muxsal())
  muxrd.io.I := ddisp
  muxrd.io.Nibble := fsm.io.nibble
  
  // Store reading Data in an output register
  val regr = Module(new RegSinc(5))
  regr.io.reset := io.rst
  regr.io.load := fsm.io.rdint
  regr.io.din := muxrd.io.datard
  
  // 7-segment display
  val disp = Module(new segment7kk())
  disp.io.in := ad(3,0)
  
  // Connect outputs
  io.dout := regr.io.dout(3,0)
  io.a := disp.io.A
  io.b := disp.io.B
  io.c := disp.io.C
  io.d := disp.io.D
  io.e := disp.io.E
  io.f := disp.io.F
  io.g := disp.io.G
  
  // Additional outputs for debugging
  io.writeb := writeb
  io.readb := readb
  io.wrint := fsm.io.wrint
  io.aleint := fsm.io.aleint
  io.rdint := fsm.io.rdint
  io.nibble := fsm.io.nibble
  io.ad := ad
  io.ddisp := ddisp
}

object VerilogGenerator extends App {
  emitVerilog(new sppinterf(), args)
}