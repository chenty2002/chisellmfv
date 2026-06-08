package llmverify
import chisel3._
import chisel3.util._
import chisel3.ltl._
import chisel3.ltl.Sequence._

// Define enums using ChiselEnum
object Selection extends ChiselEnum {
  val A, B, C, X = Value
}

object ControllerState extends ChiselEnum {
  val IDLE, READY, BUSY = Value
}

object ClientState extends ChiselEnum {
  val NO_REQ, REQ, HAVE_TOKEN = Value
}

// Controller module
class Controller extends Module {
  val io = IO(new Bundle {
    val req = Input(Bool())
    val sel = Input(Selection())
    val id = Input(Selection())
    val ack = Output(Bool())
    val pass_token = Output(Bool())
  })
  
  val state = RegInit(ControllerState.IDLE)
  val ackReg = RegInit(false.B)
  val passTokenReg = RegInit(true.B)
  
  val isSelected = (io.sel === io.id)
  
  // Gate ack with req so that the acknowledgement is withdrawn combinatorially
  // the moment the request drops, preventing a one-cycle stale ack mismatch.
  io.ack := ackReg && io.req
  io.pass_token := passTokenReg
  
  switch(state) {
    is(ControllerState.IDLE) {
      when(isSelected) {
        when(io.req) {
          state := ControllerState.READY
          passTokenReg := false.B
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

// Arbiter module
class Arbiter extends Module {
  val io = IO(new Bundle {
    val active = Input(Bool())
    val sel = Output(Selection())
  })
  
  val state = RegInit(Selection.A)
  
  // Always output the current state so that io.sel remains valid even when
  // active is low.  Previously the output was Mux(io.active, state, Selection.X),
  // which withdrew the selection as soon as all pass tokens cleared.  This
  // caused ackX_implies_selX to fail because a controller that had already
  // entered the READY→BUSY transition would assert ack while io.sel=X.
  io.sel := state
  
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
    }
  }
}

// Client module
class Client extends Module {
  val io = IO(new Bundle {
    val ack = Input(Bool())
    val req = Output(Bool())
  })
  
  val state = RegInit(ClientState.NO_REQ)
  val reqReg = RegInit(false.B)
  
  // Simple pseudo-random choice using LFSR
  val lfsr = RegInit(1.U(8.W))
  val randChoice = lfsr(0)
  lfsr := Cat(lfsr(6), lfsr(5), lfsr(4), lfsr(3), lfsr(2), lfsr(1), lfsr(0), lfsr(6) ^ lfsr(5))
  
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

// Main module
class Main extends Module {
  val io = IO(new Bundle {
    val ackA = Output(Bool())
    val ackB = Output(Bool())
    val ackC = Output(Bool())
    // Additional outputs to preserve internal signals
    val sel = Output(Selection())
    val active = Output(Bool())
    val reqA = Output(Bool())
    val reqB = Output(Bool())
    val reqC = Output(Bool())
    val pass_tokenA = Output(Bool())
    val pass_tokenB = Output(Bool())
    val pass_tokenC = Output(Bool())
  })
  
  // Instantiate controllers
  val controllerA = Module(new Controller)
  val controllerB = Module(new Controller)
  val controllerC = Module(new Controller)
  
  // Instantiate arbiter
  val arbiter = Module(new Arbiter)
  
  // Instantiate clients
  val clientA = Module(new Client)
  val clientB = Module(new Client)
  val clientC = Module(new Client)
  
  // Connect controllers
  controllerA.io.req := clientA.io.req
  controllerA.io.sel := arbiter.io.sel
  controllerA.io.id := Selection.A
  
  controllerB.io.req := clientB.io.req
  controllerB.io.sel := arbiter.io.sel
  controllerB.io.id := Selection.B
  
  controllerC.io.req := clientC.io.req
  controllerC.io.sel := arbiter.io.sel
  controllerC.io.id := Selection.C
  
  // Connect clients
  clientA.io.ack := controllerA.io.ack
  clientB.io.ack := controllerB.io.ack
  clientC.io.ack := controllerC.io.ack
  
  // Connect arbiter
  val active = controllerA.io.pass_token || controllerB.io.pass_token || controllerC.io.pass_token
  arbiter.io.active := active
  
  // Connect outputs
  io.ackA := controllerA.io.ack
  io.ackB := controllerB.io.ack
  io.ackC := controllerC.io.ack
  io.sel := arbiter.io.sel
  io.active := active
  io.reqA := clientA.io.req
  io.reqB := clientB.io.req
  io.reqC := clientC.io.req
  io.pass_tokenA := controllerA.io.pass_token
  io.pass_tokenB := controllerB.io.pass_token
  io.pass_tokenC := controllerC.io.pass_token

  // ===== FORMAL ASSERTIONS =====

  // Safety: at most one ack at a time (mutual exclusion)
  AssertProperty(!(io.ackA && io.ackB) && !(io.ackA && io.ackC) && !(io.ackB && io.ackC), "ack_mutex")

  // Safety: ack only fires when the corresponding client is requesting
  AssertProperty(!io.ackA || io.reqA, "ackA_implies_reqA")
  AssertProperty(!io.ackB || io.reqB, "ackB_implies_reqB")
  AssertProperty(!io.ackC || io.reqC, "ackC_implies_reqC")

  // Safety: ack only fires when the arbiter has selected that client
  AssertProperty(!io.ackA || (io.sel === Selection.A), "ackA_implies_selA")
  AssertProperty(!io.ackB || (io.sel === Selection.B), "ackB_implies_selB")
  AssertProperty(!io.ackC || (io.sel === Selection.C), "ackC_implies_selC")

  // Bounded liveness: if a client is requesting, it must receive ack within 30 cycles
  // The bound is generous: the round-robin cycles through 3 clients, and each
  // selected client may hold the arbiter for multiple cycles before releasing.
  AssertProperty(io.reqA |-> Sequence(io.ackA).delayRange(1, 30), None, None, Some("reqA_ackA_liveness"))
  AssertProperty(io.reqB |-> Sequence(io.ackB).delayRange(1, 30), None, None, Some("reqB_ackB_liveness"))
  AssertProperty(io.reqC |-> Sequence(io.ackC).delayRange(1, 30), None, None, Some("reqC_ackC_liveness"))
}

// Verilog generator
object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}
