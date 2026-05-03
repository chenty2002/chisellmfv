package llmverify
import chisel3._
import chisel3.util._

class Main extends Module {
  val io = IO(new Bundle {
    val req = Output(Bool())
    val ack = Output(Bool())
    val nd = Output(Bool())
  })
  
  val req = RegInit(false.B)
  val nd = Wire(Bool())
  
  // Simulate $ND(0,1) with LFSR for non-deterministic behavior
  val lfsr = RegInit(1.U(8.W))
  lfsr := ((lfsr << 1) | ((lfsr >> 7) ^ (lfsr >> 5) ^ (lfsr >> 4) ^ (lfsr >> 3)) & 1.U)
  nd := lfsr(0)
  
  // This automatically happens on clock edge
  req := nd
  
  val ra = Module(new ReqAck())
  ra.io.req := req
  
  io.req := req
  io.ack := ra.io.ack
  io.nd := nd
}

class ReqAck extends Module {
  val io = IO(new Bundle {
    val req = Input(Bool())
    val ack = Output(Bool())
    val start = Output(Bool())
    val ready = Output(Bool())
  })
  
  // State definitions
  val idle :: starting :: working :: done :: Nil = Enum(4)
  val state = RegInit(idle)
  
  // State machine
  switch(state) {
    is(idle) {
      when(io.req) {
        state := starting
      } .otherwise {
        state := idle
      }
    }
    is(starting) {
      state := working
    }
    is(working) {
      when(io.ready) {
        state := done
      } .otherwise {
        state := working
      }
    }
    is(done) {
      state := idle
    }
  }
  
  io.ack := (state === done)
  io.start := (state === starting)
  
  val slv = Module(new SlaveND())
  slv.io.start := io.start
  io.ready := slv.io.ready
}

class SlaveND extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val ready = Output(Bool())
    val count = Output(UInt(2.W))
    val nd = Output(Bool())
  })
  
  val count = RegInit(0.U(2.W))
  val nd = Wire(Bool())
  
  // Simulate $ND(0,1) with LFSR for non-deterministic behavior
  val lfsr = RegInit(1.U(8.W))
  lfsr := ((lfsr << 1) | ((lfsr >> 7) ^ (lfsr >> 5) ^ (lfsr >> 4) ^ (lfsr >> 3)) & 1.U)
  nd := lfsr(0)
  
  // This automatically happens on clock edge
  when(io.start) {
    count := 0.U
  } .elsewhen(count === 0.U) {
    count := count + nd
  } .otherwise {
    count := count + 1.U
  }
  
  io.ready := (count === 3.U) // 2'b11
  io.count := count
  io.nd := nd
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}