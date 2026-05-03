package llmverify

import chisel3._
import chisel3.util._

/*
 * Tree arbiter derived from the one of Adnan Aziz, which in turn is based
 * on the one in David Dill's thesis.  The aribter of Aziz tries to improve
 * efficiency by not returning the token every time to the root of the tree.
 * However, it has bugs that cause starvation.  This version fixes those bugs.
 *
 * Author: Fabio Somenzi <Fabio@Colorado.EDU>
 */

object Phase {
  val idle :: request :: lock :: release :: Nil = Enum(4)
}

class proc extends Module {
  val io = IO(new Bundle {
    val ack = Input(Bool())
    val choice = Input(Bool())
    val req = Output(UInt(2.W))
  })
  
  val procState = RegInit(Phase.idle)
  
  io.req := procState
  
  when(procState === Phase.idle && io.choice === 1.U) {
    procState := Phase.request
  }.elsewhen(procState === Phase.request && io.ack === 1.U) {
    procState := Phase.lock
  }.elsewhen(procState === Phase.lock && io.choice === 1.U) {
    procState := Phase.release
  }.elsewhen(procState === Phase.release) {
    procState := Phase.idle
  }
}

class arbitCell extends Module {
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
  
  val mustGiveParent = (processedLeft || io.urLeft =/= Phase.request) &&
                       (processedRight || io.urRight =/= Phase.request) &&
                       !io.topCell
  
  val childOwns = io.urLeft === Phase.lock || io.urRight === Phase.lock
  
  val giveChild = io.uaLeft || io.uaRight
  
  io.uaLeft := !mustGiveParent && holdToken && io.urLeft === Phase.request &&
               (io.urRight =/= Phase.request || !prevLeft)
  
  io.uaRight := !mustGiveParent && holdToken && io.urRight === Phase.request &&
                (io.urLeft =/= Phase.request || prevLeft)
  
  val requesting = io.urLeft === Phase.request || io.urRight === Phase.request
  
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

class main extends Module {
  val io = IO(new Bundle {
    val choice = Input(UInt(8.W))
    // Add outputs to preserve internal signals
    val a0_0 = Output(Bool())
    val a0_1 = Output(Bool())
    val a0_2 = Output(Bool())
    val a0_3 = Output(Bool())
    val a0_4 = Output(Bool())
    val a0_5 = Output(Bool())
    val a0_6 = Output(Bool())
    val a0_7 = Output(Bool())
    val r0_0 = Output(UInt(2.W))
    val r0_1 = Output(UInt(2.W))
    val r0_2 = Output(UInt(2.W))
    val r0_3 = Output(UInt(2.W))
    val r0_4 = Output(UInt(2.W))
    val r0_5 = Output(UInt(2.W))
    val r0_6 = Output(UInt(2.W))
    val r0_7 = Output(UInt(2.W))
  })
  
  val a3_0 = Wire(Bool())
  a3_0 := 0.B
  
  val a2_0 = Wire(Bool())
  val a2_1 = Wire(Bool())
  val a1_0 = Wire(Bool())
  val a1_1 = Wire(Bool())
  val a1_2 = Wire(Bool())
  val a1_3 = Wire(Bool())
  val a0_0 = Wire(Bool())
  val a0_1 = Wire(Bool())
  val a0_2 = Wire(Bool())
  val a0_3 = Wire(Bool())
  val a0_4 = Wire(Bool())
  val a0_5 = Wire(Bool())
  val a0_6 = Wire(Bool())
  val a0_7 = Wire(Bool())
  
  val r3_0 = Wire(UInt(2.W))
  val r2_0 = Wire(UInt(2.W))
  val r2_1 = Wire(UInt(2.W))
  val r1_0 = Wire(UInt(2.W))
  val r1_1 = Wire(UInt(2.W))
  val r1_2 = Wire(UInt(2.W))
  val r1_3 = Wire(UInt(2.W))
  val r0_0 = Wire(UInt(2.W))
  val r0_1 = Wire(UInt(2.W))
  val r0_2 = Wire(UInt(2.W))
  val r0_3 = Wire(UInt(2.W))
  val r0_4 = Wire(UInt(2.W))
  val r0_5 = Wire(UInt(2.W))
  val r0_6 = Wire(UInt(2.W))
  val r0_7 = Wire(UInt(2.W))
  
