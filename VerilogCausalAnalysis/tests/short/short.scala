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
  
  // Formal verification assertions
  
  // Assertion 1: State encoding - state should always be valid (ready or busy)
  fvAssert(state === ready || state === busy, "State should always be valid")
  
  // Assertion 2: When in ready state and request is high, next state must be busy
  // Fixed: Use RegInit for past values to ensure proper initialization
  val pastReady = RegInit(false.B)
  val pastRequest = RegInit(false.B)
  pastReady := (state === ready)
  pastRequest := io.request
  fvAssert(!(pastReady && pastRequest) || state === busy, "Ready with request should transition to busy")
  
  // Assertion 3: When in busy state, next state should be determined by nond_state
  assertNextStepWhen(state === busy, state === nond_state, "Busy state transition should follow nond_state")
  
  // Assertion 4: When in ready state and no request, next state should be nond_state
  assertNextStepWhen(state === ready && !io.request, state === nond_state, "Ready without request should follow nond_state")
  
  // Assertion 5: Liveness - if we're in busy state, we should eventually leave it
  astRelaxedLiveness(state === busy, state =/= busy, 10, "Should eventually leave busy state")
  
  // Assertion 6: Request should be stable when state is busy (no immediate change)
  assertStableWhen(state === busy, io.request, "Request should be stable when busy")
}

object VerilogGenerator extends App {
  emitVerilog(new short(), args)
}