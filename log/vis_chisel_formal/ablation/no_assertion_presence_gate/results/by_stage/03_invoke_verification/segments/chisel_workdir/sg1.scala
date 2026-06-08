package llmverify
import chisel3._
import chisel3.util._
import chiselFv._

class sg1 extends Module with Formal {
  val io = IO(new Bundle {
    val i = Input(Bool())
    val o = Output(Bool())
  })
  
  // Define states enum with correct Chisel 3 syntax
  object States extends ChiselEnum {
    val A, B, C, D, E = Value
  }
  
  // State register with initial value A
  val state = RegInit(States.A)
  
  // State machine logic
  switch(state) {
    is(States.A) {
      state := Mux(io.i, States.B, States.A)
    }
    is(States.B) {
      state := Mux(io.i, States.C, States.D)
    }
    is(States.C) {
      state := States.B
    }
    is(States.D) {
      state := States.E
    }
    is(States.E) {
      state := States.E
    }
  }
  
  // Output assignment
  io.o := (state === States.A)
  
  // ---- Formal Verification Assertions ----
  
  // Safety: Output must correctly reflect whether we are in state A
  fvAssert(io.o === (state === States.A), "output_eq_state_A")
  
  // Safety: State E is a terminal/absorbing state — once entered, it must remain stable
  assertStableWhen(state === States.E, state.asUInt, "terminal_E_absorbing")
  
  // Bounded liveness: When io.i is high and the FSM is not already in the terminal state E,
  // the state must change within 10 cycles, ensuring the FSM doesn't stall
  // while there is input driving it forward. We use astRelaxedLiveness where:
  //   req = io.i is true and we are not in terminal state E
  //   resp = state has changed (state !== previous state) or we reached terminal E
  astRelaxedLiveness(
    io.i && state =/= States.E,
    (state =/= RegNext(state)) || state === States.E,
    10,
    "progress_on_input"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new sg1())
}
