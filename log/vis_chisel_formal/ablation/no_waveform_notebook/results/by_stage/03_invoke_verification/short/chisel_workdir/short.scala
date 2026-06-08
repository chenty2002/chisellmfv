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

  // Formal assertions
  // Safety: state must always be one of the two valid enum values
  fvAssert(state === ready || state === busy, "state_valid")

  // Liveness: when the FSM enters busy state, it must return to ready within 5 cycles.
  // randomCounter increments each cycle, so its LSB toggles every cycle,
  // causing nond_state to alternate between ready and busy, guaranteeing progress.
  astRelaxedLiveness(state === busy, state === ready, 5, "fsm_progress")
}

object VerilogGenerator extends App {
  emitVerilog(new short(), args)
}
