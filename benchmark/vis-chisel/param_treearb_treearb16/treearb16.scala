package llmverify
import chisel3._
import chisel3.util._

// Phase enumeration
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
  
  // Simulate nondeterministic choice with a simple counter
  val choiceCounter = RegInit(0.U(8.W))
  choiceCounter := choiceCounter + 1.U
  val choice = (choiceCounter(0) === 1.U) // Simple alternating pattern
  
  val reqReg = RegInit(Phase.idle)
  
  when(reqReg === Phase.idle && choice) {
    reqReg := Phase.request
  }.elsewhen(reqReg === Phase.request && io.ack) {
    reqReg := Phase.lock
  }.elsewhen(reqReg === Phase.lock && choice) {
    reqReg := Phase.release
  }.elsewhen(reqReg === Phase.release) {
    reqReg := Phase.idle
  }
  
  io.req := reqReg
}

/*
 * The arbiter cell has two inputs from children and two outputs to children.
 * One input from parent, and one output to parent. The latch holdToken
 * corresponds to whether the cell holds the token. The latch prevLeft
 * is used to keep track of which way the token went last,
 * to impart fairness in the scheduling of the children.
 */
class ArbitCell(topCell: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val urLeft = Input(UInt(2.W))
    val urRight = Input(UInt(2.W))
    val uaLeft = Output(Bool())
    val uaRight = Output(Bool())
    val xr = Output(UInt(2.W))
    val xa = Input(Bool())
  })
  
  val prevLeft = RegInit(false.B)
  val processedLeft = RegInit(false.B)
  val processedRight = RegInit(false.B)
  val holdToken = RegInit(topCell.B)
  
  // Combinational logic
  val mustGiveParent = (processedLeft || io.urLeft =/= Phase.request) &&
                       (processedRight || io.urRight =/= Phase.request) && !topCell.B
  
  val childOwns = io.urLeft === Phase.lock || io.urRight === Phase.lock
  val giveChild = io.uaLeft || io.uaRight
  
  io.uaLeft := !mustGiveParent && holdToken && io.urLeft === Phase.request &&
               (io.urRight =/= Phase.request || !prevLeft)
  
  io.uaRight := !mustGiveParent && holdToken && io.urRight === Phase.request &&
                (io.urLeft =/= Phase.request || prevLeft)
  
  val requesting = io.urLeft === Phase.request || io.urRight === Phase.request
  
  io.xr := Mux(!holdToken && requesting, Phase.request,
           Mux(childOwns, Phase.lock,
           Mux(holdToken && !topCell.B && (mustGiveParent || !requesting), Phase.release,
               Phase.idle)))
  
  // Sequential logic
  when(io.xa) {
    holdToken := true.B
    processedLeft := false.B
    processedRight := false.B
  }.elsewhen(giveChild) {
    holdToken := false.B
  }.elsewhen(io.urLeft === Phase.release || io.urRight === Phase.release) {
    holdToken := true.B
  }.elsewhen(io.xr === Phase.release) {
    holdToken := false.B
  }
  
  when(io.uaLeft) {
    prevLeft := true.B
  }.elsewhen(io.uaRight) {
    prevLeft := false.B
  }
  
  when(io.urLeft === Phase.release) {
    processedLeft := true.B
  }.elsewhen(io.urRight === Phase.release) {
    processedRight := true.B
  }
}

/*
 * The interconnections between the processors and the cells.
 */
class Main extends Module {
  val io = IO(new Bundle {
    // Add outputs to preserve the design
    val reqs = Output(Vec(16, UInt(2.W)))
    val acks = Output(Vec(16, Bool()))
    val topReq = Output(UInt(2.W))
  })
  
  // Level 0 - Processors to first level arbiters
  val r0_0 = Wire(UInt(2.W))
  val r0_1 = Wire(UInt(2.W))
  val a0_0 = Wire(Bool())
  val a0_1 = Wire(Bool())
  val C0_0 = Module(new ArbitCell(topCell = false))
  C0_0.io.urLeft := r0_0
  C0_0.io.urRight := r0_1
  a0_0 := C0_0.io.uaLeft
  a0_1 := C0_0.io.uaRight
  val r1_0 = C0_0.io.xr
  val a1_0 = C0_0.io.xa
  
  val r0_2 = Wire(UInt(2.W))
  val r0_3 = Wire(UInt(2.W))
  val a0_2 = Wire(Bool())
  val a0_3 = Wire(Bool())
  val C0_1 = Module(new ArbitCell(topCell = false))
  C0_1.io.urLeft := r0_2
  C0_1.io.urRight := r0_3
  a0_2 := C0_1.io.uaLeft
  a0_3 := C0_1.io.uaRight
  val r1_1 = C0_1.io.xr
  val a1_1 = C0_1.io.xa
  
