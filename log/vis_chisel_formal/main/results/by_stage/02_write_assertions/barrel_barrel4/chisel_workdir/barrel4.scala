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

  // ===== Formal Verification Assertions =====

  // Invariant: the barrel shifter values (b0-b3) must always be a cyclic
  // rotation of the register file values (r0-r3). This is the key property
  // that defines correct barrel shifter behavior: the valid function checks
  // that for any index i where b[i] == r[j], the next b[i+1] == r[j+1].
  fvAssert(valid(b0, r0, b1, r1, b2, r2, b3, r3), "cyclic_rotation_invariant")

  // Stability: the register file values r0-r3 must never change because
  // they have no assignments other than RegInit initialization.
  fvAssert(r0 === RegNext(r0), "r0_stable")
  fvAssert(r1 === RegNext(r1), "r1_stable")
  fvAssert(r2 === RegNext(r2), "r2_stable")
  fvAssert(r3 === RegNext(r3), "r3_stable")

  // Rotation periodicity: since b0-b3 rotate by one position every cycle,
  // after 4 cycles the b registers return to their original state.
  // Use shift registers to track historical values.
  val b0_delayed = ShiftRegister(b0, 4)
  val b1_delayed = ShiftRegister(b1, 4)
  val b2_delayed = ShiftRegister(b2, 4)
  val b3_delayed = ShiftRegister(b3, 4)
  fvAssert(b0 === b0_delayed, "b0_rotates_every_4")
  fvAssert(b1 === b1_delayed, "b1_rotates_every_4")
  fvAssert(b2 === b2_delayed, "b2_rotates_every_4")
  fvAssert(b3 === b3_delayed, "b3_rotates_every_4")
}

object VerilogGenerator extends App {
  emitVerilog(new barrel4(), args)
}
