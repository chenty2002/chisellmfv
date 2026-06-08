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
  
  // ===== Formal Verification Assertions =====
  
  // Safety: Output must always equal (state === A)
  fvAssert((state === States.A) === io.o, "output_equals_state_A")
  
  // Safety: State E is a sink - once in E, stays in E forever
  assertNextStepWhen(state === States.E, state === States.E, "state_E_is_sink")
  
  // Safety: All state transitions obey the FSM specification
  // From A with i=0, stay in A
  assertNextStepWhen(state === States.A && !io.i, state === States.A, "A_stays_on_input_0")
  // From A with i=1, go to B
  assertNextStepWhen(state === States.A && io.i, state === States.B, "A_goes_to_B_on_input_1")
  // From B with i=0, go to D
  assertNextStepWhen(state === States.B && !io.i, state === States.D, "B_goes_to_D_on_input_0")
  // From B with i=1, go to C
  assertNextStepWhen(state === States.B && io.i, state === States.C, "B_goes_to_C_on_input_1")
  // From C, go to B (always)
  assertNextStepWhen(state === States.C, state === States.B, "C_goes_to_B")
  // From D, go to E (always)
  assertNextStepWhen(state === States.D, state === States.E, "D_goes_to_E")
  // From E, stay in E (duplicate assertion for completeness alongside the sink check)
  assertNextStepWhen(state === States.E, state === States.E, "E_stays_in_E")
  
  // Bounded liveness: From state A with input high, state B must be reached within 1 cycle
  astRelaxedLiveness(state === States.A && io.i, state === States.B, 1, "liveness_A_to_B")
  
  // Bounded liveness: From state B with input low, state E must be reached within 2 cycles (B→D→E)
  astRelaxedLiveness(state === States.B && !io.i, state === States.E, 2, "liveness_B_to_E")
  
  // Bounded liveness: From state B with input high, state B must be reached again within 2 cycles (B→C→B)
  astRelaxedLiveness(state === States.B && io.i, state === States.B, 2, "liveness_B_cycle_to_B")
}

object VerilogGenerator extends App {
  emitVerilog(new sg1(), args)
}
