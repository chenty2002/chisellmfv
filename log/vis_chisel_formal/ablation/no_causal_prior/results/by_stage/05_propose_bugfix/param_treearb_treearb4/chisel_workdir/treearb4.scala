package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

// Phase enumeration for the arbiter states
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
    val req = Output(UInt(2.W)) // 2 bits to represent 4 states
  })
  
  // For nondeterministic choice, we'll use a random signal
  // In real hardware, this could be tied to a pseudo-random generator
  val choice = Wire(Bool())
  choice := RegNext(RegNext(0.U) ^ RegNext(1.U)) // Simple pseudo-random
  
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
 * The arbiter cell has two inputs from children and two outputs to children.
 * One input from parent, and one output to parent. The latch holdToken
 * corresponds to whether the cell holds the token. The latch prevLeft
 * is used to keep track of which way the token went last,
 * to impart fairness in the scheduling of the children.
 */
class ArbitCell extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
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
  
  // essentially a macro for checking if must release the token to parent
  val mustGiveParent = (processedLeft || io.urLeft =/= Phase.request) &&
                       (processedRight || io.urRight =/= Phase.request) &&
                       !io.topCell
  
  // essentially a macro for checking if a descendant owns the token
  val childOwns = io.urLeft === Phase.lock || io.urRight === Phase.lock
  
  // essentially a macro for checking if a child is being given the token
  val giveChild = Wire(Bool())
  
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
  
  giveChild := io.uaLeft || io.uaRight
  
  val requesting = io.urLeft === Phase.request || io.urRight === Phase.request
  
  /*
   * signal to parent:
   *
   *   1. request if don't own the token,
   *   2. lock if descendant has locked the token
   *   3. release if child has released token
   *   4. idle otherwise
   */
  io.xr := Mux(!holdToken && requesting && !io.topCell, Phase.request,
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
class TreeArb4 extends Module with Formal {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    // Add outputs to preserve internal signals
    val p0_req = Output(UInt(2.W))
    val p1_req = Output(UInt(2.W))
    val p2_req = Output(UInt(2.W))
    val p3_req = Output(UInt(2.W))
    val xr_root = Output(UInt(2.W))
  })
  
  // Level 0 cells and processors
  val C0_0 = Module(new ArbitCell())
  val C0_1 = Module(new ArbitCell())
  val P0 = Module(new Proc())
  val P1 = Module(new Proc())
  val P2 = Module(new Proc())
  val P3 = Module(new Proc())
  
  // Level 1 cell
  val C1_0 = Module(new ArbitCell())
  
  // Connect level 0
  C0_0.io.clk := io.clk
  C0_0.io.topCell := 0.B
  C0_0.io.urLeft := P0.io.req
  C0_0.io.urRight := P1.io.req
  P0.io.ack := C0_0.io.uaLeft
  P1.io.ack := C0_0.io.uaRight
  
  C0_1.io.clk := io.clk
  C0_1.io.topCell := 0.B
  C0_1.io.urLeft := P2.io.req
  C0_1.io.urRight := P3.io.req
  P2.io.ack := C0_1.io.uaLeft
  P3.io.ack := C0_1.io.uaRight
  
  // Connect level 1
  C1_0.io.clk := io.clk
  C1_0.io.topCell := 1.B
  C1_0.io.urLeft := C0_0.io.xr
  C1_0.io.urRight := C0_1.io.xr
  C0_0.io.xa := C1_0.io.uaLeft
  C0_1.io.xa := C1_0.io.uaRight
  
  // Root cell doesn't have parent, so xa is tied to 0
  C1_0.io.xa := 0.B
  
  // Connect outputs for observation
  io.p0_req := P0.io.req
  io.p1_req := P1.io.req
  io.p2_req := P2.io.req
  io.p3_req := P3.io.req
  io.xr_root := C1_0.io.xr

  // ============================================================
  // Formal Verification Assertions
  // ============================================================

  // --- Safety: Mutual exclusion ---
  // At most one processor may be in Phase.lock at any time.
  // This is the fundamental mutual-exclusion invariant of the token-ring arbiter.
  fvAssert(
    PopCount(Seq(
      io.p0_req === Phase.lock,
      io.p1_req === Phase.lock,
      io.p2_req === Phase.lock,
      io.p3_req === Phase.lock
    )) <= 1.U,
    "mutex_processor_lock"
  )

  // --- Safety: No two children of the same cell are granted simultaneously ---
  // A cell must never assert uaLeft and uaRight in the same cycle.
  fvAssert(!(C0_0.io.uaLeft && C0_0.io.uaRight), "cell0_ack_mutex")
  fvAssert(!(C0_1.io.uaLeft && C0_1.io.uaRight), "cell1_ack_mutex")
  fvAssert(!(C1_0.io.uaLeft && C1_0.io.uaRight), "cell_root_ack_mutex")

  // --- Safety: Valid phase encoding ---
  // Each processor's request signal must always be one of the four valid phase values.
  fvAssert(
    io.p0_req === Phase.idle || io.p0_req === Phase.request ||
      io.p0_req === Phase.lock || io.p0_req === Phase.release,
    "p0_valid_phase"
  )
  fvAssert(
    io.p1_req === Phase.idle || io.p1_req === Phase.request ||
      io.p1_req === Phase.lock || io.p1_req === Phase.release,
    "p1_valid_phase"
  )
  fvAssert(
    io.p2_req === Phase.idle || io.p2_req === Phase.request ||
      io.p2_req === Phase.lock || io.p2_req === Phase.release,
    "p2_valid_phase"
  )
  fvAssert(
    io.p3_req === Phase.idle || io.p3_req === Phase.request ||
      io.p3_req === Phase.lock || io.p3_req === Phase.release,
    "p3_valid_phase"
  )

  // --- Safety: Grant only when child is actually requesting ---
  // When a cell's uaLeft is asserted, the corresponding child must be in request state.
  fvAssert(!C0_0.io.uaLeft || P0.io.req === Phase.request, "cell0_uaLeft_child_requesting")
  fvAssert(!C0_0.io.uaRight || P1.io.req === Phase.request, "cell0_uaRight_child_requesting")
  fvAssert(!C0_1.io.uaLeft || P2.io.req === Phase.request, "cell1_uaLeft_child_requesting")
  fvAssert(!C0_1.io.uaRight || P3.io.req === Phase.request, "cell1_uaRight_child_requesting")
  fvAssert(!C1_0.io.uaLeft || C0_0.io.xr === Phase.request, "root_uaLeft_child_requesting")
  fvAssert(!C1_0.io.uaRight || C0_1.io.xr === Phase.request, "root_uaRight_child_requesting")

  // --- Safety: Lock at a non-root cell implies a child in lock ---
  // If a level-0 cell outputs Phase.lock to the parent, one of its children must be in lock.
  fvAssert(
    !(C0_0.io.xr === Phase.lock) || (P0.io.req === Phase.lock || P1.io.req === Phase.lock),
    "cell0_lock_implies_child_lock"
  )
  fvAssert(
    !(C0_1.io.xr === Phase.lock) || (P2.io.req === Phase.lock || P3.io.req === Phase.lock),
    "cell1_lock_implies_child_lock"
  )

  // --- Safety: Root cell never outputs request or release (since it has no parent) ---
  // Since C1_0 is the top cell (topCell=1), it should never report Phase.request
  // (it always holds the token initially) and Phase.release is meaningless without a parent.
  fvAssert(
    io.xr_root =/= Phase.request,
    "root_no_request"
  )

  // --- Liveness / Progress: Processors eventually acquire the lock ---
  // When a processor enters the request state (Phase.request), it must eventually
  // reach the lock state (Phase.lock) within a bounded number of cycles.
  // The bound of 50 is chosen to account for worst-case token propagation
  // through a 2-level tree with contention from up to 3 other processors.
  astRelaxedLiveness(
    io.p0_req === Phase.request,
    io.p0_req === Phase.lock,
    50,
    "p0_request_eventually_lock"
  )
  astRelaxedLiveness(
    io.p1_req === Phase.request,
    io.p1_req === Phase.lock,
    50,
    "p1_request_eventually_lock"
  )
  astRelaxedLiveness(
    io.p2_req === Phase.request,
    io.p2_req === Phase.lock,
    50,
    "p2_request_eventually_lock"
  )
  astRelaxedLiveness(
    io.p3_req === Phase.request,
    io.p3_req === Phase.lock,
    50,
    "p3_request_eventually_lock"
  )

  // --- Liveness / Progress: Lock state eventually releases ---
  // Once a processor holds the lock, it should eventually release it
  // (transition from Phase.lock to Phase.release) within a reasonable bound.
  astRelaxedLiveness(
    io.p0_req === Phase.lock,
    io.p0_req === Phase.release,
    20,
    "p0_lock_eventually_release"
  )
  astRelaxedLiveness(
    io.p1_req === Phase.lock,
    io.p1_req === Phase.release,
    20,
    "p1_lock_eventually_release"
  )
  astRelaxedLiveness(
    io.p2_req === Phase.lock,
    io.p2_req === Phase.release,
    20,
    "p2_lock_eventually_release"
  )
  astRelaxedLiveness(
    io.p3_req === Phase.lock,
    io.p3_req === Phase.release,
    20,
    "p3_lock_eventually_release"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new TreeArb4(), args)
}