  val C2_0 = Module(new arbitCell())
  C2_0.io.topCell := 1.B
  C2_0.io.urLeft := r2_0
  C2_0.io.urRight := r2_1
  C2_0.io.xa := a3_0
  a2_0 := C2_0.io.uaLeft
  a2_1 := C2_0.io.uaRight
  r3_0 := C2_0.io.xr
  
  val C1_0 = Module(new arbitCell())
  C1_0.io.topCell := 0.B
  C1_0.io.urLeft := r1_0
  C1_0.io.urRight := r1_1
  C1_0.io.xa := a2_0
  a1_0 := C1_0.io.uaLeft
  a1_1 := C1_0.io.uaRight
  r2_0 := C1_0.io.xr
  
  val C1_1 = Module(new arbitCell())
  C1_1.io.topCell := 0.B
  C1_1.io.urLeft := r1_2
  C1_1.io.urRight := r1_3
  C1_1.io.xa := a2_1
  a1_2 := C1_1.io.uaLeft
  a1_3 := C1_1.io.uaRight
  r2_1 := C1_1.io.xr
  
  val C0_0 = Module(new arbitCell())
  C0_0.io.topCell := 0.B
  C0_0.io.urLeft := r0_0
  C0_0.io.urRight := r0_1
  C0_0.io.xa := a1_0
  a0_0 := C0_0.io.uaLeft
  a0_1 := C0_0.io.uaRight
  r1_0 := C0_0.io.xr
  
  val C0_1 = Module(new arbitCell())
  C0_1.io.topCell := 0.B
  C0_1.io.urLeft := r0_2
  C0_1.io.urRight := r0_3
  C0_1.io.xa := a1_1
  a0_2 := C0_1.io.uaLeft
  a0_3 := C0_1.io.uaRight
  r1_1 := C0_1.io.xr
  
  val C0_2 = Module(new arbitCell())
  C0_2.io.topCell := 0.B
  C0_2.io.urLeft := r0_4
  C0_2.io.urRight := r0_5
  C0_2.io.xa := a1_2
  a0_4 := C0_2.io.uaLeft
  a0_5 := C0_2.io.uaRight
  r1_2 := C0_2.io.xr
  
  val C0_3 = Module(new arbitCell())
  C0_3.io.topCell := 0.B
  C0_3.io.urLeft := r0_6
  C0_3.io.urRight := r0_7
  C0_3.io.xa := a1_3
  a0_6 := C0_3.io.uaLeft
  a0_7 := C0_3.io.uaRight
  r1_3 := C0_3.io.xr
  
  val P0 = Module(new proc())
  P0.io.ack := a0_0
  P0.io.choice := io.choice(0)
  r0_0 := P0.io.req
  
  val P1 = Module(new proc())
  P1.io.ack := a0_1
  P1.io.choice := io.choice(1)
  r0_1 := P1.io.req
  
  val P2 = Module(new proc())
  P2.io.ack := a0_2
  P2.io.choice := io.choice(2)
  r0_2 := P2.io.req
  
  val P3 = Module(new proc())
  P3.io.ack := a0_3
  P3.io.choice := io.choice(3)
  r0_3 := P3.io.req
  
  val P4 = Module(new proc())
  P4.io.ack := a0_4
  P4.io.choice := io.choice(4)
  r0_4 := P4.io.req
  
  val P5 = Module(new proc())
  P5.io.ack := a0_5
  P5.io.choice := io.choice(5)
  r0_5 := P5.io.req
  
  val P6 = Module(new proc())
  P6.io.ack := a0_6
  P6.io.choice := io.choice(6)
  r0_6 := P6.io.req
  
  val P7 = Module(new proc())
  P7.io.ack := a0_7
  P7.io.choice := io.choice(7)
  r0_7 := P7.io.req
  
  // Connect internal signals to outputs to preserve them
  io.a0_0 := a0_0
  io.a0_1 := a0_1
  io.a0_2 := a0_2
  io.a0_3 := a0_3
  io.a0_4 := a0_4
  io.a0_5 := a0_5
  io.a0_6 := a0_6
  io.a0_7 := a0_7
  io.r0_0 := r0_0
  io.r0_1 := r0_1
  io.r0_2 := r0_2
  io.r0_3 := r0_3
  io.r0_4 := r0_4
  io.r0_5 := r0_5
  io.r0_6 := r0_6
  io.r0_7 := r0_7
}

object VerilogGenerator extends App {
  emitVerilog(new main(), args)
}