package llmverify

import chisel3._
import chisel3.util._

// Buechi state machine
object BuechiStates {
  val n5 :: n6 :: n7 :: n8 :: n10 :: trap :: Nil = Enum(6)
}

class Buechi extends Module {
  import BuechiStates._
  val io = IO(new Bundle {
    val p0match = Input(Bool())
    val p0readheadNEQp0storeaddr = Input(Bool())
    val p0writefifo_p0writetailEQp0readfifo_p0readhead = Input(Bool())
    val busgnt0 = Input(Bool())
    val fair = Output(Bool())
    val scc = Output(Bool())
  })
  
  val state = RegInit(n6)
  
  // Handle nondeterministic choices using Mux (simplified approach)
  val ND_n7_n8 = Mux(state === n7 || state === n8, Mux(io.busgnt0 && !io.p0readheadNEQp0storeaddr && !io.p0writefifo_p0writetailEQp0readfifo_p0readhead, n8, n7), n7)
  val ND_n5_n6 = Mux(state === n6 && io.p0match && !io.p0readheadNEQp0storeaddr, n5, n6)
  val ND_n10_n8 = Mux(state === n10 && io.busgnt0 && !io.p0readheadNEQp0storeaddr && io.p0writefifo_p0writetailEQp0readfifo_p0readhead, n8, n10)
  
  io.fair := (state === n8)
  io.scc := (state === n7) || (state === n8)
  
  when(state === trap) {
    state := trap
  }.elsewhen(state === n6) {
    when(!io.p0match) {
      state := n6
    }.elsewhen(io.p0match && !io.p0readheadNEQp0storeaddr) {
      state := ND_n5_n6
    }.otherwise {
      state := n6
    }
  }.elsewhen(state === n10) {
    when(!io.busgnt0 && !io.p0readheadNEQp0storeaddr) {
      state := n10
    }.elsewhen(!io.busgnt0 && io.p0readheadNEQp0storeaddr) {
      state := trap
    }.elsewhen(io.busgnt0 && !io.p0readheadNEQp0storeaddr && io.p0writefifo_p0writetailEQp0readfifo_p0readhead) {
      state := ND_n10_n8
    }.elsewhen(io.busgnt0 && !io.p0readheadNEQp0storeaddr && !io.p0writefifo_p0writetailEQp0readfifo_p0readhead) {
      state := n10
    }.otherwise {
      state := trap
    }
  }.elsewhen(state === n7 || state === n8) {
    when(!io.busgnt0 && !io.p0readheadNEQp0storeaddr && !io.p0writefifo_p0writetailEQp0readfifo_p0readhead) {
      state := n7
    }.elsewhen(io.p0readheadNEQp0storeaddr) {
      state := trap
    }.elsewhen(io.p0writefifo_p0writetailEQp0readfifo_p0readhead) {
      state := trap
    }.elsewhen(io.busgnt0 && !io.p0readheadNEQp0storeaddr && !io.p0writefifo_p0writetailEQp0readfifo_p0readhead) {
      state := ND_n7_n8
    }.otherwise {
      state := trap
    }
  }.elsewhen(state === n5) {
    when(!io.p0readheadNEQp0storeaddr) {
      state := n10
    }.otherwise {
      state := trap
    }
  }
}

class sampleq(WIDTH: Int = 2, LENGTH: Int = 4, LOGLENGTH: Int = 2) extends Module {
  val io = IO(new Bundle {
    val inaddr = Input(UInt(WIDTH.W))
    val validin = Input(Bool())
    val readin = Input(Bool())
    val clkin = Input(Clock())
    val bus_gnt = Input(Bool())
    val bus_req = Output(Bool())
    val outaddr = Output(UInt(WIDTH.W))
    val validout = Output(Bool())
    val outisaread = Output(Bool())
    val readheadentry = Output(UInt(WIDTH.W))
    val match_out = Output(Bool())
    val p0readheadNEQp0storeaddr = Output(Bool())
    val p0writefifo_p0writetailEQp0readfifo_p0readhead = Output(Bool())
  })
  
  // FIFO memories
  val readfifo = RegInit(VecInit(Seq.fill(LENGTH)(0.U(WIDTH.W))))
  val writefifo = RegInit(VecInit(Seq.fill(LENGTH)(0.U(WIDTH.W))))
  
  // Pointers
  val readtail = RegInit(0.U(LOGLENGTH.W))
  val readhead = RegInit(0.U(LOGLENGTH.W))
  val writehead = RegInit(0.U(LOGLENGTH.W))
  val writetail = RegInit(0.U(LOGLENGTH.W))
  
  // Other registers
  val storeaddr = RegInit(0.U(LOGLENGTH.W))
  val inputmatch = RegInit(false.B)
  val match_reg = RegInit(false.B)
  val outaddr_reg = RegInit(0.U(WIDTH.W))
  val outisaread_reg = RegInit(false.B)
  val validout_reg = RegInit(false.B)
  
