package llmverify
import chisel3._
import chisel3.util._
import chiselFv._

// Enum for locations
object Loc extends ChiselEnum {
  val L0, L1, L2, L3, L4, L5, L6 = Value
}

class barrier extends Module with Formal {
  val io = IO(new Bundle {
    val select = Input(Bool())
    val pause = Input(Bool())
    // Add outputs to preserve the design
    val rel_out = Output(Bool())
    val self_out = Output(Bool())
    val pc0_out = Output(UInt(3.W))
    val pc1_out = Output(UInt(3.W))
    val count_out = Output(UInt(2.W))
  })

  // Registers - use Bool for 1-bit signals
  val rel = RegInit(false.B)  // Changed from UInt(1.W) to Bool
  val self = RegInit(false.B)  // Initialize to false, will be updated on first clock
  val pc = RegInit(VecInit(Seq(Loc.L0.asUInt, Loc.L0.asUInt)))
  val count = RegInit(0.U(2.W))

  // State machine logic
  self := io.select

  // Use when/elsewhen/otherwise instead of switch to handle dynamic conditions
  when(pc(self) === Loc.L0.asUInt) {
    when(!io.pause) {
      pc(self) := Loc.L1.asUInt
    }
  }.elsewhen(pc(self) === Loc.L1.asUInt) {
    rel := false.B
    pc(self) := Loc.L2.asUInt
  }.elsewhen(pc(self) === Loc.L2.asUInt) {
    when(count < 2.U) { count := count + 1.U }
    pc(self) := Loc.L3.asUInt
  }.elsewhen(pc(self) === Loc.L3.asUInt) {
    when(count === 2.U) {
      pc(self) := Loc.L4.asUInt
    }.otherwise {
      pc(self) := Loc.L6.asUInt
    }
  }.elsewhen(pc(self) === Loc.L4.asUInt) {
    count := 0.U
    rel := true.B
    pc(self) := Loc.L5.asUInt
  }.elsewhen(pc(self) === Loc.L5.asUInt) {
    pc(self) := Loc.L0.asUInt
  }.elsewhen(pc(self) === Loc.L6.asUInt) {
    when(rel) {  // Now rel is a Bool, so this works
      pc(self) := Loc.L5.asUInt
    }
  }

  // Connect outputs
  io.rel_out := rel
  io.self_out := self
  io.pc0_out := pc(0)
  io.pc1_out := pc(1)
  io.count_out := count

  // ========== Formal Verification Assertions ==========

  // Safety 1: pc values must always be valid Loc enum values (L0 through L6, i.e., 0 to 6)
  fvAssert(pc(0) <= Loc.L6.asUInt && pc(1) <= Loc.L6.asUInt, "valid_pc_states")

  // Safety 2: count must never exceed 2 (the barrier maximum for two threads)
  fvAssert(count <= 2.U, "count_bound")

  // Safety 3: When the active thread reaches L4, rel must be set in the next cycle.
  // At L4, the design issues rel := true.B, so this checks the correct sequencing
  // of the barrier release handshake.
  assertImpliesDelay(pc(self) === Loc.L4.asUInt, rel, 1, "rel_set_at_L4")

  // Liveness 4: When both threads have arrived at the barrier (count reaches 2),
  // the release signal (rel) must be asserted within 10 cycles.
  // This catches deadlock or starvation where the barrier fails to release
  // after both participants have synchronised.
  astRelaxedLiveness(count === 2.U, rel, 10, "barrier_releases")

  // Liveness 5: The active thread at the barrier point (L3 with count===2) must
  // proceed to L4 within 1 cycle, ensuring the barrier crossing is not stuck.
  assertImpliesDelay(pc(self) === Loc.L3.asUInt && count === 2.U, pc(self) === Loc.L4.asUInt, 1, "barrier_cross")
}

object VerilogGenerator extends App {
  emitVerilog(new barrier(), args)
}
