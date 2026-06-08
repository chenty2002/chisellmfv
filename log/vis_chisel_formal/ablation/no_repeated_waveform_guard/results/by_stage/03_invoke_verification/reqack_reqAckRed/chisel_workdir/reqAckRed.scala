package llmverify
import chisel3._
import chisel3.util._
import chiselFv._

class Main extends Module with Formal {
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
  
  // === Formal Assertions ===
  // Bounded liveness: when req rises (0->1), ack must be asserted within 300 cycles
  // This verifies the full request-acknowledge handshake completes in bounded time
  val reqRise = io.req && !RegNext(io.req)
  astRelaxedLiveness(reqRise, io.ack, 300, "req_ack_bounded_liveness")
}

class ReqAck extends Module with Formal {
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
  
  // === Formal Assertions ===
  // Safety: ack is only asserted in the done state
  fvAssert(io.ack === (state === done), "ack_only_in_done")
  // Safety: start is only asserted in the starting state
  fvAssert(io.start === (state === starting), "start_only_in_starting")
  // Safety: FSM always occupies a valid enumerated state
  fvAssert(state === idle || state === starting || state === working || state === done, "valid_fsm_state")
}

class SlaveND extends Module with Formal {
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
  
  // === Formal Assertions ===
  // Safety: ready is only asserted exactly when count reaches 3
  fvAssert(io.ready === (io.count === 3.U), "ready_when_count_3")
  // Safety: count never exceeds the 2-bit binary maximum value
  fvAssert(io.count <= 3.U, "count_max_3")
  // Bounded liveness: when start fires, ready must be asserted within 260 cycles
  // This ensures SlaveND always makes forward progress and does not hang
  astRelaxedLiveness(io.start, io.ready, 260, "start_ready_bounded_liveness")
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}
