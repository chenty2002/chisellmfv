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
    count := count + 1.U
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

  // ===== Formal Verification Assertions =====

  // --- SAFETY: Count bounds ---
  // The FSM only increments count at L2 (to max 2) and resets it at L4.
  // count should never reach 3 (value 3 would indicate a count overflow bug).
  fvAssert(count <= 2.U, "count_never_exceeds_2")

  // --- SAFETY: PC validity ---
  // Both lanes' program counters must always hold valid Loc enum values (0-6).
  // Value 7 is invalid and would indicate an FSM error.
  fvAssert(pc(0) <= Loc.L6.asUInt, "pc0_valid_enum_range")
  fvAssert(pc(1) <= Loc.L6.asUInt, "pc1_valid_enum_range")

  // --- SAFETY: rel consistency at L1 ---
  // When the selected lane is at L1, rel must be set to false in the same cycle.
  // This guarantees rel is cleared before the counting phase begins.
  fvAssert(
    !(pc(self) === Loc.L1.asUInt) || !rel,
    "rel_cleared_at_L1"
  )

  // --- SAFETY: rel consistency at L4 ---
  // When the selected lane is at L4, rel must be set to true in the same cycle.
  // This guarantees rel is asserted after count reaches 2.
  fvAssert(
    !(pc(self) === Loc.L4.asUInt) || rel,
    "rel_set_at_L4"
  )

  // --- LIVENESS: Progress from L0 to L5 ---
  // When a lane enters L0 (not paused), it should eventually reach L5
  // within 20 cycles. The path L0→L1→L2→L3→L6→(wait rel)→L5 or
  // L0→L1→L2→L3→L4→L5 takes at most a few cycles per iteration,
  // and count resets may require up to 2 iterations (each ~6 cycles).
  // A bound of 20 is generous for this state-space diameter.
  astRelaxedLiveness(
    pc(self) === Loc.L0.asUInt && !io.pause,
    pc(self) === Loc.L5.asUInt,
    20,
    "lane_progress_L0_to_L5_within_20"
  )

  // --- LIVENESS: Forward progress from any non-terminal state ---
  // The FSM should not stall indefinitely at L6 waiting for rel.
  // If a lane is at L6, rel should eventually become true so the lane
  // can progress to L5. Bound of 20 accounts for the other lane needing
  // to complete its iteration to set rel=true.
  astRelaxedLiveness(
    pc(self) === Loc.L6.asUInt,
    pc(self) === Loc.L5.asUInt,
    20,
    "lane_progress_L6_to_L5_within_20"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new barrier(), args)
}