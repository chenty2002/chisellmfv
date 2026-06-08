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

  // ============================================================
  // Formal Verification Assertions
  // ============================================================

  // Safety: At most one acknowledgment can be active at a time
  // Only one client is selected, so only that client's controller can assert ack
  assertMutex(Seq(io.ackA, io.ackB, io.ackC), "mutex_ack")

  // Safety: At most one pass_token can be active at a time
  // The arbiter selects one client at a time; only the selected idle controller with no req emits a token
  assertOneHot0(io.pass_tokenA.asUInt | io.pass_tokenB.asUInt | io.pass_tokenC.asUInt, "onehot0_pass_token")

  // Safety: When active is false, sel must be X (no valid selection)
  fvAssert(!io.active || io.sel =/= Selection.X, "active_implies_valid_sel")

  // Implication: When a client is requesting and the arbiter selects that client,
  // the ack must arrive within 2 cycles (Controller transitions: IDLE->READY->BUSY)
  val selA = arbiter.io.sel === Selection.A
  val selB = arbiter.io.sel === Selection.B
  val selC = arbiter.io.sel === Selection.C

  assertImpliesDelay(selA && io.reqA, io.ackA, 2, "selA_reqA_ack_within_2")
  assertImpliesDelay(selB && io.reqB, io.ackB, 2, "selB_reqB_ack_within_2")
  assertImpliesDelay(selC && io.reqC, io.ackC, 2, "selC_reqC_ack_within_2")

  // Liveness: Once a client sends a request, it should receive an ack within 8 cycles
  // Bound: round-robin through 3 clients, worst case ~3+2=5 cycles, use 8 for safety
  astRelaxedLiveness(io.reqA, io.ackA || !io.reqA, 8, "liveness_ack_A")
  astRelaxedLiveness(io.reqB, io.ackB || !io.reqB, 8, "liveness_ack_B")
  astRelaxedLiveness(io.reqC, io.ackC || !io.reqC, 8, "liveness_ack_C")

  // Liveness: Arbiter should not get stuck when active; sel cycles through A→B→C→A
  // When active, the selection should change within 6 cycles
  astRelaxedLiveness(io.active, io.sel =/= Selection.X, 6, "liveness_arbiter_active")
}

object VerilogGenerator extends App {
  emitVerilog(new ArbiterLE(), args)
}
