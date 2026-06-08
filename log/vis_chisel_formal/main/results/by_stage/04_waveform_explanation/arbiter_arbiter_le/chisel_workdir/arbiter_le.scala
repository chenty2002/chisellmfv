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

  // ----- Safety: Mutual exclusion -----
  // At most one client can receive an ack in any cycle
  fvAssert(PopCount(Seq(io.ackA, io.ackB, io.ackC)) <= 1.U, "mutex_ack")

  // ----- Safety: No phantom acknowledgments -----
  // An ack must only be issued when the corresponding client's request is active
  fvAssert(!io.ackA || io.reqA, "ackA_implies_reqA")
  fvAssert(!io.ackB || io.reqB, "ackB_implies_reqB")
  fvAssert(!io.ackC || io.reqC, "ackC_implies_reqC")

  // ----- Safety: Selection validity when active -----
  // When the arbiter is active, the selection must be one of the valid client IDs (not X)
  fvAssert(!io.active || io.sel =/= Selection.X, "active_sel_not_X")

  // ----- Safety: Ack requires matching prior selection -----
  // If a controller issues an ack, its id must have matched the arbiter selection
  // in the previous cycle (since Controller goes through READY first), or more
  // precisely the controller must have been selected when it started the handshake.
  // The key invariant: ack asserts only when the controller is in READY -> BUSY
  // transition, which follows from the controller being selected with a request.
  // We check the stronger property: pass_tokenA being consumed leads to ackA.
  // Actually the most direct check: if ackA is true, the arbiter sel must have
  // selected A at some recent point. We verify that ack is not spurious by
  // cross-checking with pass_token: when ackA fires, controllerA must have had
  // the token (pass_tokenA was true in a prior cycle, since Controller transitions
  // from IDLE with is_selected to READY then BUSY where ack is set).
  //
  // Verifying: ack should not fire more than once per pass_token grant cycle.
  // Since the controller only asserts ack once during the BUSY->IDLE transition,
  // and ack stays high as long as req stays high (until !io.req in BUSY state),
  // we check that ack and pass_token are never both true simultaneously
  // (ack consumes the token opportunity).
  fvAssert(!io.ackA || !io.pass_tokenA, "ackA_not_with_pass_tokenA")
  fvAssert(!io.ackB || !io.pass_tokenB, "ackB_not_with_pass_tokenB")
  fvAssert(!io.ackC || !io.pass_tokenC, "ackC_not_with_pass_tokenC")

  // ----- Liveness: Requesting clients eventually get ack -----
  // When a client has a request and is selected by the arbiter, it should
  // receive an acknowledgment within a bounded number of cycles.
  // The round-robin arbiter cycles through A->B->C, so worst case is:
  // client waits for 2 other selections + 1 for itself + controller pipeline.
  // Bound of 8 cycles is more than sufficient for the full round-trip.
  astRelaxedLiveness(io.reqA && io.sel === Selection.A && io.active, io.ackA, 8, "reqA_selA_eventually_ackA")
  astRelaxedLiveness(io.reqB && io.sel === Selection.B && io.active, io.ackB, 8, "reqB_selB_eventually_ackB")
  astRelaxedLiveness(io.reqC && io.sel === Selection.C && io.active, io.ackC, 8, "reqC_selC_eventually_ackC")

  // ----- Liveness: Round-robin fairness -----
  // When active, selection must cycle through all clients.
  // The arbiter cycles A->B->C->A when io.active is true.
  // Use a liveness timer: active should not stay continuously true
  // without a new ack being issued (system should make progress).
  assertLivenessTimer(io.active && !io.ackA && !io.ackB && !io.ackC, !io.active, 20, "active_stuck_no_ack")
}

object VerilogGenerator extends App {
  emitVerilog(new ArbiterLE(), args)
}