  val r0_4 = Wire(UInt(2.W))
  val r0_5 = Wire(UInt(2.W))
  val a0_4 = Wire(Bool())
  val a0_5 = Wire(Bool())
  val C0_2 = Module(new ArbitCell(topCell = false))
  C0_2.io.urLeft := r0_4
  C0_2.io.urRight := r0_5
  a0_4 := C0_2.io.uaLeft
  a0_5 := C0_2.io.uaRight
  val r1_2 = C0_2.io.xr
  val a1_2 = C0_2.io.xa
  
  val r0_6 = Wire(UInt(2.W))
  val r0_7 = Wire(UInt(2.W))
  val a0_6 = Wire(Bool())
  val a0_7 = Wire(Bool())
  val C0_3 = Module(new ArbitCell(topCell = false))
  C0_3.io.urLeft := r0_6
  C0_3.io.urRight := r0_7
  a0_6 := C0_3.io.uaLeft
  a0_7 := C0_3.io.uaRight
  val r1_3 = C0_3.io.xr
  val a1_3 = C0_3.io.xa
  
  val r0_8 = Wire(UInt(2.W))
  val r0_9 = Wire(UInt(2.W))
  val a0_8 = Wire(Bool())
  val a0_9 = Wire(Bool())
  val C0_4 = Module(new ArbitCell(topCell = false))
  C0_4.io.urLeft := r0_8
  C0_4.io.urRight := r0_9
  a0_8 := C0_4.io.uaLeft
  a0_9 := C0_4.io.uaRight
  val r1_4 = C0_4.io.xr
  val a1_4 = C0_4.io.xa
  
  val r0_10 = Wire(UInt(2.W))
  val r0_11 = Wire(UInt(2.W))
  val a0_10 = Wire(Bool())
  val a0_11 = Wire(Bool())
  val C0_5 = Module(new ArbitCell(topCell = false))
  C0_5.io.urLeft := r0_10
  C0_5.io.urRight := r0_11
  a0_10 := C0_5.io.uaLeft
  a0_11 := C0_5.io.uaRight
  val r1_5 = C0_5.io.xr
  val a1_5 = C0_5.io.xa
  
  val r0_12 = Wire(UInt(2.W))
  val r0_13 = Wire(UInt(2.W))
  val a0_12 = Wire(Bool())
  val a0_13 = Wire(Bool())
  val C0_6 = Module(new ArbitCell(topCell = false))
  C0_6.io.urLeft := r0_12
  C0_6.io.urRight := r0_13
  a0_12 := C0_6.io.uaLeft
  a0_13 := C0_6.io.uaRight
  val r1_6 = C0_6.io.xr
  val a1_6 = C0_6.io.xa
  
  val r0_14 = Wire(UInt(2.W))
  val r0_15 = Wire(UInt(2.W))
  val a0_14 = Wire(Bool())
  val a0_15 = Wire(Bool())
  val C0_7 = Module(new ArbitCell(topCell = false))
  C0_7.io.urLeft := r0_14
  C0_7.io.urRight := r0_15
  a0_14 := C0_7.io.uaLeft
  a0_15 := C0_7.io.uaRight
  val r1_7 = C0_7.io.xr
  val a1_7 = C0_7.io.xa
  
  // Level 1
  val C1_0 = Module(new ArbitCell(topCell = false))
  C1_0.io.urLeft := r1_0
  C1_0.io.urRight := r1_1
  a1_0 := C1_0.io.uaLeft
  a1_1 := C1_0.io.uaRight
  val r2_0 = C1_0.io.xr
  val a2_0 = C1_0.io.xa
  
  val C1_1 = Module(new ArbitCell(topCell = false))
  C1_1.io.urLeft := r1_2
  C1_1.io.urRight := r1_3
  a1_2 := C1_1.io.uaLeft
  a1_3 := C1_1.io.uaRight
  val r2_1 = C1_1.io.xr
  val a2_1 = C1_1.io.xa
  
  val C1_2 = Module(new ArbitCell(topCell = false))
  C1_2.io.urLeft := r1_4
  C1_2.io.urRight := r1_5
  a1_4 := C1_2.io.uaLeft
  a1_5 := C1_2.io.uaRight
  val r2_2 = C1_2.io.xr
  val a2_2 = C1_2.io.xa
  
