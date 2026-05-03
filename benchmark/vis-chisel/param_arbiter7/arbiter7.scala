package llmverify

import chisel3._
import chisel3.util._

// Enum definitions
class Selection extends Bundle {
  val value = UInt(3.W)
}

object Selection {
  val A = 0.U(3.W)
  val B = 1.U(3.W)
  val C = 2.U(3.W)
  val D = 3.U(3.W)
  val E = 4.U(3.W)
  val F = 5.U(3.W)
  val G = 6.U(3.W)
  val X = 7.U(3.W)
}

object ControllerState {
  val IDLE = 0.U(2.W)
  val READY = 1.U(2.W)
  val BUSY = 2.U(2.W)
}

object ClientState {
  val NO_REQ = 0.U(2.W)
  val REQ = 1.U(2.W)
  val HAVE_TOKEN = 2.U(2.W)
}

// Controller module
class Controller extends Module {
  val io = IO(new Bundle {
    val req = Input(Bool())
    val ack = Output(Bool())
    val sel = Input(UInt(3.W))
    val pass_token = Output(Bool())
    val id = Input(UInt(3.W))
  })
  
  val state = RegInit(ControllerState.IDLE)
  val ack = RegInit(false.B)
  val pass_token = RegInit(true.B)
  
  val is_selected = (io.sel === io.id)
  
  io.ack := ack
  io.pass_token := pass_token
  
  switch(state) {
    is(ControllerState.IDLE) {
      when(is_selected) {
        when(io.req) {
          state := ControllerState.READY
          pass_token := false.B
        }.otherwise {
          pass_token := true.B
        }
      }.otherwise {
        pass_token := false.B
      }
    }
    is(ControllerState.READY) {
      state := ControllerState.BUSY
      ack := true.B
    }
    is(ControllerState.BUSY) {
      when(!io.req) {
        state := ControllerState.IDLE
        ack := false.B
        pass_token := true.B
      }
    }
  }
}

// Arbiter module
class Arbiter extends Module {
  val io = IO(new Bundle {
    val active = Input(Bool())
    val sel = Output(UInt(3.W))
  })
  
  val state = RegInit(Selection.A)
  
  io.sel := Mux(io.active, state, Selection.X)
  
  when(io.active) {
    switch(state) {
      is(Selection.A) { state := Selection.B }
      is(Selection.B) { state := Selection.C }
      is(Selection.C) { state := Selection.D }
      is(Selection.D) { state := Selection.E }
      is(Selection.E) { state := Selection.F }
      is(Selection.F) { state := Selection.G }
      is(Selection.G) { state := Selection.A }
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
  val req = RegInit(false.B)
  
  // Simple pseudo-random choice using LFSR
  val lfsr = RegInit(1.U(8.W))
  val rand_choice = lfsr(0)
  lfsr := Cat(lfsr(6), lfsr(5), lfsr(4), lfsr(3), lfsr(2), lfsr(1), lfsr(0), lfsr(6) ^ lfsr(5))
  
  io.req := req
  
  switch(state) {
    is(ClientState.NO_REQ) {
      when(rand_choice) {
        req := true.B
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
        req := false.B
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
    val ackD = Output(Bool())
    val ackE = Output(Bool())
    val ackF = Output(Bool())
    val ackG = Output(Bool())
    // Additional outputs for debugging and to preserve signals
    val sel = Output(UInt(3.W))
    val active = Output(Bool())
    val reqA = Output(Bool())
    val reqB = Output(Bool())
    val reqC = Output(Bool())
    val reqD = Output(Bool())
    val reqE = Output(Bool())
    val reqF = Output(Bool())
    val reqG = Output(Bool())
  })
  
  // Instantiate controllers
  val controllerA = Module(new Controller)
  val controllerB = Module(new Controller)
  val controllerC = Module(new Controller)
  val controllerD = Module(new Controller)
  val controllerE = Module(new Controller)
  val controllerF = Module(new Controller)
  val controllerG = Module(new Controller)
  
  // Instantiate clients
  val clientA = Module(new Client)
  val clientB = Module(new Client)
  val clientC = Module(new Client)
  val clientD = Module(new Client)
  val clientE = Module(new Client)
  val clientF = Module(new Client)
  val clientG = Module(new Client)
  
  // Instantiate arbiter
  val arbiter = Module(new Arbiter)
  
  // Connect controllers
  controllerA.io.id := Selection.A
  controllerB.io.id := Selection.B
  controllerC.io.id := Selection.C
  controllerD.io.id := Selection.D
  controllerE.io.id := Selection.E
  controllerF.io.id := Selection.F
  controllerG.io.id := Selection.G
  
  // Connect clients to controllers
  controllerA.io.req := clientA.io.req
  controllerB.io.req := clientB.io.req
  controllerC.io.req := clientC.io.req
  controllerD.io.req := clientD.io.req
  controllerE.io.req := clientE.io.req
  controllerF.io.req := clientF.io.req
  controllerG.io.req := clientG.io.req
  
  clientA.io.ack := controllerA.io.ack
  clientB.io.ack := controllerB.io.ack
  clientC.io.ack := controllerC.io.ack
  clientD.io.ack := controllerD.io.ack
  clientE.io.ack := controllerE.io.ack
  clientF.io.ack := controllerF.io.ack
  clientG.io.ack := controllerG.io.ack
  
  // Connect controllers to arbiter
  val sel = arbiter.io.sel
  controllerA.io.sel := sel
  controllerB.io.sel := sel
  controllerC.io.sel := sel
  controllerD.io.sel := sel
  controllerE.io.sel := sel
  controllerF.io.sel := sel
  controllerG.io.sel := sel
  
  // Calculate active signal
  val active = controllerA.io.pass_token || controllerB.io.pass_token || 
               controllerC.io.pass_token || controllerD.io.pass_token ||
               controllerE.io.pass_token || controllerF.io.pass_token ||
               controllerG.io.pass_token
  
  arbiter.io.active := active
  
  // Connect outputs
  io.ackA := controllerA.io.ack
  io.ackB := controllerB.io.ack
  io.ackC := controllerC.io.ack
  io.ackD := controllerD.io.ack
  io.ackE := controllerE.io.ack
  io.ackF := controllerF.io.ack
  io.ackG := controllerG.io.ack
  
  // Additional debugging outputs
  io.sel := sel
  io.active := active
  io.reqA := clientA.io.req
  io.reqB := clientB.io.req
  io.reqC := clientC.io.req
  io.reqD := clientD.io.req
  io.reqE := clientE.io.req
  io.reqF := clientF.io.req
  io.reqG := clientG.io.req
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}