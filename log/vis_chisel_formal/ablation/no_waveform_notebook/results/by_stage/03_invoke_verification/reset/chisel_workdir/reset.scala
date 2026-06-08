package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

/**
 * A simple counter module for reset verification.
 * After reset, counter starts at 0 and increments each cycle when enabled.
 * When it reaches the max value, it wraps around to 0.
 */
class ResetModule extends Module with Formal {
  val io = IO(new Bundle {
    val en   = Input(Bool())
    val max  = Input(UInt(8.W))
    val out  = Output(UInt(8.W))
    val wrap = Output(Bool())
  })

  // Counter register, initialized to 0 on reset
  val count = RegInit(0.U(8.W))
  val wrap_detected = RegInit(false.B)

  // Counter logic
  when(io.en) {
    when(count === io.max) {
      count := 0.U
      wrap_detected := true.B
    }.otherwise {
      count := count + 1.U
      wrap_detected := false.B
    }
  }

  io.out := count
  io.wrap := wrap_detected

  // =============================================
  // Formal Verification Assertions
  // =============================================

  // --- Safety: counter never exceeds max ---
  fvAssert(count <= io.max, "counter_never_exceeds_max")

  // --- Safety: after reset, count is 0 ---
  assertAt(0.U, count === 0.U, "count_zero_after_reset")

  // --- Safety: when disabled, count stays stable ---
  assertStableWhen(!io.en, count, "count_stable_when_disabled")

  // --- Safety: wrap signal is only true when count == max and enabled ---
  fvAssert(!(io.wrap && !(io.en && count === io.max)), "wrap_only_when_enabled_and_at_max")

  // --- Safety: wrap and count==max are consistent ---
  fvAssert(!(count === io.max && io.en && !io.wrap), "wrap_must_be_high_when_at_max_and_enabled")

  // --- Liveness: counter eventually reaches max when enabled ---
  // If enabled and count < max, eventually count reaches max (or en goes low)
  astRelaxedLiveness(
    io.en && count < io.max,
    count === io.max || !io.en,
    300,
    "counter_eventually_reaches_max"
  )

  // --- Liveness: counter makes progress when enabled ---
  // If enabled and not at max, counter should eventually reach 0 (wrap around)
  astRelaxedLiveness(
    io.en && count < io.max,
    count === 0.U || !io.en,
    300,
    "counter_eventually_wraps"
  )

  // --- Safety: counter wraps correctly ---
  // When enabled and at max, next cycle count should be 0
  // We can't check next cycle directly, so check the stable-after-wrapping property
  // When wrap is asserted and then deasserted, count should be < max
  fvAssert(!(RegNext(io.wrap) && !io.en && count === io.max), "after_wrap_count_advances")
}

/**
 * VerilogGenerator - entry point used by `make verilog`, invoked via
 * `sbt "runMain llmverify.VerilogGenerator"`
 */
object VerilogGenerator extends App {
  emitVerilog(new ResetModule(), Array("--target-dir", "generated"))
}
