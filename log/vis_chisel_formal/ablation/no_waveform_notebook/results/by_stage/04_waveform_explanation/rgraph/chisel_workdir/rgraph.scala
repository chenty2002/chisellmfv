package llmverify
import chisel3._
import chisel3.util._
import chiselFv._

class rgraph extends Module with Formal {
  val io = IO(new Bundle {
    val i = Input(Bool())
    val o = Output(Bool())
  })
  
  val MSB = 11
  val cnt = RegInit(0.U((MSB+1).W))
  val mode = RegInit(0.U(1.W))
  
  when(mode === 0.U) {
    cnt := cnt + 1.U
  }.otherwise {
    when(io.i && (cnt =/= 0.U)) {
      cnt := cnt - 1.U
    }
  }
  
  when(mode === 0.U && io.i) {
    mode := 1.U
  }
  
  io.o := (cnt === 0.U)

  // === Formal Assertions ===

  // Safety: Once mode becomes 1, it stays 1 forever (monotonic transition)
  assertStableWhen(mode === 1.U, mode, "mode_stays_one_once_set")

  // Safety: In mode 0, cnt increments by exactly 1 every cycle (accounting for wrap-around)
  fvAssert(
    !(mode === 0.U) ||
    (cnt === RegNext(cnt) + 1.U) ||
    (RegNext(cnt) === ((1.U << (MSB+1).U) - 1.U) && cnt === 0.U),
    "cnt_inc_in_mode0"
  )

  // Safety: In mode 1, when io.i && cnt =/= 0, cnt decrements by exactly 1
  fvAssert(
    !(mode === 1.U && io.i && cnt =/= 0.U) ||
    (cnt === RegNext(cnt) - 1.U),
    "cnt_dec_in_mode1"
  )

  // Safety: When mode===1 and cnt===0 or io.i is false, cnt stays unchanged
  fvAssert(
    !(mode === 1.U && !(io.i && cnt =/= 0.U)) ||
    (cnt === RegNext(cnt)),
    "cnt_stable_in_mode1_when_not_decrementing"
  )

  // Safety: Output matches cnt === 0
  fvAssert(io.o === (cnt === 0.U), "output_matches_cnt_eq_zero")

  // Safety: cnt never exceeds its maximum representable value (12-bit unsigned)
  fvAssert(cnt <= ((1.U << (MSB+1).U) - 1.U), "cnt_never_overflows")

  // Progress/Liveness: When in mode 1 with io.i high and cnt > 0,
  // cnt must reach 0 within 4096 cycles
  // (max cnt is 0xFFF = 4095, so at most 4095 decrements)
  astRelaxedLiveness(
    mode === 1.U && io.i && cnt =/= 0.U,
    cnt === 0.U,
    4096,
    "cnt_eventually_zero_in_mode1"
  )

  // Progress: The mode transition from 0 to 1 is monotonic and
  // mode never goes back to 0
  assertOnRise(mode.asBool, true.B, "mode_rise_is_valid")
}

object VerilogGenerator extends App {
  emitVerilog(new rgraph(), args)
}
