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

  // Safety 1: Mode is monotonic — once set to 1, it never goes back to 0
  assertStableWhen(mode === 1.U, mode, "mode_is_monotonic")

  // Safety 2: In mode 1, the counter must not underflow (guard prevents decrement at 0)
  fvAssert(!(mode === 1.U && io.i && cnt === 0.U), "no_counter_underflow")
}

object VerilogGenerator extends App {
  emitVerilog(new rgraph(), args)
}
