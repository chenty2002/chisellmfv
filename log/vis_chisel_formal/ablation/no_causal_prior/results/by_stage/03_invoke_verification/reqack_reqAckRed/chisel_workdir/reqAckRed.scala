package llmverify
import chisel3._
import chisel3.util._
import chiselFv._

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
  
  // ========== Formal Assertions ==========
  
  // Safety: ack is only asserted precisely in the done state
  fvAssert(io.ack === (state === done), "ack_only_in_done")
  
  // Safety: start is only asserted precisely in the starting state
  fvAssert(io.start === (state === starting), "start_only_in_starting")
  
  // Safety: state must always be one of the four valid states
  val validState = state === idle || state === starting || state === working || state === done
  fvAssert(validState, "state_in_valid_range")
  
  // Liveness: when a new request is seen (req asserted in idle), ack must eventually arrive
  // Pipeline: starting(1) -> working(up to 3 for slave) -> done(1) -> ack appears
  // Max total: 1 + 3 + 1 = 5 cycles, using 10 for margin
  astRelaxedLiveness(io.req && state === idle, io.ack, 10, "req_eventually_ack")
  
  // Liveness: when in working state, ready must eventually be asserted
  // After start, slave needs at most 3 cycles to assert ready
  astRelaxedLiveness(state === working, io.ready, 5, "working_eventually_ready")
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
  
  // ========== Formal Assertions ==========
  
  // Safety: ready is only asserted when count reaches 3
  fvAssert(io.ready === (count === 3.U), "ready_only_when_count_three")
  
  // Safety: count value is always within valid range (0-3 for 2-bit value)
  fvAssert(count <= 3.U, "count_in_range")
  
  // Liveness: after start is asserted, ready must eventually come (within 5 cycles)
  // Path: start -> count=0 -> count=0+nd -> count+1 -> count+1 -> count===3
  // Worst case: nd=0 first, so: 0->0->1->2->3 = 4 cycles after start
  astRelaxedLiveness(io.start, io.ready, 5, "start_eventually_ready")
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}
