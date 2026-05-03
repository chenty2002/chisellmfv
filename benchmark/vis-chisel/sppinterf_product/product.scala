package llmverify

import chisel3._
import chisel3.util._

// FSM states
object FSMState extends ChiselEnum {
  val reposo, espescr, escribe, espdir, capdir, esplee0, lee0, esplee1, lee1 = Value
}

class product extends Module {
  val io = IO(new Bundle {
    val rst = Input(Bool())
    val din = Input(UInt(8.W))
    val wrextb = Input(Bool())
    val rdextb = Input(Bool())
    val equal = Output(Bool())
    // Additional outputs to preserve internal signals
    val new_dout = Output(UInt(4.W))
    val old_dout = Output(UInt(4.W))
    val new_a = Output(Bool())
    val old_a = Output(Bool())
    val new_b = Output(Bool())
    val old_b = Output(Bool())
    val new_c = Output(Bool())
    val old_c = Output(Bool())
    val new_d = Output(Bool())
    val old_d = Output(Bool())
    val new_e = Output(Bool())
    val old_e = Output(Bool())
    val new_f = Output(Bool())
    val old_f = Output(Bool())
    val new_g = Output(Bool())
    val old_g = Output(Bool())
  })
  
  // Instantiate new interface
  val new_intf = Module(new sppinterf())
  new_intf.io.rst := io.rst
  new_intf.io.din := io.din
  new_intf.io.wrextb := io.wrextb
  new_intf.io.rdextb := io.rdextb
  
  // Instantiate old interface
  val old_intf = Module(new sppinterfs())
  old_intf.io.rst := io.rst
  old_intf.io.din := io.din
  old_intf.io.wrextb := io.wrextb
  old_intf.io.rdextb := io.rdextb
  
  // Compare outputs
  io.equal := (new_intf.io.dout === old_intf.io.dout) &&
              (new_intf.io.a === old_intf.io.a) &&
              (new_intf.io.b === old_intf.io.b) &&
              (new_intf.io.c === old_intf.io.c) &&
              (new_intf.io.d === old_intf.io.d) &&
              (new_intf.io.e === old_intf.io.e) &&
              (new_intf.io.f === old_intf.io.f) &&
              (new_intf.io.g === old_intf.io.g)
  
  // Additional outputs for debugging
  io.new_dout := new_intf.io.dout
  io.old_dout := old_intf.io.dout
  io.new_a := new_intf.io.a
  io.old_a := old_intf.io.a
  io.new_b := new_intf.io.b
  io.old_b := old_intf.io.b
  io.new_c := new_intf.io.c
  io.old_c := old_intf.io.c
  io.new_d := new_intf.io.d
  io.old_d := old_intf.io.d
  io.new_e := new_intf.io.e
  io.old_e := old_intf.io.e
  io.new_f := new_intf.io.f
  io.old_f := old_intf.io.f
  io.new_g := new_intf.io.g
  io.old_g := old_intf.io.g
}

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
  })
  
  // Internal signals
  val writeb = RegInit(true.B)
  val readb = RegInit(true.B)
  
  // FSM instance
  val fsm = Module(new fsmrdwr())
  fsm.io.rst := io.rst
  fsm.io.wrexb := writeb
  fsm.io.rdexb := readb
  
  // Memory and address
  val mem = RegInit(VecInit(Seq.fill(4)(0.U(8.W))))
  val ad = RegInit(0.U(8.W))
  val dout = RegInit(0.U(4.W))
  
  val memaddr = ad(1, 0)
  val ddisp = mem(memaddr)
  
  // Memory and address logic
  when (io.rst) {
    mem := VecInit(Seq.fill(4)(0.U(8.W)))
    ad := 0.U
    dout := 0.U
  }.otherwise {
    when (fsm.io.wrint) {
      mem(memaddr) := io.din
    }
    when (fsm.io.aleint) {
      ad := io.din
    }
    when (fsm.io.rdint) {
      when (fsm.io.nibble) {
        dout := ddisp(7, 4)
      }.otherwise {
        dout := ddisp(3, 0)
      }
    }
  }
  
  // 7-segment display
  val disp = Module(new segment7kk())
  disp.io.in := ad(3, 0)
  
  io.a := disp.io.A
  io.b := disp.io.B
  io.c := disp.io.C
  io.d := disp.io.D
  io.e := disp.io.E
  io.f := disp.io.F
  io.g := disp.io.G
  
  io.dout := dout
  
  // Input synchronization
  writeb := io.wrextb | io.rst
  readb := io.rdextb | io.rst
}

