package llmverify
import chisel3._
import chisel3.util._
import chisel3.ltl._
import chisel3.ltl.Sequence._

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

class Main extends Module {
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
  
  // ---------------------------------------------------------------------------
  // Formal Verification Assertions (Chisel 6 LTL)
  // ---------------------------------------------------------------------------
  
  // Bounded Liveness: When req is asserted, ack must fire within 1500 cycles.
  // req |-> ##[1:1500] ack
  // Worst-case path: idle -> starting (1 cyc) -> working (1 cyc) ->
  //   wait for slave count to reach 1023 (~1024 cyc) -> done (1 cyc) -> ack
  // Total worst case ~1027 cycles; bound of 1500 provides safe margin.
  AssertProperty(
    req |-> Sequence(ack).delayRange(1, 1500),
    None,
    None,
    Some("req_ack_bounded_liveness")
  )
  
  // Safety: ack must be a single-cycle pulse.
  // The done state lasts exactly one cycle before transitioning back to idle.
  AssertProperty(!ack || !RegNext(ack), None, None, Some("ack_is_single_cycle_pulse"))
  
  // Safety: The 8-bit LFSR must never enter the all-zeros state,
  // which would lock the random request generation.
  AssertProperty(lfsr =/= 0.U(8.W), None, None, Some("lfsr_never_zero"))
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}
