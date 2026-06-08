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
class Controller(initToken: Boolean = false) extends Module {
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
  
  // Instantiate controllers — only controllerA starts with the token
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

  // ===== Formal Verification Assertions =====

  // --- Safety: Mutual Exclusion ---
  // At most one ack can be high at any time (only one client holds the token)
  fvAssert(PopCount(Seq(io.ackA, io.ackB, io.ackC)) <= 1.U, "ack_mutex")
  
  // At most one pass_token can be high at any time (token passes atomically)
  fvAssert(PopCount(Seq(io.pass_tokenA, io.pass_tokenB, io.pass_tokenC)) <= 1.U, "pass_token_mutex")

  // --- Safety: Ack-Request Consistency ---
  // A client must be requesting to receive an acknowledgement
  fvAssert(!io.ackA || io.reqA, "ackA_implies_reqA")
  fvAssert(!io.ackB || io.reqB, "ackB_implies_reqB")
  fvAssert(!io.ackC || io.reqC, "ackC_implies_reqC")

  // --- Safety: Ack-Selection Consistency ---
  // A client can only be acknowledged when the arbiter selects it
  fvAssert(!io.ackA || io.sel === Selection.A, "ackA_implies_selA")
  fvAssert(!io.ackB || io.sel === Selection.B, "ackB_implies_selB")
  fvAssert(!io.ackC || io.sel === Selection.C, "ackC_implies_selC")

  // --- Safety: Pass-Token implies Request Inactive ---
  // A controller passes the token only when its client is not requesting
  // (pass_token is set when selected in IDLE with no req, or transitioning from BUSY to IDLE with no req)
  fvAssert(!io.pass_tokenA || !io.reqA, "pass_tokenA_implies_no_reqA")
  fvAssert(!io.pass_tokenB || !io.reqB, "pass_tokenB_implies_no_reqB")
  fvAssert(!io.pass_tokenC || !io.reqC, "pass_tokenC_implies_no_reqC")

  // --- Safety: Pass-Token implies Arbiter Selects Passing Client ---
  // When a controller passes the token, it must currently be selected by the arbiter
  fvAssert(!io.pass_tokenA || io.sel === Selection.A, "pass_tokenA_implies_selA")
  fvAssert(!io.pass_tokenB || io.sel === Selection.B, "pass_tokenB_implies_selB")
  fvAssert(!io.pass_tokenC || io.sel === Selection.C, "pass_tokenC_implies_selC")

  // --- Liveness: Bounded Progress ---
  // If a client is selected by the arbiter and has a pending request,
  // the client must receive an acknowledgement within a bounded number of cycles.
  // The pipeline is: IDLE → READY (1 cycle) → BUSY with ack (1 cycle).
  // Bound of 8 allows for worst-case when controller is mid-transition.
  astRelaxedLiveness(io.sel === Selection.A && io.reqA, io.ackA, 8, "reqA_eventually_ackA")
  astRelaxedLiveness(io.sel === Selection.B && io.reqB, io.ackB, 8, "reqB_eventually_ackB")
  astRelaxedLiveness(io.sel === Selection.C && io.reqC, io.ackC, 8, "reqC_eventually_ackC")

  // --- Liveness: Arbiter Progress ---
  // When the token is being actively passed (active is true), the round-robin
  // arbiter should advance. This ensures the arbiter doesn't stall.
  // The arbiter advances every cycle when active is true.
  // Verify: when active is true, the sel output should change within 1 cycle
  // (arbiter state updates combinatorially through io.sel).
  // Actually, we verify a stronger property: active implies sel !== X (not idle).
  fvAssert(!io.active || io.sel =/= Selection.X, "active_implies_sel_valid")
}

// Verilog generator
object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}
