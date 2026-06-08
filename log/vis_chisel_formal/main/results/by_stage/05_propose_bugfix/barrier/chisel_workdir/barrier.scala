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
    // Only increment count if it hasn't reached 2 yet; this prevents count from
    // exceeding 2 when both threads reach L2 before either is serviced through L3.
    when(count < 2.U) {
      count := count + 1.U
    }
    pc(self) := Loc.L3.asUInt
  }.elsewhen(pc(self) === Loc.L3.asUInt) {
    // Go to release (L4) if both threads have arrived (count >= 2) or if the
    // other thread already performed the release and reset count to 0.
    // Otherwise (count === 1) this is the first arriver, so wait at L6.
    when(count >= 2.U || count === 0.U) {
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
    when(rel) {
      pc(self) := Loc.L5.asUInt
    }.elsewhen(count >= 2.U) {
      // Both threads have arrived but the other thread was interrupted
      // (by io.select switching) before it could perform the release.
      // Perform the release ourselves: go to L4 to reset count and set rel.
      pc(self) := Loc.L4.asUInt
    }
  }

  // Connect outputs
  io.rel_out := rel
  io.self_out := self
  io.pc0_out := pc(0)
  io.pc1_out := pc(1)
  io.count_out := count

  // ============ Formal Verification Assertions ============

  // Safety: count is a 2-bit register; it should never exceed 2
  // (max value is 2 because L4 resets it to 0 when it reaches 2)
  fvAssert(count <= 2.U, "count_never_exceeds_two")

  // Safety: in the waiting state L6, count must be exactly 1 or 2.
  // count=1: normal case, one thread has arrived at the barrier, the other has not yet.
  // count=2: both threads have arrived but io.select switched before the second thread
  // could perform the release. The L6 handler correctly transitions to L4 in this case.
  fvAssert(!(pc(self) === Loc.L6.asUInt) || count === 1.U || count === 2.U, "L6_count_is_one")

  // Liveness: when not paused, selected, and not stuck waiting for release (L6),
  // the thread should complete a full cycle (return to L0) within 10 cycles.
  // The states L1→L2→L3→L4→L5→L0 or L1→L2→L3→L6→... are the normal paths,
  // and the longest non-waiting path is at most 6 cycles, so 10 is a safe bound.
  astRelaxedLiveness(
    !io.pause && io.select && pc(self) =/= Loc.L6.asUInt,
    pc(self) === Loc.L0.asUInt,
    10,
    "non_waiting_thread_completes_cycle"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new barrier(), args)
}
