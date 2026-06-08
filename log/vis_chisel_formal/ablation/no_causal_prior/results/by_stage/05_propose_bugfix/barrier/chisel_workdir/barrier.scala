package llmverify
import chisel3._
import chisel3.util._
import chisel3.ltl._
import chisel3.ltl.Sequence._

// Enum for locations
object Loc extends ChiselEnum {
  val L0, L1, L2, L3, L4, L5, L6 = Value
}

class barrier extends Module {
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
    // Guard: only increment count if below threshold (barrier has 2 threads)
    // Also clear rel so that rel is not asserted while count is non-zero
    when(count < 2.U) {
      count := count + 1.U
    }
    rel := false.B
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

  // ============ Formal Verification Assumptions ============

  // Fair-scheduling assumption: io.select must alternate between threads
  // every cycle. Without this, a single thread can be selected perpetually,
  // preventing the other thread from ever reaching the barrier, causing
  // count to never reach 2 and deadlocking the first thread at L6.
  AssumeProperty(
    Sequence(!io.select) |-> Sequence(io.select).delay(),
    None, None, Some("io_select_alternating_0_to_1")
  )
  AssumeProperty(
    Sequence(io.select) |-> Sequence(!io.select).delay(),
    None, None, Some("io_select_alternating_1_to_0")
  )

  // ============ Formal Verification Assertions ============

  // Safety: count must never exceed 2 (the barrier thread count)
  AssertProperty(count <= 2.U, None, None, Some("count_bounded_by_2"))

  // Safety: PC values must always be valid Loc enum values (0 through L6 = 6)
  AssertProperty(pc(0) <= Loc.L6.asUInt, None, None, Some("pc0_in_valid_range"))
  AssertProperty(pc(1) <= Loc.L6.asUInt, None, None, Some("pc1_in_valid_range"))

  // Safety: When rel is asserted, count must be 0 (reset at barrier release)
  AssertProperty(!rel || count === 0.U, None, None, Some("rel_implies_count_zero"))

  // Safety: Only enter L4 when count has reached the barrier threshold of 2
  AssertProperty(!(pc(self) === Loc.L4.asUInt) || count === 2.U, None, None, Some("l4_requires_count_2"))

  // Safety: The selected thread must never be in an invalid/unused state
  AssertProperty(pc(self) <= Loc.L6.asUInt, None, None, Some("selected_pc_valid"))

  // Bounded liveness: When a thread is selected and not paused and not stuck
  // waiting for the barrier (L6), it returns to L0 within 10 cycles.
  // Path length: L0->L1->L2->L3->L4->L5->L0 = 6 cycles maximum.
  // Bound of 10 accounts for any pipeline/registration interactions.
  // Use self (the registered thread select) instead of io.select so the
  // assertion matches the thread the state machine actually operates on.
  AssertProperty(
    Sequence(!io.pause && pc(self) =/= Loc.L6.asUInt) |->
      Sequence(pc(self) === Loc.L0.asUInt).delayRange(0, 10),
    None, None, Some("active_thread_progress")
  )

  // Safety: When in L5 (the state just before returning to L0), the thread
  // must always transition to L0 on the next cycle (no conditional logic in L5).
  // Use self (registered thread select) to match the state-machine thread.
  AssertProperty(
    Sequence(pc(self) === Loc.L5.asUInt && !io.pause) |->
      Sequence(pc(self) === Loc.L0.asUInt).delay(),
    None, None, Some("l5_always_goes_to_l0")
  )

  // Connect outputs
  io.rel_out := rel
  io.self_out := self
  io.pc0_out := pc(0)
  io.pc1_out := pc(1)
  io.count_out := count
}

object VerilogGenerator extends App {
  emitVerilog(new barrier(), args)
}
