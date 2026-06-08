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

  // ========== Formal Verification Assertions ==========

  // Safety: At most one client receives acknowledgment at a time (mutual exclusion)
  // This is critical for a token-passing arbiter — two simultaneous acks would corrupt the protocol
  assertMutex(Seq(io.ackA, io.ackB, io.ackC), "mutex_acks: at most one client acknowledged at a time")

  // Safety: At most one controller passes the token at a time (one-hot-0: could be zero)
  // The token must be exclusive to guarantee correct round-robin handoff
  assertOneHot0(Cat(io.passTokenC, io.passTokenB, io.passTokenA), "onehot0_passToken: at most one passToken active at a time")

  // Safety: When the arbiter is active, the selection must be a valid client (A/B/C), never X
  // X is only valid when the system is inactive (no token passed)
  fvAssert(!io.active || io.sel =/= Selection.X, "sel_valid_when_active: selection is A/B/C when active")

  // Liveness: A client with a pending request that is currently selected must be acknowledged
  // within a bounded number of cycles (bound 10 is generous: arbiter cycles through 3 states
  // and controller takes 2 cycles from READY to BUSY/ack)
  astRelaxedLiveness(io.reqA && io.sel === Selection.A, io.ackA, 10, "liveness_clientA: request selected -> ack within 10 cycles")
  astRelaxedLiveness(io.reqB && io.sel === Selection.B, io.ackB, 10, "liveness_clientB: request selected -> ack within 10 cycles")
  astRelaxedLiveness(io.reqC && io.sel === Selection.C, io.ackC, 10, "liveness_clientC: request selected -> ack within 10 cycles")
}

// Object to generate Verilog
object VerilogGenerator extends App {
  emitVerilog(new Main(), Array("--target-dir", "generated"))
}
