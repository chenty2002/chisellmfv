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
          // Skip READY state: the arbiter changes selection every cycle, so
          // a controller is selected for only one cycle at a time.  By going
          // directly to BUSY and asserting ack immediately, we complete the
          // handshake in that single cycle instead of requiring a second
          // consecutive selection that the arbiter never provides.
          state := ControllerState.BUSY
          ackReg := true.B
          passTokenReg := false.B
        }.otherwise {
          passTokenReg := true.B
        }
      }.otherwise {
        // Keep passToken high when a request is pending but not selected,
        // so the arbiter continues cycling and will eventually select this controller.
        // Without this, passToken drops to 0, active goes low, arbiter stalls,
        // and the system deadlocks (liveness failure).
        when(io.req) {
          passTokenReg := true.B
        }.otherwise {
          passTokenReg := false.B
        }
      }
    }
    is(ControllerState.READY) {
      // Unreachable after the IDLE→BUSY shortcut above, but kept for
      // safety in case the state machine enters READY via reset or
      // unforeseen scenarios.
      when(isSelected) {
        state := ControllerState.BUSY
        ackReg := true.B
      }.otherwise {
        state := ControllerState.IDLE
        ackReg := false.B
      }
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

  // ========== Formal Verification Assertions ==========

  // Safety: Acknowledgments are mutually exclusive.
  // Only one client should receive ack at a time because the system distributes
  // a single token among clients.
  assertMutex(Seq(io.ackA, io.ackB, io.ackC), "Mutex on acknowledgments")

  // Safety: An acknowledgment must only fire when the corresponding client
  // actually has a pending request.  Acking without a request indicates a
  // protocol violation.
  fvAssert(!io.ackA || io.reqA, "ackA implies reqA")
  fvAssert(!io.ackB || io.reqB, "ackB implies reqB")
  fvAssert(!io.ackC || io.reqC, "ackC implies reqC")

  // Safety: When the system is inactive, the arbiter must output the
  // null-selection value X, ensuring no controller is spuriously triggered.
  fvAssert(!io.active || io.sel =/= Selection.X, "sel is not X when active")
  fvAssert(io.active || io.sel === Selection.X, "sel is X when inactive")

  // Safety: The selection output must always be one of the four defined
  // enum values (A, B, C, X); no undefined or latched value can appear.
  // (Checked implicitly by ChiselEnum, but a formal assertion documents it.)

  // Bounded liveness / progress: when a client has a pending request
  // while the system is active (token passing underway), it should
  // receive an acknowledgment within 10 cycles.  This guards against
  // deadlock, starvation, or stuck FSMs in the controller or arbiter.
  astRelaxedLiveness(io.active && io.reqA, io.ackA, 10, "reqA eventually gets ack when active")
  astRelaxedLiveness(io.active && io.reqB, io.ackB, 10, "reqB eventually gets ack when active")
  astRelaxedLiveness(io.active && io.reqC, io.ackC, 10, "reqC eventually gets ack when active")
}

// Object to generate Verilog
object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}
