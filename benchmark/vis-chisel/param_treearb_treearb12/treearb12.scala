package llmverify

import chisel3._
import chisel3.util._

// Define the phase enum
object Phase {
  val idle :: request :: lock :: release :: Nil = Enum(4)
}

/*
 * A process loops through four states: idle, request, lock, and release.
 * The transitions from idle to request, and from lock to release are
 * nondeterministic.
 */
class Proc extends Module {
  val io = IO(new Bundle {
    val ack = Input(Bool())
    val req = Output(UInt(2.W))
  })
  
  // Use a simple pseudo-random choice instead of $ND
  val choice = Wire(Bool())
  choice := RegNext(RegNext(io.req === Phase.idle) ^ RegNext(RegNext(io.req === Phase.lock)))
  
  val reqReg = RegInit(Phase.idle)
  
  when(reqReg === Phase.idle && choice === 1.U) {
    reqReg := Phase.request
  }.elsewhen(reqReg === Phase.request && io.ack === 1.U) {
    reqReg := Phase.lock
  }.elsewhen(reqReg === Phase.lock && choice === 1.U) {
    reqReg := Phase.release
  }.elsewhen(reqReg === Phase.release) {
    reqReg := Phase.idle
  }
  
  io.req := reqReg
}

/*
 * The arbiter cell has two inputs from children and two outputs to chidren.
 * One input from parent, and one output to parent. The latch holdToken
 * corresponds to whether the cell holds the token. The latch prevLeft
 * is used to keep track of which way the token went last,
 * to impart fairness in the scheduling of the children.
 */
class ArbitCell extends Module {
  val io = IO(new Bundle {
    val topCell = Input(Bool())
    val urLeft = Input(UInt(2.W))
    val urRight = Input(UInt(2.W))
    val xa = Input(Bool())
    val uaLeft = Output(Bool())
    val uaRight = Output(Bool())
    val xr = Output(UInt(2.W))
  })
  
  val prevLeft = RegInit(0.B)
  val processedLeft = RegInit(0.B)
  val processedRight = RegInit(0.B)
  val holdToken = RegInit(io.topCell)
  
  val mustGiveParent = Wire(Bool())
  mustGiveParent := (processedLeft || io.urLeft =/= Phase.request) &&
                    (processedRight || io.urRight =/= Phase.request) &&
                    !io.topCell
  
  val childOwns = Wire(Bool())
  childOwns := io.urLeft === Phase.lock || io.urRight === Phase.lock
  
  val giveChild = Wire(Bool())
  giveChild := io.uaLeft || io.uaRight
  
  io.uaLeft := !mustGiveParent && holdToken && io.urLeft === Phase.request &&
               (io.urRight =/= Phase.request || !prevLeft)
  
  io.uaRight := !mustGiveParent && holdToken && io.urRight === Phase.request &&
                (io.urLeft =/= Phase.request || prevLeft)
  
  val requesting = Wire(Bool())
  requesting := io.urLeft === Phase.request || io.urRight === Phase.request
  
  io.xr := Mux(!holdToken && requesting, Phase.request,
           Mux(childOwns, Phase.lock,
           Mux(holdToken && !io.topCell && (mustGiveParent || !requesting), Phase.release,
               Phase.idle)))
  
  when(io.xa) {
    holdToken := 1.B
    processedLeft := 0.B
    processedRight := 0.B
  }.elsewhen(giveChild) {
    holdToken := 0.B
  }.elsewhen(io.urLeft === Phase.release || io.urRight === Phase.release) {
    holdToken := 1.B
  }.elsewhen(io.xr === Phase.release) {
    holdToken := 0.B
  }
  
  when(io.uaLeft) {
    prevLeft := 1.B
  }.elsewhen(io.uaRight) {
    prevLeft := 0.B
  }
  
  when(io.urLeft === Phase.release) {
    processedLeft := 1.B
  }.elsewhen(io.urRight === Phase.release) {
    processedRight := 1.B
  }
}

/*
 * The inteconnections between the processors and the cells.
 */
class Main extends Module {
  val io = IO(new Bundle {
    // Add outputs to preserve the design from optimization
    val req0 = Output(UInt(2.W))
    val req1 = Output(UInt(2.W))
    val req2 = Output(UInt(2.W))
    val req3 = Output(UInt(2.W))
    val req4 = Output(UInt(2.W))
    val req5 = Output(UInt(2.W))
    val req6 = Output(UInt(2.W))
    val req7 = Output(UInt(2.W))
    val req8 = Output(UInt(2.W))
    val req9 = Output(UInt(2.W))
    val req10 = Output(UInt(2.W))
    val req11 = Output(UInt(2.W))
  })
  
