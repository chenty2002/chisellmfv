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
class Controller(startWithToken: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val req = Input(Bool())
    val sel = Input(Selection())
    val id = Input(Selection())
    val ack = Output(Bool())
    val pass_token = Output(Bool())
  })
  
  val state = RegInit(ControllerState.IDLE)
  val ackReg = RegInit(false.B)
  val passTokenReg = RegInit(startWithToken.B)
  
  val isSelected = (io.sel === io.id)
  
  io.ack := ackReg
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

// Main module
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
  
  // Instantiate controllers
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
  
  // ========== FORMAL VERIFICATION ASSERTIONS ==========
  
  // ---- Safety: Mutual exclusion ----
  // At most one controller can assert ack at a time (critical token integrity)
  assertMutex(Seq(io.ackA, io.ackB, io.ackC), "mutex_acks")
  
  // At most one controller can pass the token at a time
  assertMutex(Seq(io.pass_tokenA, io.pass_tokenB, io.pass_tokenC), "mutex_pass_tokens")
  
  // A controller should never simultaneously ack and pass the token
  fvAssert(!(io.ackA && io.pass_tokenA), "ack_and_pass_token_exclusive_A")
  fvAssert(!(io.ackB && io.pass_tokenB), "ack_and_pass_token_exclusive_B")
  fvAssert(!(io.ackC && io.pass_tokenC), "ack_and_pass_token_exclusive_C")
  
  // ---- Safety: Correctness of acknowledgment ----
  // Ack implies the client was actually requesting
  assertImplies(io.ackA, io.reqA, "ackA_requires_reqA")
  assertImplies(io.ackB, io.reqB, "ackB_requires_reqB")
  assertImplies(io.ackC, io.reqC, "ackC_requires_reqC")
  
  // Ack implies the arbiter selected the correct client
  assertImplies(io.ackA, io.sel === Selection.A, "ackA_requires_sel_A")
  assertImplies(io.ackB, io.sel === Selection.B, "ackB_requires_sel_B")
  assertImplies(io.ackC, io.sel === Selection.C, "ackC_requires_sel_C")
  
  // ---- Safety: No ack without an active arbitration ----
  // If any controller is acknowledging, the arbiter must be active
  fvAssert(!(io.ackA || io.ackB || io.ackC) || io.active, "ack_requires_active")
  
  // ---- Bounded liveness: every request is eventually acknowledged ----
  // When a client starts requesting (rising edge of req), an ack must come within N cycles.
  // N=50 provides a generous bound for round-robin through up to 3 clients,
  // each potentially holding the token for some cycles.
  val reqARose = io.reqA && !RegNext(io.reqA, false.B)
  val reqBRose = io.reqB && !RegNext(io.reqB, false.B)
  val reqCRose = io.reqC && !RegNext(io.reqC, false.B)
  
  astRelaxedLiveness(reqARose, io.ackA, 50, "liveness_clientA")
  astRelaxedLiveness(reqBRose, io.ackB, 50, "liveness_clientB")
  astRelaxedLiveness(reqCRose, io.ackC, 50, "liveness_clientC")
}

// Verilog generator
object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}