class sppinterfs extends Module {
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
  })
  
  val data = io.din
  
  // FSM instance
  val fsm = Module(new fsmrdwr())
  fsm.io.rst := io.rst
  
  // FF cleaners for input synchronization
  val ffcln1 = Module(new FFcleaner())
  ffcln1.io.D := io.wrextb
  ffcln1.io.RST := io.rst
  
  val ffcln2 = Module(new FFcleaner())
  ffcln2.io.D := io.rdextb
  ffcln2.io.RST := io.rst
  
  fsm.io.wrexb := ffcln1.io.Q
  fsm.io.rdexb := ffcln2.io.Q
  
  // Four registers for RAM emulation
  val reg0 = Module(new RegSinc(8))
  val reg1 = Module(new RegSinc(8))
  val reg2 = Module(new RegSinc(8))
  val reg3 = Module(new RegSinc(8))
  
  reg0.io.reset := io.rst
  reg0.io.din := data
  
  reg1.io.reset := io.rst
  reg1.io.din := data
  
  reg2.io.reset := io.rst
  reg2.io.din := data
  
  reg3.io.reset := io.rst
  reg3.io.din := data
  
  // Address register
  val rega = Module(new RegSinc(8))
  rega.io.reset := io.rst
  rega.io.din := data
  rega.io.load := fsm.io.aleint
  val ad = rega.io.dout
  
  // Decoder for write address
  val decaw = Module(new decod())
  decaw.io.enable := fsm.io.wrint
  decaw.io.address := ad(1, 0)
  val pw = decaw.io.pointers
  
  reg0.io.load := pw(0)
  reg1.io.load := pw(1)
  reg2.io.load := pw(2)
  reg3.io.load := pw(3)
  
  // Decoder for read address
  val decar = Module(new decod())
  decar.io.enable := fsm.io.rdint
  decar.io.address := ad(1, 0)
  val pr = decar.io.pointers
  
  // Multiplexer for read selection
  val selrd = Module(new Mux4(8))
  selrd.io.r0 := reg0.io.dout
  selrd.io.r1 := reg1.io.dout
  selrd.io.r2 := reg2.io.dout
  selrd.io.r3 := reg3.io.dout
  selrd.io.paddr := pr
  val ddisp = selrd.io.rd
  
  // Multiplexer for nibble selection
  val muxrd = Module(new Muxsal())
  muxrd.io.I := ddisp
  muxrd.io.Nibble := fsm.io.nibble
  val drd = muxrd.io.datard
  
  // Output register
  val regr = Module(new RegSinc(5))
  regr.io.reset := io.rst
  regr.io.din := drd
  regr.io.load := fsm.io.rdint
  val daux = regr.io.dout
  
  // 7-segment display
  val disp = Module(new segment7kk())
  disp.io.in := ad(3, 0)
  
  io.a := disp.io.A
  io.b := disp.io.B
  io.c := disp.io.C
  io.d := disp.io.D
  io.e := disp.io.E
  io.f := disp.io.F
  io.g := disp.io.G
  
  io.dout := daux(3, 0)
}

class fsmrdwr extends Module {
  val io = IO(new Bundle {
    val rst = Input(Bool())
    val wrexb = Input(Bool())
    val rdexb = Input(Bool())
    val wrint = Output(Bool())
    val aleint = Output(Bool())
    val rdint = Output(Bool())
    val nibble = Output(Bool())
  })
  
  val fsmstate = RegInit(FSMState.reposo)
  
  // Default outputs
  io.wrint := false.B
  io.aleint := false.B
  io.rdint := false.B
  io.nibble := false.B
  
