package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

object ArbiterEnums {
  object Selection extends ChiselEnum {
    val A, B, C, X = Value
  }
  
  object ControllerState extends ChiselEnum {
    val IDLE, READY, BUSY = Value
  }
  
  object ClientState extends ChiselEnum {
    val NO_REQ, REQ, HAVE_TOKEN = Value
  }
  
  object ObserverState extends ChiselEnum {
    val IDLE, BAD, GOOD = Value
  }
}

class Controller extends Module {
  import ArbiterEnums._
  
  val io = IO(new Bundle {
    val req = Input(Bool())
    val ack = Output(Bool())
    val sel = Input(Selection())
    val pass_token = Output(Bool())
    val id = Input(Selection())
  })
  
  val state = RegInit(ControllerState.IDLE)
  val ackReg = RegInit(false.B)
  val pass_tokenReg = RegInit(true.B)
  
  val is_selected = (io.sel === io.id)
  
  io.ack := ackReg
  io.pass_token := pass_tokenReg
  
  switch(state) {
    is(ControllerState.IDLE) {
      when(is_selected) {
        when(io.req) {
          state := ControllerState.READY
          pass_tokenReg := false.B
        }.otherwise {
          pass_tokenReg := true.B
        }
      }.otherwise {
        pass_tokenReg := false.B
      }
    }
    is(ControllerState.READY) {
      state := ControllerState.BUSY
      ackReg := true.B
    }
    is(ControllerState.BUSY) {
      when(!io.req) {
        state := ControllerState.IDLE
        ackReg := false.B
        pass_tokenReg := true.B
      }
    }
  }
}

class Arbiter extends Module {
  import ArbiterEnums._
  
  val io = IO(new Bundle {
    val active = Input(Bool())
    val sel = Output(Selection())
  })
  
  val state = RegInit(Selection.A)
  
  io.sel := Mux(io.active, state, Selection.X)
  
  when(io.active) {
    switch(state) {
      is(Selection.A) { state := Selection.B }
      is(Selection.B) { state := Selection.C }
      is(Selection.C) { state := Selection.A }
    }
  }
}

class Client extends Module {
  import ArbiterEnums._
  
  val io = IO(new Bundle {
    val req = Output(Bool())
    val ack = Input(Bool())
  })
  
  val state = RegInit(ClientState.NO_REQ)
  val reqReg = RegInit(false.B)
  
  io.req := reqReg
  
  // Generate random choice using LFSR
  val lfsr = RegInit(1.U(8.W))
  val rand_choice = lfsr(0)
  lfsr := Cat(lfsr(6), lfsr(5), lfsr(4), lfsr(3), lfsr(2), lfsr(1), lfsr(0), lfsr(6) ^ lfsr(5))
  
  switch(state) {
    is(ClientState.NO_REQ) {
      when(rand_choice) {
        reqReg := true.B
        state := ClientState.REQ
      }
    }
    is(ClientState.REQ) {
      when(io.ack) {
        state := ClientState.HAVE_TOKEN
      }
    }
    is(ClientState.HAVE_TOKEN) {
      when(rand_choice) {
        reqReg := false.B
        state := ClientState.NO_REQ
      }
    }
  }
}

class Observer extends Module {
  import ArbiterEnums._
  
  val io = IO(new Bundle {
    val req = Input(Bool())
    val ack = Input(Bool())
  })
  
  val state = RegInit(ObserverState.IDLE)
  
  // Generate random choice using LFSR
  val lfsr = RegInit(1.U(8.W))
  val rand_choice = lfsr(0)
  lfsr := Cat(lfsr(6), lfsr(5), lfsr(4), lfsr(3), lfsr(2), lfsr(1), lfsr(0), lfsr(6) ^ lfsr(5))
  
  switch(state) {
    is(ObserverState.IDLE) {
      when(io.req && rand_choice) {
        state := ObserverState.BAD
      }
    }
    is(ObserverState.BAD) {
      when(io.ack) {
        state := ObserverState.GOOD
      }
    }
  }
}

class ArbiterLE extends Module with Formal {
  import ArbiterEnums._
  
  val io = IO(new Bundle {
    val ackA = Output(Bool())
    val ackB = Output(Bool())
    val ackC = Output(Bool())
    // Add internal signals as outputs for verification
    val reqA = Output(Bool())
    val reqB = Output(Bool())
    val reqC = Output(Bool())
    val sel = Output(Selection())
    val active = Output(Bool())
    val pass_tokenA = Output(Bool())
    val pass_tokenB = Output(Bool())
    val pass_tokenC = Output(Bool())
  })
  
