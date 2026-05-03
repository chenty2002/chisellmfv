package llmverify

import chisel3._
import chisel3.util._

// Enum definitions
class Selection extends Bundle {
  val value = UInt(4.W)
}

object Selection {
  def A = 0.U(4.W)
  def B = 1.U(4.W)
  def C = 2.U(4.W)
  def D = 3.U(4.W)
  def E = 4.U(4.W)
  def F = 5.U(4.W)
  def G = 6.U(4.W)
  def H = 7.U(4.W)
  def I = 8.U(4.W)
  def X = 15.U(4.W)
}

object ControllerState {
  def IDLE = 0.U(2.W)
  def READY = 1.U(2.W)
  def BUSY = 2.U(2.W)
}

object ClientState {
  def NO_REQ = 0.U(2.W)
  def REQ = 1.U(2.W)
  def HAVE_TOKEN = 2.U(2.W)
}

class Controller(id: UInt) extends Module {
  val io = IO(new Bundle {
    val req = Input(Bool())
    val ack = Output(Bool())
    val sel = Input(UInt(4.W))
    val pass_token = Output(Bool())
  })
  
  val state = RegInit(ControllerState.IDLE)
  val ackReg = RegInit(false.B)
  val passTokenReg = RegInit(true.B)
  
  val is_selected = (io.sel === id)
  
  io.ack := ackReg
  io.pass_token := passTokenReg
  
  switch(state) {
    is(ControllerState.IDLE) {
      when(is_selected) {
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

class Arbiter extends Module {
  val io = IO(new Bundle {
    val active = Input(Bool())
    val sel = Output(UInt(4.W))
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
      is(Selection.G) { state := Selection.H }
      is(Selection.H) { state := Selection.I }
      is(Selection.I) { state := Selection.A }
    }
  }
}

class Client extends Module {
  val io = IO(new Bundle {
    val req = Output(Bool())
    val ack = Input(Bool())
  })
  
  val state = RegInit(ClientState.NO_REQ)
  val reqReg = RegInit(false.B)
  
  io.req := reqReg
  
  // In Chisel, we can use LFSR for pseudo-random choice
  val lfsr = RegInit(1.U(8.W))
  val rand_choice = lfsr(0)
  lfsr := Cat(lfsr(6,0), lfsr(7) ^ lfsr(5) ^ lfsr(4) ^ lfsr(3))
  
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

class Main extends Module {
  val io = IO(new Bundle {
    val ackA = Output(Bool())
    val ackB = Output(Bool())
    val ackC = Output(Bool())
    val ackD = Output(Bool())
    val ackE = Output(Bool())
    val ackF = Output(Bool())
    val ackG = Output(Bool())
    val ackH = Output(Bool())
    val ackI = Output(Bool())
    // Additional outputs to preserve internal signals
    val sel = Output(UInt(4.W))
    val active = Output(Bool())
    val reqA = Output(Bool())
    val reqB = Output(Bool())
    val reqC = Output(Bool())
    val reqD = Output(Bool())
    val reqE = Output(Bool())
    val reqF = Output(Bool())
    val reqG = Output(Bool())
    val reqH = Output(Bool())
    val reqI = Output(Bool())
  })
  
  // Create controllers
  val controllerA = Module(new Controller(Selection.A))
  val controllerB = Module(new Controller(Selection.B))
  val controllerC = Module(new Controller(Selection.C))
  val controllerD = Module(new Controller(Selection.D))
  val controllerE = Module(new Controller(Selection.E))
  val controllerF = Module(new Controller(Selection.F))
  val controllerG = Module(new Controller(Selection.G))
  val controllerH = Module(new Controller(Selection.H))
  val controllerI = Module(new Controller(Selection.I))
  
  // Create arbiter
  val arbiter = Module(new Arbiter())
  
  // Create clients
  val clientA = Module(new Client())
  val clientB = Module(new Client())
  val clientC = Module(new Client())
  val clientD = Module(new Client())
  val clientE = Module(new Client())
  val clientF = Module(new Client())
  val clientG = Module(new Client())
  val clientH = Module(new Client())
  val clientI = Module(new Client())
  
  // Connect controllers to clients
  controllerA.io.req := clientA.io.req
  controllerB.io.req := clientB.io.req
  controllerC.io.req := clientC.io.req
  controllerD.io.req := clientD.io.req
  controllerE.io.req := clientE.io.req
  controllerF.io.req := clientF.io.req
  controllerG.io.req := clientG.io.req
  controllerH.io.req := clientH.io.req
  controllerI.io.req := clientI.io.req
  
  clientA.io.ack := controllerA.io.ack
  clientB.io.ack := controllerB.io.ack
  clientC.io.ack := controllerC.io.ack
  clientD.io.ack := controllerD.io.ack
  clientE.io.ack := controllerE.io.ack
  clientF.io.ack := controllerF.io.ack
  clientG.io.ack := controllerG.io.ack
  clientH.io.ack := controllerH.io.ack
  clientI.io.ack := controllerI.io.ack
  
  // Connect controllers to arbiter
  val sel = arbiter.io.sel
  controllerA.io.sel := sel
  controllerB.io.sel := sel
  controllerC.io.sel := sel
  controllerD.io.sel := sel
  controllerE.io.sel := sel
  controllerF.io.sel := sel
  controllerG.io.sel := sel
  controllerH.io.sel := sel
  controllerI.io.sel := sel
  
  // Calculate active signal
  val active = controllerA.io.pass_token || controllerB.io.pass_token ||
                controllerC.io.pass_token || controllerD.io.pass_token ||
                controllerE.io.pass_token || controllerF.io.pass_token ||
                controllerG.io.pass_token || controllerH.io.pass_token ||
                controllerI.io.pass_token
  
  arbiter.io.active := active
  
  // Connect outputs
  io.ackA := controllerA.io.ack
  io.ackB := controllerB.io.ack
  io.ackC := controllerC.io.ack
  io.ackD := controllerD.io.ack
  io.ackE := controllerE.io.ack
  io.ackF := controllerF.io.ack
  io.ackG := controllerG.io.ack
  io.ackH := controllerH.io.ack
  io.ackI := controllerI.io.ack
  
  // Additional outputs to preserve internal signals
  io.sel := sel
  io.active := active
  io.reqA := clientA.io.req
  io.reqB := clientB.io.req
  io.reqC := clientC.io.req
  io.reqD := clientD.io.req
  io.reqE := clientE.io.req
  io.reqF := clientF.io.req
  io.reqG := clientG.io.req
  io.reqH := clientH.io.req
  io.reqI := clientI.io.req
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}