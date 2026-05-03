package llmverify
import chisel3._
import chisel3.util._

class cell extends Module {
  val io = IO(new Bundle {
    val req = Input(Bool())
    val ack = Output(Bool())
    val tokenIn = Input(Bool())
    val tokenOut = Output(Bool())
    val tokenInit = Input(Bool())
    val grantIn = Input(Bool())
    val grantOut = Output(Bool())
    val overrideIn = Input(Bool())
    val overrideOut = Output(Bool())
  })

  val token = RegInit(io.tokenInit)
  val waiting = RegInit(false.B)

  when(true.B) { // always @ (posedge clock)
    waiting := io.req & (waiting | token)
    token := io.tokenIn
  }

  val tw = token & waiting
  io.ack := io.req & (io.grantIn | tw)
  io.tokenOut := token
  io.grantOut := io.grantIn & ~io.req
  io.overrideOut := io.overrideIn | tw
}

class syncArb extends Module {
  val io = IO(new Bundle {
    val req = Input(UInt(16.W))
    val ack = Output(UInt(16.W))
  })

  val lreq = RegInit(0.U(16.W))
  lreq := io.req

  val tokenInit = 1.U(16.W)
  
  // Create cells
  val cells = Array.fill(16)(Module(new cell()))
  
  // Create wires for the circular connections (17 elements for 0-16)
  val token = Wire(Vec(17, Bool()))
  val grant = Wire(Vec(17, Bool()))
  val overrideSignal = Wire(Vec(17, Bool()))
  
  // Connect cells first to establish the flow
  for (i <- 0 until 16) {
    cells(i).io.req := lreq(i)
    io.ack(i) := cells(i).io.ack
    cells(i).io.tokenInit := tokenInit(i)
  }
  
  // Now establish the circular connections
  // token[0] = token[16] (circular)
  // grant[0] = ~override[0] 
  // override[16] = 0
  overrideSignal(16) := false.B
  
  // Connect the chain
  for (i <- 0 until 16) {
    cells(i).io.tokenIn := token(i)
    token(i+1) := cells(i).io.tokenOut
    cells(i).io.grantIn := grant(i)
    grant(i+1) := cells(i).io.grantOut
    cells(i).io.overrideIn := overrideSignal(i)
    overrideSignal(i+1) := cells(i).io.overrideOut
  }
  
  // Complete the circular connections
  token(0) := token(16)
  grant(0) := ~overrideSignal(0)
}

object VerilogGenerator extends App {
  emitVerilog(new syncArb(), args)
}