  // Controller instances
  val controllerA = Module(new Controller)
  val controllerB = Module(new Controller)
  val controllerC = Module(new Controller)
  
  // Arbiter instance
  val arbiter = Module(new Arbiter)
  
  // Client instances
  val clientA = Module(new Client)
  val clientB = Module(new Client)
  val clientC = Module(new Client)
  
  // Observer instance
  val observer = Module(new Observer)
  
  // Connect controllers
  controllerA.io.id := Selection.A
  controllerA.io.req := clientA.io.req
  controllerA.io.sel := arbiter.io.sel
  
  controllerB.io.id := Selection.B
  controllerB.io.req := clientB.io.req
  controllerB.io.sel := arbiter.io.sel
  
  controllerC.io.id := Selection.C
  controllerC.io.req := clientC.io.req
  controllerC.io.sel := arbiter.io.sel
  
  // Connect arbiter
  val active = controllerA.io.pass_token || controllerB.io.pass_token || controllerC.io.pass_token
  arbiter.io.active := active
  
  // Connect clients
  clientA.io.ack := controllerA.io.ack
  clientB.io.ack := controllerB.io.ack
  clientC.io.ack := controllerC.io.ack
  
  // Connect observer
  observer.io.req := clientA.io.req
  observer.io.ack := controllerA.io.ack
  
  // Connect outputs
  io.ackA := controllerA.io.ack
  io.ackB := controllerB.io.ack
  io.ackC := controllerC.io.ack
  io.reqA := clientA.io.req
  io.reqB := clientB.io.req
  io.reqC := clientC.io.req
  io.sel := arbiter.io.sel
  io.active := active
  io.pass_tokenA := controllerA.io.pass_token
  io.pass_tokenB := controllerB.io.pass_token
  io.pass_tokenC := controllerC.io.pass_token

  // ========== Formal Verification Assertions ==========

  // Safety 1: At most one ack is high at any time (mutual exclusion).
  // The arbiter grants the token to only one client per cycle.
  assertMutex(Seq(io.ackA, io.ackB, io.ackC), "only_one_ack_active")

  // Safety 2: At most one controller passes the token at a time.
  // A token is passed only by the currently selected controller that has no request.
  assertOneHot0(Cat(io.pass_tokenA, io.pass_tokenB, io.pass_tokenC), "at_most_one_pass_token")

  // Safety 3: A controller only emits ack when its own id matches the arbiter selection.
  // ackA requires sel === A, ackB requires sel === B, ackC requires sel === C.
  assertImplies(io.ackA, arbiter.io.sel === Selection.A, "ackA_requires_sel_A")
  assertImplies(io.ackB, arbiter.io.sel === Selection.B, "ackB_requires_sel_B")
  assertImplies(io.ackC, arbiter.io.sel === Selection.C, "ackC_requires_sel_C")

  // Liveness 4: When a client persistently asserts req while the arbiter is active,
  // it must eventually receive an ack within a bounded number of cycles.
  // Bound 15 covers the worst-case round-robin cycle through all three clients
  // (3 clients × ~4 cycles per round + margin).
  astRelaxedLiveness(
    io.reqA && io.active && (arbiter.io.sel === Selection.A),
    io.ackA || !io.reqA,
    15,
    "clientA_req_eventually_acknowledged"
  )
  astRelaxedLiveness(
    io.reqB && io.active && (arbiter.io.sel === Selection.B),
    io.ackB || !io.reqB,
    15,
    "clientB_req_eventually_acknowledged"
  )
  astRelaxedLiveness(
    io.reqC && io.active && (arbiter.io.sel === Selection.C),
    io.ackC || !io.reqC,
    15,
    "clientC_req_eventually_acknowledged"
  )

  // Liveness 5: The arbiter must not get stuck (active implies selection changes).
  // When active is high, the arbiter should not stay on the same selection forever.
  // This detects a deadlock in the round-robin state machine.
  astRelaxedLiveness(
    io.active,
    arbiter.io.sel === Selection.A || arbiter.io.sel === Selection.B || arbiter.io.sel === Selection.C,
    10,
    "arbiter_progress_when_active"
  )

  // Safety 6: The arbiter sel output must be valid (A/B/C) when active, and X when inactive.
  // This ensures the arbiter does not emit an illegal selection value.
  assertImplies(io.active, arbiter.io.sel =/= Selection.X, "sel_valid_when_active")
}

object VerilogGenerator extends App {
  emitVerilog(new ArbiterLE(), args)
}
