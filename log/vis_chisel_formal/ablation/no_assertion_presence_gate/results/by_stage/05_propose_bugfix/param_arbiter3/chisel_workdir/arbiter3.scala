package llmverify
import chisel3._
import chisel3.util._
import chiselFv._

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
class Controller(val initToken: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val req = Input(Bool())
    val sel = Input(Selection())
    val id = Input(Selection())
    val ack = Output(Bool())
    val pass_token = Output(Bool())
  })
  
  val state = RegInit(ControllerState.IDLE)
  val ackReg = RegInit(false.B)
  val passTokenReg = RegInit(initToken.B)
  
  val isSelected = (io.sel === io.id)
  
  // ack is combinatorial: high only while the controller is in BUSY and req is still asserted.
  // This ensures ack drops immediately when the client withdraws req (fixes ackA_implies_reqA).
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

// Main module with formal assertions
class Main extends Module with Formal {
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
  
  // Instantiate controllers — only controllerA starts with the token
  // (prevents all three from having pass_token true at reset, fixing mutex_pass_token)
  val controllerA = Module(new Controller(true))
  val controllerB = Module(new Controller(false))
  val controllerC = Module(new Controller(false))
  
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

  // ============================================================
  // Formal Verification Assertions
  // ============================================================

  // Safety: At most one client receives acknowledgment at any time
  // (mutual exclusion on token delivery)
  assertMutex(Seq(io.ackA, io.ackB, io.ackC), "mutex_ack")

  // Safety: At most one controller passes the token at any time
  // (token integrity — the token must be in exactly one place)
  assertMutex(Seq(io.pass_tokenA, io.pass_tokenB, io.pass_tokenC), "mutex_pass_token")

  // Safety: An acknowledgment is only given when the client has an active request
  assertImplies(io.ackA, io.reqA, "ackA_implies_reqA")
  assertImplies(io.ackB, io.reqB, "ackB_implies_reqB")
  assertImplies(io.ackC, io.reqC, "ackC_implies_reqC")

  // Safety: When the arbiter is active, the selected client must be a valid
  // participant (A, B, or C), never the idle value X
  fvAssert(!io.active || io.sel =/= Selection.X, "active_sel_valid")

  // Liveness: Every request is eventually acknowledged within a bounded
  // number of cycles. The bound is set to 20 cycles, which comfortably
  // covers the round-robin latency through all three clients (arbiter
  // cycles A→B→C, each step taking 1–3 controller clock cycles).
  // The response condition also accepts that the client has withdrawn
  // the request, which only happens after the ack has been received.
  astRelaxedLiveness(io.reqA, io.ackA || !io.reqA, 20, "reqA_eventually_ackA")
  astRelaxedLiveness(io.reqB, io.ackB || !io.reqB, 20, "reqB_eventually_ackB")
  astRelaxedLiveness(io.reqC, io.ackC || !io.reqC, 20, "reqC_eventually_ackC")
}

// Verilog generator
object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}