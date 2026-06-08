package llmverify

import chisel3._
import chisel3.util._
import chisel3.ltl._
import chisel3.ltl.Sequence._

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
class Main extends Module {
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

  // =================================================================
  // Formal Verification Assertions
  // =================================================================
  // All assertions are placed directly in the Main module, which is the
  // DUT emitted by VerilogGenerator. These assertions verify critical
  // safety and liveness properties of the token-passing arbiter.
  // =================================================================

  // Safety 1: Token mutex — at most one controller may pass a token at any time.
  // This prevents double-grant and ensures the token-passing protocol is sound.
  AssertProperty(
    !(io.passTokenA && io.passTokenB) &&
    !(io.passTokenA && io.passTokenC) &&
    !(io.passTokenB && io.passTokenC),
    None, None, Some("passTokenMutex")
  )

  // Safety 2: Acknowledge only when the corresponding client has an active
  // request.  This ensures the controller handshake follows the protocol:
  // the controller must not grant a token to a client that did not ask for one.
  AssertProperty(
    !(io.ackA && !io.reqA) &&
    !(io.ackB && !io.reqB) &&
    !(io.ackC && !io.reqC),
    None, None, Some("ackImpliesReq")
  )

  // Safety 3: When the arbiter is active, the selection output must be a valid
  // client (A, B, or C) and not the idle value X.  An X selection during
  // activity would mean no client is being serviced, breaking progress.
  AssertProperty(
    !io.active || (io.sel === Selection.A || io.sel === Selection.B || io.sel === Selection.C),
    None, None, Some("selValidWhenActive")
  )

  // Liveness: When a client has a pending request and the system is active
  // (at least one token is being passed), an acknowledgment must arrive
  // within a bounded number of cycles.
  //
  // Justification for bound = 15:
  //   - The arbiter cycles A->B->C->A (3 states)
  //   - A controller takes 2 cycles (IDLE->READY->BUSY) to assert ack
  //   - Worst case: client just missed its slot, waits for a full cycle
  //     (3 state transitions) + 2 controller cycles = 5 cycles
  //   - Bound 15 provides generous margin for the random token-release
  //     and arbitration delays.
  AssertProperty(
    (io.reqA && io.active) |-> Sequence(io.ackA).delayRange(1, 15),
    None, None, Some("liveness_clientA_gets_ack")
  )
  AssertProperty(
    (io.reqB && io.active) |-> Sequence(io.ackB).delayRange(1, 15),
    None, None, Some("liveness_clientB_gets_ack")
  )
  AssertProperty(
    (io.reqC && io.active) |-> Sequence(io.ackC).delayRange(1, 15),
    None, None, Some("liveness_clientC_gets_ack")
  )
}

// Object to generate Verilog
object VerilogGenerator extends App {
  emitVerilog(new Main(), Array("--target-dir", "generated"))
}
