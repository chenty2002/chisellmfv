package llmverify

import chisel3._
import chisel3.util._
import scala.util.Random

object Arbiter5Types {
  // Define enums
  val selection = Enum(6)
  val A = selection(0)
  val B = selection(1) 
  val C = selection(2)
  val D = selection(3)
  val E = selection(4)
  val X = selection(5)
  
  val controller_state = Enum(3)
  val IDLE = controller_state(0)
  val READY = controller_state(1)
  val BUSY = controller_state(2)
  
  val client_state = Enum(3)
  val NO_REQ = client_state(0)
  val REQ = client_state(1)
  val HAVE_TOKEN = client_state(2)
}

class Controller(id: UInt) extends Module {
  import Arbiter5Types._
  
  val io = IO(new Bundle {
    val req = Input(Bool())
    val ack = Output(Bool())
    val sel = Input(UInt(3.W))
    val pass_token = Output(Bool())
  })
  
  val state = RegInit(IDLE)
  val ackReg = RegInit(false.B)
  val pass_tokenReg = RegInit(true.B)
  
  val is_selected = (io.sel === id)
  
  io.ack := ackReg
  io.pass_token := pass_tokenReg
  
  switch(state) {
    is(IDLE) {
      when(is_selected) {
        when(io.req) {
          state := READY
          pass_tokenReg := false.B // dropping off this line causes a safety bug
        }.otherwise {
          pass_tokenReg := true.B
        }
      }.otherwise {
        pass_tokenReg := false.B
      }
    }
    is(READY) {
      state := BUSY
      ackReg := true.B
    }
    is(BUSY) {
      when(!io.req) {
        state := IDLE
        ackReg := false.B
        pass_tokenReg := true.B
      }
    }
  }
}

class Arbiter extends Module {
  import Arbiter5Types._
  
  val io = IO(new Bundle {
    val active = Input(Bool())
    val sel = Output(UInt(3.W))
  })
  
  val state = RegInit(A)
  
  io.sel := Mux(io.active, state, X)
  
  when(io.active) {
    switch(state) {
      is(A) { state := B }
      is(B) { state := C }
      is(C) { state := D }
      is(D) { state := E }
      is(E) { state := A }
    }
  }
}

class Client extends Module {
  import Arbiter5Types._
  
  val io = IO(new Bundle {
    val req = Output(Bool())
    val ack = Input(Bool())
  })
  
  val state = RegInit(NO_REQ)
  val reqReg = RegInit(false.B)
  
  // Simulate non-deterministic choice with pseudo-random
  val rand_counter = RegInit(0.U(8.W))
  rand_counter := rand_counter + 1.U
  val rand_choice = (rand_counter(0) === 1.U) // Simple pseudo-random
  
  io.req := reqReg
  
  switch(state) {
    is(NO_REQ) {
      when(rand_choice) {
        reqReg := true.B
        state := REQ
      }
    }
    is(REQ) {
      when(io.ack) {
        state := HAVE_TOKEN
      }
    }
    is(HAVE_TOKEN) {
      when(rand_choice) {
        reqReg := false.B
        state := NO_REQ
      }
    }
  }
}

class Arbiter5Main extends Module {
  import Arbiter5Types._
  
  val io = IO(new Bundle {
    val ackA = Output(Bool())
    val ackB = Output(Bool())
    val ackC = Output(Bool())
    val ackD = Output(Bool())
    val ackE = Output(Bool())
    // Add debug outputs to preserve signals
    val sel = Output(UInt(3.W))
    val active = Output(Bool())
    val reqA = Output(Bool())
    val reqB = Output(Bool())
    val reqC = Output(Bool())
    val reqD = Output(Bool())
    val reqE = Output(Bool())
  })
  
  // Instantiate clients
  val clientA = Module(new Client)
  val clientB = Module(new Client)
  val clientC = Module(new Client)
  val clientD = Module(new Client)
  val clientE = Module(new Client)
  
  // Instantiate controllers
  val controllerA = Module(new Controller(A))
  val controllerB = Module(new Controller(B))
  val controllerC = Module(new Controller(C))
  val controllerD = Module(new Controller(D))
  val controllerE = Module(new Controller(E))
  
  // Instantiate arbiter
  val arbiterModule = Module(new Arbiter)
  
  // Connect clients to controllers
  controllerA.io.req := clientA.io.req
  clientA.io.ack := controllerA.io.ack
  
  controllerB.io.req := clientB.io.req
  clientB.io.ack := controllerB.io.ack
  
  controllerC.io.req := clientC.io.req
  clientC.io.ack := controllerC.io.ack
  
  controllerD.io.req := clientD.io.req
  clientD.io.ack := controllerD.io.ack
  
  controllerE.io.req := clientE.io.req
  clientE.io.ack := controllerE.io.ack
  
  // Connect controllers to arbiter
  val selWire = arbiterModule.io.sel
  controllerA.io.sel := selWire
  controllerB.io.sel := selWire
  controllerC.io.sel := selWire
  controllerD.io.sel := selWire
  controllerE.io.sel := selWire
  
  // Calculate active signal
  val activeWire = controllerA.io.pass_token || controllerB.io.pass_token || 
                   controllerC.io.pass_token || controllerD.io.pass_token || 
                   controllerE.io.pass_token
  
  arbiterModule.io.active := activeWire
  
  // Connect outputs
  io.ackA := controllerA.io.ack
  io.ackB := controllerB.io.ack
  io.ackC := controllerC.io.ack
  io.ackD := controllerD.io.ack
  io.ackE := controllerE.io.ack
  
  // Debug outputs
  io.sel := selWire
  io.active := activeWire
  io.reqA := clientA.io.req
  io.reqB := clientB.io.req
  io.reqC := clientC.io.req
  io.reqD := clientD.io.req
  io.reqE := clientE.io.req
}

object VerilogGenerator extends App {
  emitVerilog(new Arbiter5Main(), args)
}