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

  // 1. Mode monotonicity: once mode becomes 1, it stays 1 forever (never resets to 0)
  // Equivalent to: mode === 1.U |=> ##1 mode === 1.U
  val mode_prev = RegNext(mode)
  chisel3.assert(!(mode_prev === 1.U) || (mode === 1.U), "mode_stays_one")

  // 2. Mode transition: when mode=0 and input is asserted, mode becomes 1 in the next cycle
  // Equivalent to: (mode === 0.U && io.i) |=> ##1 mode === 1.U
  val trans_cond_prev = RegNext(mode === 0.U && io.i)
  chisel3.assert(!trans_cond_prev || (mode === 1.U), "mode_transitions_on_input")

  // 3. Output consistency: io.o is high exactly when cnt is zero
  chisel3.assert(io.o === (cnt === 0.U), "output_equals_cnt_is_zero")
}

object VerilogGenerator extends App {
  emitVerilog(new rgraph(), args)
}
