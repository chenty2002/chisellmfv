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

  // === Formal Verification Assertions ===

  // Safety 1: mode is "sticky" — once it transitions to 1, it never goes back to 0
  assertImplies(RegNext(mode, 0.U) === 1.U, mode === 1.U, "mode_stays_one")

  // Safety 2: In mode 0, cnt increments by exactly 1 every cycle
  fvAssert(!(mode === 0.U) || cnt === RegNext(cnt, 0.U) + 1.U, "cnt_increments_in_mode0")

  // Safety 3: In mode 1, cnt never increases
  fvAssert(!(mode === 1.U) || cnt <= RegNext(cnt, 0.U), "cnt_never_increases_in_mode1")

  // Safety 4: In mode 1, when io.i is asserted and cnt > 0, cnt decrements by exactly 1
  fvAssert(!(mode === 1.U && io.i && cnt =/= 0.U) || cnt === RegNext(cnt, 0.U) - 1.U, "cnt_decrements_in_mode1")

  // Safety 5: In mode 1, when io.i is not asserted or cnt == 0, cnt does not change
  fvAssert(!(mode === 1.U && !(io.i && cnt =/= 0.U)) || cnt === RegNext(cnt, 0.U), "cnt_stable_in_mode1")
}

object VerilogGenerator extends App {
  emitVerilog(new rgraph(), args)
}