  val C1_3 = Module(new ArbitCell(topCell = false))
  C1_3.io.urLeft := r1_6
  C1_3.io.urRight := r1_7
  a1_6 := C1_3.io.uaLeft
  a1_7 := C1_3.io.uaRight
  val r2_3 = C1_3.io.xr
  val a2_3 = C1_3.io.xa
  
  // Level 2
  val C2_0 = Module(new ArbitCell(topCell = false))
  C2_0.io.urLeft := r2_0
  C2_0.io.urRight := r2_1
  a2_0 := C2_0.io.uaLeft
  a2_1 := C2_0.io.uaRight
  val r3_0 = C2_0.io.xr
  val a3_0 = C2_0.io.xa
  
  val C2_1 = Module(new ArbitCell(topCell = false))
  C2_1.io.urLeft := r2_2
  C2_1.io.urRight := r2_3
  a2_2 := C2_1.io.uaLeft
  a2_3 := C2_1.io.uaRight
  val r3_1 = C2_1.io.xr
  val a3_1 = C2_1.io.xa
  
  // Level 3
  val C3_0 = Module(new ArbitCell(topCell = true))
  C3_0.io.urLeft := r3_0
  C3_0.io.urRight := r3_1
  a3_0 := C3_0.io.uaLeft
  a3_1 := C3_0.io.uaRight
  val r4_0 = C3_0.io.xr
  val a4_0 = C3_0.io.xa
  
  // Top level assignment
  C3_0.io.xa := 0.B
  
  // Processors
  val P0 = Module(new Proc())
  P0.io.ack := a0_0
  r0_0 := P0.io.req
  
  val P1 = Module(new Proc())
  P1.io.ack := a0_1
  r0_1 := P1.io.req
  
  val P2 = Module(new Proc())
  P2.io.ack := a0_2
  r0_2 := P2.io.req
  
  val P3 = Module(new Proc())
  P3.io.ack := a0_3
  r0_3 := P3.io.req
  
  val P4 = Module(new Proc())
  P4.io.ack := a0_4
  r0_4 := P4.io.req
  
  val P5 = Module(new Proc())
  P5.io.ack := a0_5
  r0_5 := P5.io.req
  
  val P6 = Module(new Proc())
  P6.io.ack := a0_6
  r0_6 := P6.io.req
  
  val P7 = Module(new Proc())
  P7.io.ack := a0_7
  r0_7 := P7.io.req
  
  val P8 = Module(new Proc())
  P8.io.ack := a0_8
  r0_8 := P8.io.req
  
  val P9 = Module(new Proc())
  P9.io.ack := a0_9
  r0_9 := P9.io.req
  
  val P10 = Module(new Proc())
  P10.io.ack := a0_10
  r0_10 := P10.io.req
  
  val P11 = Module(new Proc())
  P11.io.ack := a0_11
  r0_11 := P11.io.req
  
  val P12 = Module(new Proc())
  P12.io.ack := a0_12
  r0_12 := P12.io.req
  
  val P13 = Module(new Proc())
  P13.io.ack := a0_13
  r0_13 := P13.io.req
  
  val P14 = Module(new Proc())
  P14.io.ack := a0_14
  r0_14 := P14.io.req
  
  val P15 = Module(new Proc())
  P15.io.ack := a0_15
  r0_15 := P15.io.req
  
  // Connect outputs to preserve design
  io.reqs(0) := r0_0
  io.reqs(1) := r0_1
  io.reqs(2) := r0_2
  io.reqs(3) := r0_3
  io.reqs(4) := r0_4
  io.reqs(5) := r0_5
  io.reqs(6) := r0_6
  io.reqs(7) := r0_7
  io.reqs(8) := r0_8
  io.reqs(9) := r0_9
  io.reqs(10) := r0_10
  io.reqs(11) := r0_11
  io.reqs(12) := r0_12
  io.reqs(13) := r0_13
  io.reqs(14) := r0_14
  io.reqs(15) := r0_15
  
  io.acks(0) := a0_0
  io.acks(1) := a0_1
  io.acks(2) := a0_2
  io.acks(3) := a0_3
  io.acks(4) := a0_4
  io.acks(5) := a0_5
  io.acks(6) := a0_6
  io.acks(7) := a0_7
  io.acks(8) := a0_8
  io.acks(9) := a0_9
  io.acks(10) := a0_10
  io.acks(11) := a0_11
  io.acks(12) := a0_12
  io.acks(13) := a0_13
  io.acks(14) := a0_14
  io.acks(15) := a0_15
  
  io.topReq := r4_0
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}