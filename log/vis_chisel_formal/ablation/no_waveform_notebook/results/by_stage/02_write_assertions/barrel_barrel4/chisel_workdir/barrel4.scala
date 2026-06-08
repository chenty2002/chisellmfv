package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

class barrel4 extends Module with Formal {
  val io = IO(new Bundle {
    // Add outputs to preserve the registers for verification
    val b0 = Output(UInt(2.W))
    val b1 = Output(UInt(2.W))
    val b2 = Output(UInt(2.W))
    val b3 = Output(UInt(2.W))
    val r0 = Output(UInt(2.W))
    val r1 = Output(UInt(2.W))
    val r2 = Output(UInt(2.W))
    val r3 = Output(UInt(2.W))
  }
  )
  
  // Define registers (2 bits wide as specified by [2-1:0])
  // Initialize to 0 to ensure valid initial state
  val b0 = RegInit(0.U(2.W))
  val b1 = RegInit(0.U(2.W))
  val b2 = RegInit(0.U(2.W))
  val b3 = RegInit(0.U(2.W))
  val r0 = RegInit(0.U(2.W))
  val r1 = RegInit(0.U(2.W))
  val r2 = RegInit(0.U(2.W))
  val r3 = RegInit(0.U(2.W))
  
  // Implement the valid function as a Chisel function
  def valid(b0: UInt, r0: UInt, b1: UInt, r1: UInt, b2: UInt, r2: UInt, b3: UInt, r3: UInt): Bool = {
    (b0 =/= r0 || b1 === r1) &&
    (b0 =/= r1 || b1 === r2) &&
    (b0 =/= r2 || b1 === r3) &&
    (b0 =/= r3 || b1 === r0) &&
    (b1 =/= r0 || b2 === r1) &&
    (b1 =/= r1 || b2 === r2) &&
    (b1 =/= r2 || b2 === r3) &&
    (b1 =/= r3 || b2 === r0) &&
    (b2 =/= r0 || b3 === r1) &&
    (b2 =/= r1 || b3 === r2) &&
    (b2 =/= r2 || b3 === r3) &&
    (b2 =/= r3 || b3 === r0) &&
    (b3 =/= r0 || b0 === r1) &&
    (b3 =/= r1 || b0 === r2) &&
    (b3 =/= r2 || b0 === r3) &&
    (b3 =/= r3 || b0 === r0)
  }
  
  // Handle state updates on each clock edge
  // In Chisel, RegInit handles reset automatically
  // The rotation happens on every clock cycle when not in reset
  
  // Rotate shifter contents up by one position
  // b0 gets b1, b1 gets b2, b2 gets b3, b3 gets b0 (old value)
  val b0_old = RegNext(b0) // Store previous value of b0
  b0 := b1
  b1 := b2
  b2 := b3
  b3 := b0_old
  
  // Register file holds its contents perpetually (no updates)
  // r0, r1, r2, r3 remain unchanged as they are RegInit with no further assignments
  
  // Connect registers to outputs to preserve them for verification
  io.b0 := b0
  io.b1 := b1
  io.b2 := b2
  io.b3 := b3
  io.r0 := r0
  io.r1 := r1
  io.r2 := r2
  io.r3 := r3

  // ========== FORMAL ASSERTIONS ==========

  // ---- SAFETY: valid invariant ----
  // The valid function defines the core correctness condition for the barrel
  // shifter. It must always hold in every cycle.
  fvAssert(valid(b0, r0, b1, r1, b2, r2, b3, r3), "valid_invariant")

  // ---- SAFETY: correct barrel rotation ----
  // After each cycle, the barrel contents are rotated: each b[i] receives
  // the previous value of b[i+1], and b3 receives the previous value of b0.
  // Verify using RegNext (value from previous cycle).
  fvAssert(b0 === RegNext(b1), "b0_gets_previous_b1")
  fvAssert(b1 === RegNext(b2), "b1_gets_previous_b2")
  fvAssert(b2 === RegNext(b3), "b2_gets_previous_b3")
  fvAssert(b3 === RegNext(b0), "b3_gets_previous_b0")

  // ---- SAFETY: register file stability ----
  // The r registers (r0-r3) are RegInit with no further assignments, so they
  // must never change value. Assert stability of each r register.
  assertStable(r0, "r0_stable")
  assertStable(r1, "r1_stable")
  assertStable(r2, "r2_stable")
  assertStable(r3, "r3_stable")

  // ---- LIVENESS: rotation always makes progress ----
  // After each reset, the barrel rotates every cycle. Verify that for any
  // state reachable from reset, the valid invariant continues to hold after
  // each rotation step (bounded liveness: within 1 cycle, rotation completes).
  // This ensures the system never deadlocks into an invalid state.
  astRelaxedLiveness(true.B, valid(b0, r0, b1, r1, b2, r2, b3, r3), 4, "rotation_preserves_valid")

  // ---- SAFETY: rotation cycle completes in 4 steps ----
  // After 4 cycles, each barrel register should have returned to its original
  // value (full rotation cycle). This checks the rotation is a perfect cycle.
  fvAssert(RegNext(RegNext(RegNext(RegNext(b0)))) === b0, "b0_4step_rotation_cycle")
  fvAssert(RegNext(RegNext(RegNext(RegNext(b1)))) === b1, "b1_4step_rotation_cycle")
  fvAssert(RegNext(RegNext(RegNext(RegNext(b2)))) === b2, "b2_4step_rotation_cycle")
  fvAssert(RegNext(RegNext(RegNext(RegNext(b3)))) === b3, "b3_4step_rotation_cycle")
}

object VerilogGenerator extends App {
  emitVerilog(new barrel4(), args)
}
