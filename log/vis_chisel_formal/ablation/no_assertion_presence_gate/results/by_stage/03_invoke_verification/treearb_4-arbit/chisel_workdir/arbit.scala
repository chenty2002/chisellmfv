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

  // ========== FORMAL ASSERTIONS ==========

  // SAFETY 1: Mutual exclusion — at most one leaf grant active at any time
  // This is the fundamental correctness property of any arbiter: no two
  // downstream ports receive a grant simultaneously.
  assertOneHot0(Cat(ua1, ua2, ua3, ua4), "ua_mutex_at_most_one_grant")

  // SAFETY 2: C0 (top cell) uaLeft/uaRight mutual exclusion
  // xa (grant to C1) and ya (grant to C2) must never both be high, because
  // C0's internal grant logic uses holdToken as a guard.
  fvAssert(!(xa && ya), "C0_xa_ya_mutex_no_parallel_grants")

  // LIVENESS 3-6: Bounded liveness for each processor
  // Every request issued by a processor must be granted (ua asserted) within
  // a bounded number of cycles.  The processor model stays in HandShakeType.request
  // until ack is received, so the assertion triggers every cycle the request is
  // outstanding; the bound of 50 cycles is generous for a 4-leaf tree arbiter.
  astRelaxedLiveness(ur1 === HandShakeType.request, ua1, 50, "P1_request_granted_within_50")
  astRelaxedLiveness(ur2 === HandShakeType.request, ua2, 50, "P2_request_granted_within_50")
  astRelaxedLiveness(ur3 === HandShakeType.request, ua3, 50, "P3_request_granted_within_50")
  astRelaxedLiveness(ur4 === HandShakeType.request, ua4, 50, "P4_request_granted_within_50")
}

// Verilog generator
object VerilogGenerator extends App {
  emitVerilog(new main(), args)
}
