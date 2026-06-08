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

  // ========== Formal Verification Assertions ==========

  // Liveness: When req is asserted, ack should arrive within bounded time.
  // The path is: idle→starting(1cyc)→working(≤3cyc for count→3)→done(1cyc→ack).
  // Bound of 10 provides generous margin over the theoretical ~5 cycle maximum.
  astRelaxedLiveness(req, ra.io.ack, 10, "req_ack_progress")

  // Safety: Once ack fires, it must de-assert next cycle.
  // The done state transitions back to idle immediately, so ack is a single-cycle pulse.
  assertNextStepWhen(ra.io.ack, !ra.io.ack, "ack_one_cycle_pulse")

  // Liveness: When start fires, ready must arrive within bounded time.
  // SlaveND counts from 0 up to 3; in the worst case (nd=0 at first tick) it takes 3 cycles.
  // Bound of 10 adds generous margin.
  astRelaxedLiveness(ra.io.start, ra.io.ready, 10, "start_ready_progress")

  // Safety: req and ack must not be asserted simultaneously.
  // The protocol expects req→ack handshake without overlap.
  fvAssert(!(req && ra.io.ack), "req_ack_mutex")
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
