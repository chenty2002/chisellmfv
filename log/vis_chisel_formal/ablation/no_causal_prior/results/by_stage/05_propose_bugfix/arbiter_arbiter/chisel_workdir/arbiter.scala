package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

// Enums for the system
object Selection extends ChiselEnum {
  val A, B, C, X = Value
}

object ControllerState extends ChiselEnum {
  val IDLE, READY, BUSY = Value
}

object ClientState extends ChiselEnum {
  val NO_REQ, REQ, HAVE_TOKEN = Value
}

// Client module - generates requests randomly
class Client extends Module {
  val io = IO(new Bundle {
    val ack = Input(Bool())
    val req = Output(Bool())
  })
  
  val state = RegInit(ClientState.NO_REQ)
  val reqReg = RegInit(false.B)
  
  // Simple pseudo-random choice using a counter
  val randCounter = RegInit(0.U(8.W))
  randCounter := randCounter + 1.U
  val randChoice = randCounter(0) // Use LSB as random bit
  
  io.req := reqReg
  
  switch(state) {
    is(ClientState.NO_REQ) {
      when(randChoice) {
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
      when(randChoice) {
        reqReg := false.B
        state := ClientState.NO_REQ
      }
    }
  }
}

// Controller module - handles handshake between client and arbiter
class Controller(id: Selection.Type) extends Module {
  val io = IO(new Bundle {
    val req = Input(Bool())
    val ack = Output(Bool())
    val sel = Input(Selection())
    val passToken = Output(Bool())
  })
  
  val state = RegInit(ControllerState.IDLE)
  val ackReg = RegInit(false.B)
  // Only controllerA starts with the token; others start without it.
  // This ensures mutual exclusion of passToken signals at reset.
  val passTokenReg = RegInit(if (id == Selection.A) true.B else false.B)
  
  io.ack := ackReg
  io.passToken := passTokenReg
  
  val isSelected = (io.sel === id)
  
  switch(state) {
    is(ControllerState.IDLE) {
      when(isSelected) {
        when(io.req) {
          state := ControllerState.READY
          passTokenReg := false.B // consuming token for servicing
        }.otherwise {
          passTokenReg := true.B // keep token if no request
        }
      }
      // When not selected: passTokenReg retains its previous value.
      // The token (bus ownership) persists until intentionally consumed
      // (when servicing a request) or passed (after servicing completes).
      // Clearing the token when not selected would destroy it, causing
      // all passToken signals to become zero simultaneously, which
      // freezes the arbiter (active=0 → sel=X) and deadlocks the system.
    }
    is(ControllerState.READY) {
      state := ControllerState.BUSY
      ackReg := true.B
    }
    is(ControllerState.BUSY) {
      // Clear ack immediately after the single-cycle assertion in READY.
      // The client latches ack on the cycle the controller transitions
      // READY→BUSY (ackReg is set in READY, read by client on the next clock
      // edge), so ack only needs to be high for one cycle.  Clearing it
      // unconditionally here prevents the one-cycle window where ack remains
      // asserted after the client has already dropped req (due to the
      // register-update semantic delay).
      ackReg := false.B
      when(!io.req) {
        state := ControllerState.IDLE
        passTokenReg := true.B
      }
    }
  }
}

// Arbiter module - cycles through clients when active
class Arbiter extends Module {
  val io = IO(new Bundle {
    val active = Input(Bool())
    val sel = Output(Selection())
  })
  
  val state = RegInit(Selection.A)
  
  io.sel := Mux(io.active, state, Selection.X)
  
  when(io.active) {
    switch(state) {
      is(Selection.A) {
        state := Selection.B
      }
      is(Selection.B) {
        state := Selection.C
      }
      is(Selection.C) {
        state := Selection.A
      }
      is(Selection.X) {
        // Should not happen when active, but keep as is
      }
    }
  }
}

// Main module - connects all components
class Main extends Module with Formal {
  val io = IO(new Bundle {
    val ackA = Output(Bool())
    val ackB = Output(Bool())
    val ackC = Output(Bool())
    // Additional outputs to preserve internal signals
    val reqA = Output(Bool())
    val reqB = Output(Bool())
    val reqC = Output(Bool())
    val sel = Output(Selection())
    val active = Output(Bool())
    val passTokenA = Output(Bool())
    val passTokenB = Output(Bool())
    val passTokenC = Output(Bool())
  })
  
  // Instantiate clients
  val clientA = Module(new Client())
  val clientB = Module(new Client())
  val clientC = Module(new Client())
  
  // Instantiate controllers
  val controllerA = Module(new Controller(Selection.A))
  val controllerB = Module(new Controller(Selection.B))
  val controllerC = Module(new Controller(Selection.C))
  
