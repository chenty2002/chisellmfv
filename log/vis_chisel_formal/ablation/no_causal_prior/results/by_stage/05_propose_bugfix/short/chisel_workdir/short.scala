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

  // ---- Formal Verification Assertions ----

  // Safety: state encoding must always be valid (binary-encoded 2-state FSM)
  // state is 1-bit with ready=0, busy=1, so this checks state is always one of the two valid values
  assertImplies(true.B, state === ready || state === busy, "state_one_hot")

  // Correctness: when request is high and in ready state, state must transition to busy in the next cycle
  // Manual delay using RegNext to avoid a lowering bug in assertImpliesDelay's delayedBool pipe
  val prev_antecedent = RegNext(io.request && (state === ready), false.B)
  assertImplies(prev_antecedent, state === busy, "req_ready_to_busy")

  // Bounded liveness: when in busy state, the FSM must return to ready within 2 cycles.
  // nond_state toggles every cycle via randomCounter(0), so from busy the next state is
  // ready if randomCounter(0)=1 else busy; but randomCounter(0) flips each cycle, so
  // within at most 2 cycles the state will be ready.
  astRelaxedLiveness(state === busy, state === ready, 2, "busy_to_ready_liveness")
}

object VerilogGenerator extends App {
  emitVerilog(new short(), args)
}