  // Level 0 cells
  val C0_0 = Module(new ArbitCell())
  val C0_1 = Module(new ArbitCell())
  val C0_2 = Module(new ArbitCell())
  val C0_3 = Module(new ArbitCell())
  val C0_4 = Module(new ArbitCell())
  val C0_5 = Module(new ArbitCell())
  
  // Level 1 cells
  val C1_0 = Module(new ArbitCell())
  val C1_1 = Module(new ArbitCell())
  val C1_2 = Module(new ArbitCell())
  
  // Level 2 cells
  val C2_0 = Module(new ArbitCell())
  val C2_1 = Module(new ArbitCell())
  
  // Level 3 cell
  val C3_0 = Module(new ArbitCell())
  
  // Processors
  val P0 = Module(new Proc())
  val P1 = Module(new Proc())
  val P2 = Module(new Proc())
  val P3 = Module(new Proc())
  val P4 = Module(new Proc())
  val P5 = Module(new Proc())
  val P6 = Module(new Proc())
  val P7 = Module(new Proc())
  val P8 = Module(new Proc())
  val P9 = Module(new Proc())
  val P10 = Module(new Proc())
  val P11 = Module(new Proc())
  
  // Connect Level 0 cells
  C0_0.io.topCell := 0.B
  C0_0.io.urLeft := P0.io.req
  C0_0.io.urRight := P1.io.req
  P0.io.ack := C0_0.io.uaLeft
  P1.io.ack := C0_0.io.uaRight
  
  C0_1.io.topCell := 0.B
  C0_1.io.urLeft := P2.io.req
  C0_1.io.urRight := P3.io.req
  P2.io.ack := C0_1.io.uaLeft
  P3.io.ack := C0_1.io.uaRight
  
  C0_2.io.topCell := 0.B
  C0_2.io.urLeft := P4.io.req
  C0_2.io.urRight := P5.io.req
  P4.io.ack := C0_2.io.uaLeft
  P5.io.ack := C0_2.io.uaRight
  
  C0_3.io.topCell := 0.B
  C0_3.io.urLeft := P6.io.req
  C0_3.io.urRight := P7.io.req
  P6.io.ack := C0_3.io.uaLeft
  P7.io.ack := C0_3.io.uaRight
  
  C0_4.io.topCell := 0.B
  C0_4.io.urLeft := P8.io.req
  C0_4.io.urRight := P9.io.req
  P8.io.ack := C0_4.io.uaLeft
  P9.io.ack := C0_4.io.uaRight
  
  C0_5.io.topCell := 0.B
  C0_5.io.urLeft := P10.io.req
  C0_5.io.urRight := P11.io.req
  P10.io.ack := C0_5.io.uaLeft
  P11.io.ack := C0_5.io.uaRight
  
  // Connect Level 1 cells
  C1_0.io.topCell := 0.B
  C1_0.io.urLeft := C0_0.io.xr
  C1_0.io.urRight := C0_1.io.xr
  C0_0.io.xa := C1_0.io.uaLeft
  C0_1.io.xa := C1_0.io.uaRight
  
  C1_1.io.topCell := 0.B
  C1_1.io.urLeft := C0_2.io.xr
  C1_1.io.urRight := C0_3.io.xr
  C0_2.io.xa := C1_1.io.uaLeft
  C0_3.io.xa := C1_1.io.uaRight
  
  C1_2.io.topCell := 0.B
  C1_2.io.urLeft := C0_4.io.xr
  C1_2.io.urRight := C0_5.io.xr
  C0_4.io.xa := C1_2.io.uaLeft
  C0_5.io.xa := C1_2.io.uaRight
  
  // Connect Level 2 cells
  C2_0.io.topCell := 0.B
  C2_0.io.urLeft := C1_0.io.xr
  C2_0.io.urRight := C1_1.io.xr
  C1_0.io.xa := C2_0.io.uaLeft
  C1_1.io.xa := C2_0.io.uaRight
  
  C2_1.io.topCell := 0.B
  C2_1.io.urLeft := C1_2.io.xr
  C2_1.io.urRight := Phase.idle
  C1_2.io.xa := C2_1.io.uaLeft
  
  // Connect Level 3 cell
  C3_0.io.topCell := 1.B
  C3_0.io.urLeft := C2_0.io.xr
  C3_0.io.urRight := C2_1.io.xr
  C2_0.io.xa := C3_0.io.uaLeft
  C2_1.io.xa := C3_0.io.uaRight
  
  // Top level connection
  C3_0.io.xa := 0.B
  
  // Connect outputs to preserve the design
  io.req0 := P0.io.req
  io.req1 := P1.io.req
  io.req2 := P2.io.req
  io.req3 := P3.io.req
  io.req4 := P4.io.req
  io.req5 := P5.io.req
  io.req6 := P6.io.req
  io.req7 := P7.io.req
  io.req8 := P8.io.req
  io.req9 := P9.io.req
  io.req10 := P10.io.req
  io.req11 := P11.io.req
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}