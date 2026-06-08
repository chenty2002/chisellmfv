package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

object arbit {
  // Enum for handshake types
  object HandShakeType extends ChiselEnum {
    val idle = Value(0.U)
    val request = Value(1.U)
    val lock = Value(2.U)
    val release = Value(3.U)
  }
}

// Simple model for a processor
class procModel extends Module {
  import arbit.HandShakeType
  
  val io = IO(new Bundle {
    val ack = Input(Bool())
    val req = Output(HandShakeType())
  })
  
  // State register
  val procState = RegInit(HandShakeType.idle)
  
  // Simple counter to simulate non-deterministic choice
  val randCounter = RegInit(0.U(3.W))
  randCounter := randCounter + 1.U
  
  // Output assignment
  io.req := procState
  
  // State machine logic
  when(procState === HandShakeType.idle && randCounter === 7.U) {
    procState := HandShakeType.request
  }.elsewhen(procState === HandShakeType.request && io.ack) {
    procState := HandShakeType.lock
  }.elsewhen(procState === HandShakeType.lock && randCounter > 3.U) {
    procState := HandShakeType.release
  }.elsewhen(procState === HandShakeType.release) {
    procState := HandShakeType.idle
  }
}

// Arbiter cell module
class arbitCell extends Module {
  import arbit.HandShakeType
  
  val io = IO(new Bundle {
    val topCell = Input(Bool())
    val urLeft = Input(HandShakeType())
    val urRight = Input(HandShakeType())
    val xa = Input(Bool())
    val uaLeft = Output(Bool())
    val uaRight = Output(Bool())
    val xr = Output(HandShakeType())
  })
  
  // Registers
  val prevLeft = RegInit(false.B)
  val prevRight = RegInit(true.B)
  val processedLeft = RegInit(false.B)
  val processedRight = RegInit(false.B)
  val holdToken = RegInit(io.topCell) // Initial value depends on topCell
  
  // Wire definitions
  val mustGiveParent = (processedLeft && processedRight && !io.topCell)
  val childOwns = (io.urLeft === HandShakeType.lock || io.urRight === HandShakeType.lock)
  val giveChild = (io.uaLeft || io.uaRight)
  
  // Combinational logic for uaLeft
  io.uaLeft := (!mustGiveParent && holdToken && (io.urLeft === HandShakeType.request) && 
                (!(io.urRight === HandShakeType.request) || prevRight))
  
  // Combinational logic for uaRight
  io.uaRight := (!mustGiveParent && holdToken && (io.urRight === HandShakeType.request) && 
                 (!(io.urLeft === HandShakeType.request) || prevLeft))
  
  // Combinational logic for xr
  when(!holdToken && (io.urLeft === HandShakeType.request || io.urRight === HandShakeType.request)) {
    io.xr := HandShakeType.request
  }.elsewhen(childOwns) {
    io.xr := HandShakeType.lock
  }.elsewhen(holdToken && ((mustGiveParent || 
                           !(io.urLeft === HandShakeType.request || io.urRight === HandShakeType.request)) && 
                           !io.topCell)) {
    io.xr := HandShakeType.release
  }.otherwise {
    io.xr := HandShakeType.idle
  }
  
  // Sequential logic
  when(io.xa) {
    holdToken := true.B
  }.elsewhen(giveChild) {
    holdToken := false.B
  }.elsewhen(io.urLeft === HandShakeType.release || io.urRight === HandShakeType.release) {
    holdToken := true.B
  }.elsewhen(io.xr === HandShakeType.release) {
    holdToken := false.B
  }
  
  // Update prevLeft and prevRight
  when(io.uaLeft) {
    prevLeft := true.B
    prevRight := false.B
  }.elsewhen(io.uaRight) {
    prevLeft := false.B
    prevRight := true.B
  }
  
  // Update processed flags
  when(io.urLeft === HandShakeType.release) {
    processedLeft := true.B
  }.elsewhen(io.urRight === HandShakeType.release) {
    processedRight := true.B
  }.elsewhen(processedLeft && processedRight) {
    processedLeft := false.B
    processedRight := false.B
  }
}

// Main module
class main extends Module with Formal {
  import arbit.HandShakeType
  
  val io = IO(new Bundle {
    // Add outputs to preserve the design
    val ua1 = Output(Bool())
    val ua2 = Output(Bool())
    val ua3 = Output(Bool())
    val ua4 = Output(Bool())
    val xa = Output(Bool())
    val ya = Output(Bool())
    val sa = Output(Bool())
    val ur1 = Output(HandShakeType())
    val ur2 = Output(HandShakeType())
    val ur3 = Output(HandShakeType())
    val ur4 = Output(HandShakeType())
    val xr = Output(HandShakeType())
    val yr = Output(HandShakeType())
    val sr = Output(HandShakeType())
  })
  
  // Constants
  val constTRUE = true.B
  val constFALSE = false.B
  
