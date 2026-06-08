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
  val prev_mode = RegNext(mode, 0.U)
  fvAssert(!(prev_mode === 1.U && mode === 0.U), "mode_is_monotonic")

  // Safety 2: In mode 1, the counter must not underflow (guard prevents decrement at 0)
  // Check that when cnt=0 in mode 1 with io.i asserted, cnt does not wrap around
  val underflow_danger = RegNext(mode === 1.U && io.i && cnt === 0.U, false.B)
  fvAssert(!(underflow_danger && cnt =/= 0.U), "no_counter_underflow")
}

object VerilogGenerator extends App {
  emitVerilog(new rgraph(), args)
}
