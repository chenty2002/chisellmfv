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
  
  // Safety: Mode is monotonic - once it transitions to 1, it never goes back to 0
  fvAssert(!(mode === 0.U && RegNext(mode) === 1.U), "mode_monotonic")
  
  // Safety: Once cnt reaches 0 in mode 1, it stays at 0
  fvAssert(!(mode === 1.U && cnt === 0.U) || RegNext(cnt) === 0.U, "cnt_stays_zero_in_mode1")
  
  // Safety: Output always reflects cnt === 0
  fvAssert(io.o === (cnt === 0.U), "output_correct")
  
  // Bounded liveness: When actively decrementing in mode 1 (io.i true, cnt>0),
  // cnt will reach 0 within at most 4096 cycles (max value of 12-bit counter)
  astRelaxedLiveness(mode === 1.U && cnt > 0.U && io.i, cnt === 0.U, 4096, "cnt_eventually_reaches_zero")
}

object VerilogGenerator extends App {
  emitVerilog(new rgraph(), args)
}