  // Signals
  val ua1 = Wire(Bool())
  val ua2 = Wire(Bool())
  val ua3 = Wire(Bool())
  val ua4 = Wire(Bool())
  val xa = Wire(Bool())
  val ya = Wire(Bool())
  val sa = Wire(Bool())
  val ur1 = Wire(HandShakeType())
  val ur2 = Wire(HandShakeType())
  val ur3 = Wire(HandShakeType())
  val ur4 = Wire(HandShakeType())
  val xr = Wire(HandShakeType())
  val yr = Wire(HandShakeType())
  val sr = Wire(HandShakeType())
  
  sa := constFALSE
  
  // Instantiate arbitCell C0
  val C0 = Module(new arbitCell())
  C0.io.topCell := constTRUE
  C0.io.urLeft := xr
  C0.io.urRight := yr
  C0.io.xa := sa  // Fixed: sa is Bool, xa is Bool
  xa := C0.io.uaLeft
  ya := C0.io.uaRight
  sr := C0.io.xr
  
  // Instantiate arbitCell C1
  val C1 = Module(new arbitCell())
  C1.io.topCell := constFALSE
  C1.io.urLeft := ur1
  C1.io.urRight := ur2
  C1.io.xa := xa
  ua1 := C1.io.uaLeft
  ua2 := C1.io.uaRight
  xr := C1.io.xr
  
  // Instantiate arbitCell C2
  val C2 = Module(new arbitCell())
  C2.io.topCell := constFALSE
  C2.io.urLeft := ur3
  C2.io.urRight := ur4
  C2.io.xa := ya
  ua3 := C2.io.uaLeft
  ua4 := C2.io.uaRight
  yr := C2.io.xr
  
  // Instantiate procModel P1
  val P1 = Module(new procModel())
  P1.io.ack := ua1
  ur1 := P1.io.req
  
  // Instantiate procModel P2
  val P2 = Module(new procModel())
  P2.io.ack := ua2
  ur2 := P2.io.req
  
  // Instantiate procModel P3
  val P3 = Module(new procModel())
  P3.io.ack := ua3
  ur3 := P3.io.req
  
  // Instantiate procModel P4
  val P4 = Module(new procModel())
  P4.io.ack := ua4
  ur4 := P4.io.req
  
  // Connect outputs for preservation
  io.ua1 := ua1
  io.ua2 := ua2
  io.ua3 := ua3
  io.ua4 := ua4
  io.xa := xa
  io.ya := ya
  io.sa := sa
  io.ur1 := ur1
  io.ur2 := ur2
  io.ur3 := ur3
  io.ur4 := ur4
  io.xr := xr
  io.yr := yr
  io.sr := sr

  // ============================================================
  // FORMAL ASSERTIONS
  // ============================================================

  // SAFETY: Mutual exclusion on processor grants
  // At most one of the four grant signals can be active at any time
  assertMutex(Seq(ua1, ua2, ua3, ua4), "mutex_all_grants")

  // SAFETY: Subtree mutual exclusion
  // Within each arbitCell subtree, at most one child grant is active
  assertMutex(Seq(ua1, ua2), "mutex_c1_subtree_grants")
  assertMutex(Seq(ua3, ua4), "mutex_c2_subtree_grants")

  // SAFETY: Grant validity — a grant is only issued when the
  // corresponding processor is actively in the request handshake state
  fvAssert(!ua1 || ur1 === HandShakeType.request, "grant1_implies_request")
  fvAssert(!ua2 || ur2 === HandShakeType.request, "grant2_implies_request")
  fvAssert(!ua3 || ur3 === HandShakeType.request, "grant3_implies_request")
  fvAssert(!ua4 || ur4 === HandShakeType.request, "grant4_implies_request")

  // LIVENESS: Every request eventually receives a grant (bounded)
  // Once a processor enters the request state, a grant must appear
  // within 30 cycles (covers tree propagation + lock-holder release)
  astRelaxedLiveness(ur1 === HandShakeType.request, ua1, 30, "liveness_proc1")
  astRelaxedLiveness(ur2 === HandShakeType.request, ua2, 30, "liveness_proc2")
  astRelaxedLiveness(ur3 === HandShakeType.request, ua3, 30, "liveness_proc3")
  astRelaxedLiveness(ur4 === HandShakeType.request, ua4, 30, "liveness_proc4")

  // PROTOCOL: Release state cleanly transitions to idle in the next
  // cycle (the procModel unconditionally leaves release for idle)
  assertNextStepWhen(ur1 === HandShakeType.release, ur1 === HandShakeType.idle, "release_to_idle_p1")
  assertNextStepWhen(ur2 === HandShakeType.release, ur2 === HandShakeType.idle, "release_to_idle_p2")
  assertNextStepWhen(ur3 === HandShakeType.release, ur3 === HandShakeType.idle, "release_to_idle_p3")
  assertNextStepWhen(ur4 === HandShakeType.release, ur4 === HandShakeType.idle, "release_to_idle_p4")
}

// Verilog generator
object VerilogGenerator extends App {
  emitVerilog(new main(), Array("--target-dir", "generated"))
}
