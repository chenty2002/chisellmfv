package llmverify
import chisel3._
import chisel3.util._
import chiselFv._

class short extends Module with Formal {
  val io = IO(new Bundle {
    val request = Output(Bool())
  })
  
  // Define the enum for status
  val ready :: busy :: Nil = Enum(2)
  
  // State register with initial value ready
  val state = RegInit(ready)
  
  // Simple pseudo-random generator for nondeterministic behavior
  val randomCounter = RegInit(0.U(8.W))
  randomCounter := randomCounter + 1.U
  
  // Nondeterministic state - using pseudo-random bit
  val nond_state = Mux(randomCounter(0), ready, busy)
  
  // Nondeterministic request output
  io.request := randomCounter(1)
  
  // State machine logic
  when(state === ready) {
    when(io.request) {
      state := busy
    }.otherwise {
      state := nond_state
    }
  }.elsewhen(state === busy) {
    state := nond_state
  }

  // ============================================================
  // Formal Verification Assertions
  // ============================================================

  // Safety: state must always hold a valid encoding (one of ready/busy)
  fvAssert(state === ready || state === busy, "state_valid_encoding")

  // Bounded liveness: when state is ready, it must transition to busy
  // within 4 cycles.  The pseudo-random counter and nond_state toggle
  // every cycle, so the worst-case delay from ready to busy is 2 cycles.
  astRelaxedLiveness(state === ready, state === busy, 4, "ready_eventually_busy")

  // Bounded liveness: when state is busy, it must transition to ready
  // within 4 cycles.  From busy the state always takes nond_state,
  // which alternates every cycle, so the worst-case delay is 2 cycles.
  astRelaxedLiveness(state === busy, state === ready, 4, "busy_eventually_ready")
}

object VerilogGenerator extends App {
  emitVerilog(new short(), args)
}
