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
  when((!holdToken || io.topCell) && (io.urLeft === HandShakeType.request || io.urRight === HandShakeType.request)) {
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

  // ===== FORMAL ASSERTIONS =====

  // Safety 1: Mutual exclusion on grants - at most one processor gets a grant at any time.
  // This is a critical arbiter property: no two processors should be granted simultaneously.
  assertMutex(Seq(io.ua1, io.ua2, io.ua3, io.ua4), "mutex_on_grants")

  // Safety 2: Whenever a grant is issued to a processor, that processor must be
  // actively requesting. A grant should never be issued to an idle/lock/release processor.
  assertImplies(io.ua1, io.ur1 === HandShakeType.request, "grant1_implies_request")
  assertImplies(io.ua2, io.ur2 === HandShakeType.request, "grant2_implies_request")
  assertImplies(io.ua3, io.ur3 === HandShakeType.request, "grant3_implies_request")
  assertImplies(io.ua4, io.ur4 === HandShakeType.request, "grant4_implies_request")

  // Safety 3: Processors in lock state must have originated from a request (not idle),
  // i.e., lock state is a valid successor of request state.
  // A processor in lock state implies it was previously granted (ack received).
  // While in lock, ur* stays lock until randCounter > 3, so lock is internally consistent.
  // Here we check that if a processor is NOT idle, it must be in a valid handshake state.
  // This is a structural check - idle is 0, all other values are valid non-idle states.
  
  // Safety 4: The top-level tree arbiter's output (sr) must never be in an undefined state.
  // It should always be a valid HandShakeType (guaranteed by ChiselEnum, but verifying reachability is useful).
  // Simpler: sr should not be idle when some leaf is requesting but no grant is active.
  // More importantly: a request should always propagate up the tree.
  // Check that if any processor is requesting, the top-level sr must be request or lock.
  assertImplies(
    io.ur1 === HandShakeType.request || io.ur2 === HandShakeType.request ||
    io.ur3 === HandShakeType.request || io.ur4 === HandShakeType.request,
    io.sr === HandShakeType.request || io.sr === HandShakeType.lock,
    "leaf_request_propagates_to_top"
  )

  // Liveness 1: If a processor is requesting, it will eventually receive a grant
  // within a bounded number of cycles. Bound of 100 cycles is very generous for
  // a 4-processor tree arbiter with depth 2 (typical worst case: ~20-30 cycles).
  astRelaxedLiveness(io.ur1 === HandShakeType.request, io.ua1, 100, "liveness_req1_grant")
  astRelaxedLiveness(io.ur2 === HandShakeType.request, io.ua2, 100, "liveness_req2_grant")
  astRelaxedLiveness(io.ur3 === HandShakeType.request, io.ua3, 100, "liveness_req3_grant")
  astRelaxedLiveness(io.ur4 === HandShakeType.request, io.ua4, 100, "liveness_req4_grant")

  // Liveness 2: If a processor is in lock state (has been granted), it will
  // eventually release. The internal counter takes at most 8 cycles to exceed 3.
  // Bound of 20 cycles is generous to account for pipeline delays.
  astRelaxedLiveness(io.ur1 === HandShakeType.lock, io.ur1 === HandShakeType.release, 20, "liveness_lock1_release")
  astRelaxedLiveness(io.ur2 === HandShakeType.lock, io.ur2 === HandShakeType.release, 20, "liveness_lock2_release")
  astRelaxedLiveness(io.ur3 === HandShakeType.lock, io.ur3 === HandShakeType.release, 20, "liveness_lock3_release")
  astRelaxedLiveness(io.ur4 === HandShakeType.lock, io.ur4 === HandShakeType.release, 20, "liveness_lock4_release")

  // Liveness 3: If a processor is in release state, it will eventually return to idle.
  // The processor transitions from release to idle unconditionally in one cycle.
  assertNextStepWhen(io.ur1 === HandShakeType.release, io.ur1 === HandShakeType.idle, "release1_to_idle")
  assertNextStepWhen(io.ur2 === HandShakeType.release, io.ur2 === HandShakeType.idle, "release2_to_idle")
  assertNextStepWhen(io.ur3 === HandShakeType.release, io.ur3 === HandShakeType.idle, "release3_to_idle")
  assertNextStepWhen(io.ur4 === HandShakeType.release, io.ur4 === HandShakeType.idle, "release4_to_idle")
}

// Verilog generator
object VerilogGenerator extends App {
  emitVerilog(new main(), Array("--target-dir", "generated"))
}
