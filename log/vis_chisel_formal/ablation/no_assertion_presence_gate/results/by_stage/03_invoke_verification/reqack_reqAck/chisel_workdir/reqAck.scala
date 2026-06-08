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

class ReqAck extends Module {
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
  
  // === Formal Verification Assertions ===
  
  // 1. Bounded liveness: when req is asserted, ack must follow within 1500 cycles.
  //    Max path through state machine: idle -> starting (1 cycle) -> working (~1024 cycles) -> done (1 cycle)
  //    Total = ~1027 cycles, so 1500 provides sufficient margin.
  astRelaxedLiveness(req, ack, 1500, "req_eventually_ack")
  
  // 2. Safety: ack is never asserted in two consecutive cycles.
  //    The done state transitions immediately back to idle, so ack stays high for exactly one cycle.
  fvAssert(!(ack && RegNext(ack)), "ack_no_consecutive")
  
  // 3. Safety: ack only fires when there is a pending request that has not yet been acknowledged.
  //    Track whether a request is outstanding.
  val pending = RegInit(false.B)
  when(req && !pending) { pending := true.B }
  when(ack) { pending := false.B }
  fvAssert(!ack || pending, "no_ack_without_pending_request")
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}
