package llmverify

import chisel3._
import chisel3.util._

object ArbiterBugEnums {
  // Define enum values as object constants with UInt values
  object selection {
    val A = 0.U(2.W)
    val B = 1.U(2.W)
    val C = 2.U(2.W)
    val X = 3.U(2.W)
  }
  
  object controllerState {
    val IDLE = 0.U(2.W)
    val READY = 1.U(2.W)
    val BUSY = 2.U(2.W)
  }
  
  object clientState {
    val NO_REQ = 0.U(2.W)
    val REQ = 1.U(2.W)
    val HAVE_TOKEN = 2.U(2.W)
  }
}

class Client extends Module {
  import ArbiterBugEnums._
  
  val io = IO(new Bundle {
    val ack = Input(Bool())
    val req = Output(Bool())
  })
  
  val state = RegInit(clientState.NO_REQ)
  val reqReg = RegInit(false.B)
  
  // Random choice generator (simplified version)
  val randChoice = RegInit(false.B)
  randChoice := ~randChoice // Simple toggle for nondeterministic behavior
  
  io.req := reqReg
  
  when(state === clientState.NO_REQ) {
    when(randChoice) {
      reqReg := true.B
      state := clientState.REQ
    }
  }.elsewhen(state === clientState.REQ) {
    when(io.ack) {
      state := clientState.HAVE_TOKEN
    }
  }.elsewhen(state === clientState.HAVE_TOKEN) {
    when(randChoice) {
      reqReg := false.B
      state := clientState.NO_REQ
    }
  }
}

class Controller(id: Int) extends Module {
  import ArbiterBugEnums._
  
  val io = IO(new Bundle {
    val req = Input(Bool())
    val ack = Output(Bool())
    val sel = Input(UInt(2.W))
    val passToken = Output(Bool())
  })
  
  val state = RegInit(controllerState.IDLE)
  val ackReg = RegInit(false.B)
  val passTokenReg = RegInit(true.B)
  
  val idValue = id.U(2.W)
  val isSelected = (io.sel === idValue)
  
  io.ack := ackReg
  io.passToken := passTokenReg
  
  when(state === controllerState.IDLE) {
    when(isSelected) {
      when(io.req) {
        state := controllerState.READY
        passTokenReg := false.B // dropping off this line causes a safety bug
      }.otherwise {
        passTokenReg := true.B
      }
    }.otherwise {
      passTokenReg := false.B
    }
  }.elsewhen(state === controllerState.READY) {
    state := controllerState.BUSY
    ackReg := true.B
  }.elsewhen(state === controllerState.BUSY) {
    when(!io.req) {
      state := controllerState.IDLE
      ackReg := false.B
      passTokenReg := true.B
    }
  }
}

class Arbiter extends Module {
  import ArbiterBugEnums._
  
  val io = IO(new Bundle {
    val active = Input(Bool())
    val sel = Output(UInt(2.W))
  })
  
  val state = RegInit(selection.A)
  
  io.sel := Mux(io.active, state, selection.X)
  
  when(state === selection.A) {
    state := selection.B
  }.elsewhen(state === selection.B) {
    state := selection.C
  }.elsewhen(state === selection.C) {
    state := selection.A
  }
}

class Main extends Module {
  import ArbiterBugEnums._
  
  val io = IO(new Bundle {
    val ackA = Output(Bool())
    val ackB = Output(Bool())
    val ackC = Output(Bool())
    // Additional outputs to preserve internal signals
    val sel = Output(UInt(2.W))
    val active = Output(Bool())
    val reqA = Output(Bool())
    val reqB = Output(Bool())
    val reqC = Output(Bool())
    val passTokenA = Output(Bool())
    val passTokenB = Output(Bool())
    val passTokenC = Output(Bool())
  })
  
  // Instantiate clients
  val clientA = Module(new Client())
  val clientB = Module(new Client())
  val clientC = Module(new Client())
  
  // Instantiate controllers
  val controllerA = Module(new Controller(0)) // A = 0
  val controllerB = Module(new Controller(1)) // B = 1
  val controllerC = Module(new Controller(2)) // C = 2
  
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
  controllerA.io.sel := arbiterModule.io.sel
  controllerB.io.sel := arbiterModule.io.sel
  controllerC.io.sel := arbiterModule.io.sel
  
  // Calculate active signal
  val active = controllerA.io.passToken || controllerB.io.passToken || controllerC.io.passToken
  arbiterModule.io.active := active
  
  // Connect outputs
  io.ackA := controllerA.io.ack
  io.ackB := controllerB.io.ack
  io.ackC := controllerC.io.ack
  io.sel := arbiterModule.io.sel
  io.active := active
  io.reqA := clientA.io.req
  io.reqB := clientB.io.req
  io.reqC := clientC.io.req
  io.passTokenA := controllerA.io.passToken
  io.passTokenB := controllerB.io.passToken
  io.passTokenC := controllerC.io.passToken
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}