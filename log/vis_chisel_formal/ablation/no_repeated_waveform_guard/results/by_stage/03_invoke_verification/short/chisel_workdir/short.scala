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
  
  // ========== Formal Verification Assertions ==========
  
  // Safety: State must always be one of the valid enum values
  fvAssert(state === ready || state === busy, "state_valid")
  
  // Safety: When in ready state and request is high, next state must be busy
  assertNextStepWhen(state === ready && io.request, state === busy,
    "ready_request_goes_busy")
  
  // Bounded liveness: State should not stay in ready for more than 2 consecutive cycles.
  // The nond_state toggles each cycle (based on randomCounter(0)), so from ready with
  // io.request low the worst case is ready -> ready (nond_state=ready) -> busy.
  val ready_stable_counter = RegInit(0.U(2.W))
  when(state === ready) {
    ready_stable_counter := ready_stable_counter + 1.U
  }.otherwise {
    ready_stable_counter := 0.U
  }
  fvAssert(ready_stable_counter <= 2.U, "ready_stable_max_2")
  
  // Bounded liveness: The FSM should reach the busy state within 3 cycles from any state
  astRelaxedLiveness(true.B, state === busy, 3, "reach_busy_within_3")
}

object VerilogGenerator extends App {
  emitVerilog(new short(), args)
}