  // Instantiate arbiter
  val arbiterModule = Module(new Arbiter())
  
  // Connect clients to controllers
  controllerA.io.req := clientA.io.req
  clientA.io.ack := controllerA.io.ack
  
  controllerB.io.req := clientB.io.req
  clientB.io.ack := controllerB.io.ack
  
  controllerC.io.req := clientC.io.req
  clientC.io.ack := controllerC.io.ack
  
  // Connect controllers to arbiter
  val selWire = arbiterModule.io.sel
  controllerA.io.sel := selWire
  controllerB.io.sel := selWire
  controllerC.io.sel := selWire
  
  // Calculate active signal
  val activeWire = controllerA.io.passToken || controllerB.io.passToken || controllerC.io.passToken
  arbiterModule.io.active := activeWire
  
  // Connect outputs
  io.ackA := controllerA.io.ack
  io.ackB := controllerB.io.ack
  io.ackC := controllerC.io.ack
  
  // Additional outputs for debugging/preservation
  io.reqA := clientA.io.req
  io.reqB := clientB.io.req
  io.reqC := clientC.io.req
  io.sel := selWire
  io.active := activeWire
  io.passTokenA := controllerA.io.passToken
  io.passTokenB := controllerB.io.passToken
  io.passTokenC := controllerC.io.passToken

  // ==========================================================================
  // FORMAL ASSERTIONS
  // ==========================================================================

  // ---- Safety: Mutual Exclusion of Acks ----
  // At most one controller can acknowledge its client at any time.
  // Simultaneous acks would mean multiple clients receive the token, breaking the protocol.
  assertMutex(Seq(io.ackA, io.ackB, io.ackC), "mutex_acks")

  // ---- Safety: Ack Implies Request ----
  // A controller must only acknowledge a client that is actively requesting.
  // An ack without a request indicates a spurious grant.
  fvAssert(!io.ackA || io.reqA, "ackA_implies_reqA")
  fvAssert(!io.ackB || io.reqB, "ackB_implies_reqB")
  fvAssert(!io.ackC || io.reqC, "ackC_implies_reqC")

  // ---- Safety: Mutual Exclusion of PassTokens ----
  // At most one controller should be passing the token forward.
  // Multiple passTokens would indicate conflicting token ownership.
  assertMutex(Seq(io.passTokenA, io.passTokenB, io.passTokenC), "mutex_passTokens")

  // ---- Safety: Ack Excludes PassToken (same controller) ----
  // When a controller is servicing a request (ack asserted), its token
  // must be consumed (passToken false). This directly catches the bug
  // identified in the comment: "dropping off this line causes a safety bug".
  fvAssert(!io.ackA || !io.passTokenA, "ackA_excludes_passTokenA")
  fvAssert(!io.ackB || !io.passTokenB, "ackB_excludes_passTokenB")
  fvAssert(!io.ackC || !io.passTokenC, "ackC_excludes_passTokenC")

  // ---- Safety: Selected-with-Request Implies PassToken False ----
  // When a controller is selected by the arbiter AND its client has a request,
  // the controller must NOT pass the token (it consumes it for servicing).
  // This is a direct guard for the commented bug.
  fvAssert(!(io.sel === Selection.A && io.reqA) || !io.passTokenA, "selA_reqA_implies_not_passTokenA")
  fvAssert(!(io.sel === Selection.B && io.reqB) || !io.passTokenB, "selB_reqB_implies_not_passTokenB")
  fvAssert(!(io.sel === Selection.C && io.reqC) || !io.passTokenC, "selC_reqC_implies_not_passTokenC")

  // ---- Safety: Active Arbiter Never Selects X ----
  // When the token is actively being passed, the arbiter must emit a valid
  // selection (A, B, or C), never the invalid X state.
  fvAssert(!io.active || (io.sel =/= Selection.X), "active_implies_sel_not_X")

  // ---- Bounded Liveness: Requests Eventually Get Acks ----
  // Every client request must be acknowledged within a bounded number of cycles.
  // Bound of 15 chosen from state-space diameter: worst case waits for round-robin
  // arbiter to cycle through all 3 states (3 cycles) + controller processing
  // (2 cycles) + significant margin for pipeline delays.
  astRelaxedLiveness(io.reqA, io.ackA, 15, "reqA_eventually_ackA")
  astRelaxedLiveness(io.reqB, io.ackB, 15, "reqB_eventually_ackB")
  astRelaxedLiveness(io.reqC, io.ackC, 15, "reqC_eventually_ackC")
}

// Object to generate Verilog
object VerilogGenerator extends App {
  emitVerilog(new Main(), Array("--target-dir", "generated"))
}
