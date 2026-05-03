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

/*
 * A process loops through four states: idle, request, lock, and release.
 * The transitions from idle to request, and from lock to release are
 * nondeterministic.
 */
class Proc extends Module {
  val io = IO(new Bundle {
    val ack = Input(Bool())
    val req = Output(UInt(2.W)) // 2 bits to represent phase enum
  })
  
  val reqReg = RegInit(Phase.idle)
  
  // In Chisel, we use random generation for nondeterministic choice
  val choice = Wire(Bool())
  choice := RegNext(RegNext(0.U) ^ RegNext(1.U)) // Simple pseudo-random
  
  io.req := reqReg
  
  when(reqReg === Phase.idle && choice === 1.U) {
    reqReg := Phase.request
  }.elsewhen(reqReg === Phase.request && io.ack === 1.U) {
    reqReg := Phase.lock
  }.elsewhen(reqReg === Phase.lock && choice === 1.U) {
    reqReg := Phase.release
  }.elsewhen(reqReg === Phase.release) {
    reqReg := Phase.idle
  }
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
    val uaLeft = Output(Bool())
    val uaRight = Output(Bool())
    val xr = Output(UInt(2.W))
    val xa = Input(Bool())
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
  
  /*
   * Condition under which the token is given to the left child
   * Must own token, have request from left, and either no request from
   * right or if there is a request from the right it should be left's
   * turn (since right went the last time )
   */
  io.uaLeft := !mustGiveParent && holdToken && io.urLeft === Phase.request &&
               (io.urRight =/= Phase.request || !prevLeft)
  
  /*
   * same as above for right
   */
  io.uaRight := !mustGiveParent && holdToken && io.urRight === Phase.request &&
                (io.urLeft =/= Phase.request || prevLeft)
  
  val requesting = Wire(Bool())
  requesting := io.urLeft === Phase.request || io.urRight === Phase.request
  
  /*
   * signal to parent:
   *
   *   1. request if don't own the token,
   *   2. lock if descendant has locked the token
   *   3. release if child has released token
   *   4. idle otherwise
   */
  io.xr := Mux(!holdToken && requesting, Phase.request,
           Mux(childOwns, Phase.lock,
           Mux(holdToken && !io.topCell && (mustGiveParent || !requesting), Phase.release,
               Phase.idle)))
  
  /*
   * keep track of whether we hold the token or not
   */
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
  
  /*
   * keep track of which child got the token last
   */
  when(io.uaLeft) {
    prevLeft := 1.B
  }.elsewhen(io.uaRight) {
    prevLeft := 0.B
  }
  
  /*
   * child has finished processing the token
   */
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
    val ack0 = Output(Bool())
    val ack1 = Output(Bool())
    val ack2 = Output(Bool())
    val ack3 = Output(Bool())
    val ack4 = Output(Bool())
    val ack5 = Output(Bool())
    val ack6 = Output(Bool())
    val ack7 = Output(Bool())
  })
  
  // Level 0 arbiter cells
  val C0_0 = Module(new ArbitCell())
  val C0_1 = Module(new ArbitCell())
  val C0_2 = Module(new ArbitCell())
  val C0_3 = Module(new ArbitCell())
  
  // Level 1 arbiter cells
  val C1_0 = Module(new ArbitCell())
  val C1_1 = Module(new ArbitCell())
  
  // Level 2 arbiter cell
  val C2_0 = Module(new ArbitCell())
  
  // Processors
  val P0 = Module(new Proc())
  val P1 = Module(new Proc())
  val P2 = Module(new Proc())
  val P3 = Module(new Proc())
  val P4 = Module(new Proc())
  val P5 = Module(new Proc())
  val P6 = Module(new Proc())
  val P7 = Module(new Proc())
  
  // Configure level 0 cells
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
  
  // Configure level 1 cells
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
  
  // Configure level 2 cell (root)
  C2_0.io.topCell := 1.B
  C2_0.io.urLeft := C1_0.io.xr
  C2_0.io.urRight := C1_1.io.xr
  C1_0.io.xa := C2_0.io.uaLeft
  C1_1.io.xa := C2_0.io.uaRight
  
  // Root cell's parent doesn't exist, so xa is always 0
  C2_0.io.xa := 0.B
  
  // Connect outputs to preserve the design
  io.req0 := P0.io.req
  io.req1 := P1.io.req
  io.req2 := P2.io.req
  io.req3 := P3.io.req
  io.req4 := P4.io.req
  io.req5 := P5.io.req
  io.req6 := P6.io.req
  io.req7 := P7.io.req
  
  io.ack0 := P0.io.ack
  io.ack1 := P1.io.ack
  io.ack2 := P2.io.ack
  io.ack3 := P3.io.ack
  io.ack4 := P4.io.ack
  io.ack5 := P5.io.ack
  io.ack6 := P6.io.ack
  io.ack7 := P7.io.ack
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}