  when (io.rst) {
    fsmstate := FSMState.reposo
  }.otherwise {
    switch (fsmstate) {
      is (FSMState.reposo) {
        io.wrint := false.B
        io.rdint := false.B
        io.aleint := false.B
        io.nibble := false.B
        when (io.wrexb && io.rdexb) {
          fsmstate := FSMState.reposo
        }.elsewhen (!io.wrexb && io.rdexb) {
          fsmstate := FSMState.espescr
        }.elsewhen (!io.wrexb && !io.rdexb) {
          fsmstate := FSMState.espdir
        }.otherwise {
          fsmstate := FSMState.esplee0
        }
      }
      is (FSMState.espescr) {
        io.wrint := false.B
        io.rdint := false.B
        io.aleint := false.B
        io.nibble := false.B
        when (!io.wrexb && io.rdexb) {
          fsmstate := FSMState.espescr
        }.elsewhen (!io.wrexb && !io.rdexb) {
          fsmstate := FSMState.espdir
        }.otherwise {
          fsmstate := FSMState.escribe
        }
      }
      is (FSMState.escribe) {
        io.wrint := true.B
        io.rdint := false.B
        io.aleint := false.B
        io.nibble := false.B
        when (io.wrexb && io.rdexb) {
          fsmstate := FSMState.reposo
        }.otherwise {
          fsmstate := FSMState.escribe
        }
      }
      is (FSMState.espdir) {
        io.wrint := false.B
        io.rdint := false.B
        io.aleint := false.B
        io.nibble := false.B
        when (!io.wrexb && !io.rdexb) {
          fsmstate := FSMState.espdir
        }.otherwise {
          fsmstate := FSMState.capdir
        }
      }
      is (FSMState.capdir) {
        io.wrint := false.B
        io.rdint := false.B
        io.aleint := true.B
        io.nibble := false.B
        when (io.wrexb && io.rdexb) {
          fsmstate := FSMState.reposo
        }.otherwise {
          fsmstate := FSMState.capdir
        }
      }
      is (FSMState.esplee0) {
        io.wrint := false.B
        io.rdint := false.B
        io.aleint := false.B
        io.nibble := false.B
        when (io.wrexb && !io.rdexb) {
          fsmstate := FSMState.esplee0
        }.elsewhen (!io.wrexb && !io.rdexb) {
          fsmstate := FSMState.espdir
        }.otherwise {
          fsmstate := FSMState.lee0
        }
      }
      is (FSMState.lee0) {
        io.wrint := false.B
        io.rdint := true.B
        io.aleint := false.B
        io.nibble := false.B
        when (io.rdexb) {
          fsmstate := FSMState.lee0
        }.otherwise {
          fsmstate := FSMState.esplee1
        }
      }
      is (FSMState.esplee1) {
        io.wrint := false.B
        io.rdint := false.B
        io.aleint := false.B
        io.nibble := false.B
        when (io.wrexb && !io.rdexb) {
          fsmstate := FSMState.esplee1
        }.elsewhen (!io.wrexb && !io.rdexb) {
          fsmstate := FSMState.espdir
        }.otherwise {
          fsmstate := FSMState.lee1
        }
      }
      is (FSMState.lee1) {
        io.wrint := false.B
        io.aleint := false.B
        io.rdint := true.B
        io.nibble := true.B
        when (io.wrexb && io.rdexb) {
          fsmstate := FSMState.reposo
        }.otherwise {
          fsmstate := FSMState.lee1
        }
      }
    }
  }
}

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

class RegSinc(width: Int) extends Module {
  val io = IO(new Bundle {
    val reset = Input(Bool())
    val load = Input(Bool())
    val din = Input(UInt(width.W))
    val dout = Output(UInt(width.W))
  })
  
  val dout = RegInit(0.U(width.W))
  
  when (io.reset) {
    dout := 0.U
  }.elsewhen (io.load) {
    dout := io.din
  }
  
  io.dout := dout
}

class Mux4(width: Int) extends Module {
  val io = IO(new Bundle {
    val r0 = Input(UInt(width.W))
    val r1 = Input(UInt(width.W))
    val r2 = Input(UInt(width.W))
    val r3 = Input(UInt(width.W))
    val paddr = Input(UInt(4.W))
    val rd = Output(UInt(width.W))
  })
  
  io.rd := Mux(io.paddr(0), io.r0,
              Mux(io.paddr(1), io.r1,
                  Mux(io.paddr(2), io.r2,
                      Mux(io.paddr(3), io.r3, 0.U))))
}

class Muxsal extends Module {
  val io = IO(new Bundle {
    val I = Input(UInt(8.W))
    val Nibble = Input(Bool())
    val datard = Output(UInt(5.W))
  })
  
  io.datard := Cat(io.Nibble, Mux(io.Nibble, io.I(7, 4), io.I(3, 0)))
}

class decod extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val address = Input(UInt(2.W))
    val pointers = Output(UInt(4.W))
  })
  
  // Default assignment to ensure initialization in all paths
  io.pointers := 0.U
  
  when (io.enable) {
    switch (io.address) {
      is (0.U) { io.pointers := 1.U }
      is (1.U) { io.pointers := 2.U }
      is (2.U) { io.pointers := 4.U }
      is (3.U) { io.pointers := 8.U }
    }
  }
}

class FFcleaner extends Module {
  val io = IO(new Bundle {
    val D = Input(Bool())
    val RST = Input(Bool())
    val Q = Output(Bool())
  })
  
  val Q = RegInit(true.B)
  
  when (io.RST) {
    Q := true.B
  }.otherwise {
    Q := io.D
  }
  
  io.Q := Q
}

object VerilogGenerator extends App {
  emitVerilog(new product(), args)
}