  // Combinational signals
  val readempty = (readtail === readhead)
  val writeempty = (writetail === writehead)
  val readfull = ((readtail + 1.U) & ((1.U << LOGLENGTH) - 1.U)) === readhead
  val writefull = ((writetail + 1.U) & ((1.U << LOGLENGTH) - 1.U)) === writehead
  
  io.readheadentry := readfifo(readhead)
  io.bus_req := !(readempty && writeempty)
  
  // Output assignments
  io.outaddr := outaddr_reg
  io.validout := validout_reg
  io.outisaread := outisaread_reg
  io.match_out := match_reg
  
  // Property outputs
  io.p0writefifo_p0writetailEQp0readfifo_p0readhead := writefifo(writetail) === readfifo(readhead)
  io.p0readheadNEQp0storeaddr := readhead =/= storeaddr
  
  // Sequential logic
  when(io.validin && io.readin && !readfull) {
    readfifo(readtail) := io.inaddr
    readtail := readtail + 1.U
  }.elsewhen(io.validin && !io.readin && !writefull) {
    writefifo(writetail) := io.inaddr
    writetail := writetail + 1.U
  }
  
  when(io.bus_gnt) {
    // Check for match between read queue entry and write queue entries
    match_reg := false.B
    storeaddr := readhead
    
    for (i <- 0 until LENGTH) {
      val inWriteRange = ((writehead < writetail) && (i.U >= writehead) && (i.U < writetail)) ||
                         ((writehead > writetail) && ((i.U >= writehead) || (i.U < writetail)))
      when(inWriteRange && !readempty && (readfifo(readhead) === writefifo(i.U))) {
        match_reg := true.B
        storeaddr := readhead
      }
    }
    
    // Output logic
    when(!readempty && !match_reg) {
      outaddr_reg := readfifo(readhead)
      readhead := readhead + 1.U
      outisaread_reg := true.B
      validout_reg := true.B
    }.elsewhen(!writeempty) {
      outaddr_reg := writefifo(writehead)
      writehead := writehead + 1.U
      outisaread_reg := false.B
      validout_reg := true.B
    }.otherwise {
      validout_reg := false.B
    }
  }
}

class twoQ extends Module {
  val WIDTH = 2
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val select = Input(Bool())
    val validin = Input(UInt(2.W))
    val readin = Input(UInt(2.W))
    val inaddr0 = Input(UInt(WIDTH.W))
    val inaddr1 = Input(UInt(WIDTH.W))
    
    // Additional outputs to preserve the design
    val bus_req = Output(UInt(2.W))
    val validout = Output(UInt(2.W))
    val outisread = Output(UInt(2.W))
    val outaddr0 = Output(UInt(WIDTH.W))
    val outaddr1 = Output(UInt(WIDTH.W))
    val readheadentry0 = Output(UInt(WIDTH.W))
    val readheadentry1 = Output(UInt(WIDTH.W))
    val fair = Output(Bool())
    val scc = Output(Bool())
  })
  
  val bus_gnt = RegInit(0.U(2.W))
  
  // Instantiate sampleq modules
  val q0 = Module(new sampleq(WIDTH, 4, 2))
  q0.io.inaddr := io.inaddr0
  q0.io.validin := io.validin(0)
  q0.io.readin := io.readin(0)
  q0.io.clkin := io.clock
  q0.io.bus_gnt := bus_gnt(0)
  
  val q1 = Module(new sampleq(WIDTH, 4, 2))
  q1.io.inaddr := io.inaddr1
  q1.io.validin := io.validin(1)
  q1.io.readin := io.readin(1)
  q1.io.clkin := io.clock
  q1.io.bus_gnt := bus_gnt(1)
  
  // Instantiate Buechi module
  val buechi = Module(new Buechi())
  buechi.io.p0match := q0.io.match_out
  buechi.io.p0readheadNEQp0storeaddr := q0.io.p0readheadNEQp0storeaddr
  buechi.io.p0writefifo_p0writetailEQp0readfifo_p0readhead := q0.io.p0writefifo_p0writetailEQp0readfifo_p0readhead
  buechi.io.busgnt0 := bus_gnt(0) === 1.U
  
  // Bus grant logic
  when(io.select && q1.io.bus_req) {
    bus_gnt := 2.U(2.W)
  }.elsewhen(!io.select && q0.io.bus_req) {
    bus_gnt := 1.U(2.W)
  }.otherwise {
    bus_gnt := 0.U(2.W)
  }
  
  // Connect outputs to preserve design
  io.bus_req := Cat(q1.io.bus_req, q0.io.bus_req)
  io.validout := Cat(q1.io.validout, q0.io.validout)
  io.outisread := Cat(q1.io.outisaread, q0.io.outisaread)
  io.outaddr0 := q0.io.outaddr
  io.outaddr1 := q1.io.outaddr
  io.readheadentry0 := q0.io.readheadentry
  io.readheadentry1 := q1.io.readheadentry
  io.fair := buechi.io.fair
  io.scc := buechi.io.scc
}

object VerilogGenerator extends App {
  emitVerilog(new twoQ(), args)
}