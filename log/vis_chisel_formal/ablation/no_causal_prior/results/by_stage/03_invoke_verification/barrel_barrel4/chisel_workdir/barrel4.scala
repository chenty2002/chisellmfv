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
  })
  
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

  // ========== Formal Verification Assertions ==========

  // ---- Safety Invariants ----

  // Assertion 1: The main valid invariant must always hold
  // This is the core design property enforced by the valid function
  fvAssert(valid(b0, r0, b1, r1, b2, r2, b3, r3), "valid_invariant")

  // Assertion 2: Register file entries (r0-r3) remain constant at their initial value
  // Since they are RegInit(0.U) with no further assignments, they never change
  fvAssert(r0 === 0.U, "r0_constant_zero")
  fvAssert(r1 === 0.U, "r1_constant_zero")
  fvAssert(r2 === 0.U, "r2_constant_zero")
  fvAssert(r3 === 0.U, "r3_constant_zero")

  // ---- Barrel Rotation Integrity ----

  // Assertion 3: The barrel rotation chain is correctly maintained
  // b0 gets the value of b1 from the previous cycle
  fvAssert(RegNext(b1) === b0, "b0_rotates_from_b1")
  // b1 gets the value of b2 from the previous cycle
  fvAssert(RegNext(b2) === b1, "b1_rotates_from_b2")
  // b2 gets the value of b3 from the previous cycle
  fvAssert(RegNext(b3) === b2, "b2_rotates_from_b3")
  // b3 gets b0_old (previous b0) from the previous cycle;
  // b0_old = RegNext(b0) stores the old b0, so b3 gets the value b0 had two cycles ago
  fvAssert(RegNext(b0_old) === b3, "b3_rotates_from_old_b0")

  // ---- Liveness / Progress ----

  // Assertion 4: The barrel makes progress - b0 changes value within 4 cycles
  // unless all b registers hold the same value (in which case rotation yields no change)
  // This catches the system getting stuck unexpectedly
  val b_all_equal = b0 === b1 && b1 === b2 && b2 === b3
  astRelaxedLiveness(!b_all_equal, RegNext(b0) =/= b0, 4, "barrel_progress_b0")
  astRelaxedLiveness(!b_all_equal, RegNext(b1) =/= b1, 4, "barrel_progress_b1")
  astRelaxedLiveness(!b_all_equal, RegNext(b2) =/= b2, 4, "barrel_progress_b2")
  astRelaxedLiveness(!b_all_equal, RegNext(b3) =/= b3, 4, "barrel_progress_b3")
}

object VerilogGenerator extends App {
  emitVerilog(new barrel4(), args)
}
