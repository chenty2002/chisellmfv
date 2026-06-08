package llmverify
import chisel3._
import chisel3.util._
import chiselFv._

class Slave extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val ready = Output(Bool())
  })
  
  val count = RegInit(0.U(10.W))
  
  when(io.start) {
    count := 0.U
  }.otherwise {
    count := count + 1.U
  }
  
  io.ready := count === 1023.U // 10'b1111111111
}

class ReqAck extends Module with Formal {
  val io = IO(new Bundle {
    val req = Input(Bool())
    val ack = Output(Bool())
  })
  
  // State definitions
  val idle :: starting :: working :: done :: Nil = Enum(4)
  val state = RegInit(idle)
  
  // Connect to slave module
  val slave = Module(new Slave)
  slave.io.start := (state === starting)
  val ready = slave.io.ready
  
  // State machine
  switch(state) {
    is(idle) {
      when(io.req) {
        state := starting
      }.otherwise {
        state := idle
      }
    }
    is(starting) {
      state := working
    }
    is(working) {
      when(ready) {
        state := done
      }.otherwise {
        state := working
      }
    }
    is(done) {
      state := idle
    }
  }
  
  io.ack := (state === done)

  // ---- Formal Verification Assertions ----

  // Safety: ack must only be asserted when in the done state
  fvAssert(io.ack === (state === done), "ack_only_in_done_state")

  // Safety: ack must not be asserted during working state
  fvAssert(!(state === working) || !io.ack, "no_ack_during_working")

  // Safety: ack must not be asserted during starting state
  fvAssert(!(state === starting) || !io.ack, "no_ack_during_starting")

  // Safety: ack must not be asserted during idle state
  fvAssert(!(state === idle) || !io.ack, "no_ack_during_idle")

  // Bounded liveness: once we enter the starting state (beginning request processing),
  // ack must be asserted within at most 1030 cycles.
  // Path: starting(1) + working(1024 cycles for slave to count to 1023) + done(1) = 1026 cycles minimum,
  // with some margin for the req to be sampled in idle state first.
  astRelaxedLiveness(state === starting, io.ack, 1030, "progress_from_starting_to_ack")

  // Safety: state should never be undefined (covers valid state encoding)
  // The 4 states cover all possible 2-bit values since Enum(4) produces 2-bit encoding.
  // But we assert that state is always one of the defined states.
  fvAssert(state === idle || state === starting || state === working || state === done, "state_in_valid_range")
}

class Main extends Module with Formal {
  val io = IO(new Bundle {
    val req = Output(Bool())
    val ack = Output(Bool())
  })
  
  // Use LFSR to simulate nondeterministic behavior
  val lfsr = RegInit(1.U(8.W))
  val nextLfsr = Cat(lfsr(0), lfsr(7), lfsr(6), lfsr(5), lfsr(4), lfsr(3), lfsr(2), lfsr(1))
  lfsr := nextLfsr
  
  val req = RegInit(false.B)
  req := lfsr(0) // Use one bit of LFSR as pseudo-random request
  
  // Instantiate reqAck module
  val reqAck = Module(new ReqAck)
  reqAck.io.req := req
  val ack = reqAck.io.ack
  
  io.req := req
  io.ack := ack

  // ---- Formal Verification Assertions ----

  // Safety: ack should never be asserted without req having been seen
  // (req must have fired at some point to get ack). The tightest coupling:
  // ack implies that the ReqAck state machine was triggered by a req.
  // If ack is high, the internal state must be done (which requires req to have been seen).
  // This is already covered in ReqAck but add a top-level view as well.

  // Bounded liveness at top level: when req is asserted, ack must eventually come
  // within a reasonable bound. Worst case: req sampled, then starting(1) + working(1024) + done(1) = 1026 cycles.
  // Add margin: 1100 cycles.
  astRelaxedLiveness(req, ack, 1100, "top_req_implies_eventual_ack")
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}
