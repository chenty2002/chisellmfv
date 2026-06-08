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

  // Safety: At most one acknowledgment active at any time
  fvAssert(PopCount(Seq(io.ackA, io.ackB, io.ackC)) <= 1.U, "mutex_ack")

  // Safety: An acknowledgment implies the corresponding client is requesting
  fvAssert(!io.ackA || io.reqA, "ackA_implies_reqA")
  fvAssert(!io.ackB || io.reqB, "ackB_implies_reqB")
  fvAssert(!io.ackC || io.reqC, "ackC_implies_reqC")

  // Safety: When active, the arbitration selection must be a valid client (A, B, or C, never X)
  fvAssert(!io.active || io.sel =/= Selection.X, "active_sel_valid")

  // Safety: At most one controller passes the token at any time (mutual exclusion)
  fvAssert(PopCount(Seq(io.pass_tokenA, io.pass_tokenB, io.pass_tokenC)) <= 1.U, "mutex_pass_token")

  // Liveness: When a client asserts a request, it must be acknowledged within 20 cycles
  // The bound of 20 is chosen to cover worst-case round-robin (3 clients × 2 cycles each + processing)
  astRelaxedLiveness(io.reqA, io.ackA, 20, "reqA_eventually_ackA")
  astRelaxedLiveness(io.reqB, io.ackB, 20, "reqB_eventually_ackB")
  astRelaxedLiveness(io.reqC, io.ackC, 20, "reqC_eventually_ackC")
}

object VerilogGenerator extends App {
  emitVerilog(new ArbiterLE(), args)
}