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
  val passTokenReg = RegInit(true.B)
  
  io.ack := ackReg
  io.passToken := passTokenReg
  
  val isSelected = (io.sel === id)
  
  switch(state) {
    is(ControllerState.IDLE) {
      when(isSelected) {
        when(io.req) {
          state := ControllerState.READY
          passTokenReg := false.B // dropping off this line causes a safety bug
        }.otherwise {
          passTokenReg := true.B
        }
      }.otherwise {
        passTokenReg := false.B
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

  // ======================================================================
  // FORMAL VERIFICATION ASSERTIONS
  // ======================================================================

  // ----- SAFETY: Mutual Exclusion of Acknowledge Signals -----
  // Only one client should ever receive an ack at a time.
  // Two simultaneous acks would mean two clients hold the token.
  assertMutex(Seq(io.ackA, io.ackB, io.ackC), "mutex_ack")

  // ----- SAFETY: Mutual Exclusion of Token Passing -----
  // At most one controller passes the token at a time.
  // Multiple simultaneous passTokens would corrupt the round-robin order.
  assertMutex(Seq(io.passTokenA, io.passTokenB, io.passTokenC), "mutex_passToken")

  // ----- SAFETY: Valid Selection When Active -----
  // When the system is active (token is being passed), the arbiter
  // must output a valid client selection (A/B/C), not the idle X state.
  fvAssert(!io.active || (io.sel =/= Selection.X), "sel_valid_when_active")

  // ----- SAFETY: No Spurious Acknowledge -----
  // A controller must not assert ack unless its client is actually
  // requesting.  A spurious ack could corrupt the client state machine.
  fvAssert(!io.ackA || io.reqA, "ackA_implies_reqA")
  fvAssert(!io.ackB || io.reqB, "ackB_implies_reqB")
  fvAssert(!io.ackC || io.reqC, "ackC_implies_reqC")

  // ----- LIVENESS: Requests Eventually Get Acknowledged -----
  // When a client is asserting req AND the arbiter selects that
  // client, the controller must assert ack within 20 cycles.
  // The worst-case path is: controller stuck in BUSY waiting for
  // req to fall, then IDLE → wait for selection (≤3 cycles) →
  // READY (1 cycle) → BUSY/ack (1 cycle).  20 cycles is generous.
  astRelaxedLiveness(
    io.reqA && (io.sel === Selection.A),
    io.ackA,
    20,
    "reqA_eventually_ackA"
  )
  astRelaxedLiveness(
    io.reqB && (io.sel === Selection.B),
    io.ackB,
    20,
    "reqB_eventually_ackB"
  )
  astRelaxedLiveness(
    io.reqC && (io.sel === Selection.C),
    io.ackC,
    20,
    "reqC_eventually_ackC"
  )

  // ----- LIVENESS: Token Passing Progress -----
  // When the arbiter is active, the selection should not get stuck
  // on X.  At least one passToken is true, so the arbiter should
  // cycle and produce valid selections.
  // A stuck-X state would mean deadlock.
  assertLivenessTimer(
    io.sel === Selection.X,
    !io.active,
    5,
    "sel_X_stuck_timer"
  )
}

// Object to generate Verilog
object VerilogGenerator extends App {
  emitVerilog(new Main(), Array("--target-dir", "generated"))
}
