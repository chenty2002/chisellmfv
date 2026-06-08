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

  // --------------------------------------------------------------------------
  // Formal verification assertions
  // --------------------------------------------------------------------------

  // Convenience aliases for readability
  val inA = state === States.A
  val inB = state === States.B
  val inC = state === States.C
  val inD = state === States.D
  val inE = state === States.E

  // ---- Safety: absorbing sink state E ----
  // Once the FSM enters state E it must stay in E forever.
  // Check: if in E now, the next state must also be E.
  assertNextStepWhen(inE, inE,
    "state_E_is_sink")

  // ---- Safety: deterministic single-step transitions ----
  // From state C, the next state is always B (input-independent)
  assertNextStepWhen(inC, inB,
    "from_C_next_is_B")

  // From state D, the next state is always E (input-independent)
  assertNextStepWhen(inD, inE,
    "from_D_next_is_E")

  // From state A with io.i low, remain in A
  assertNextStepWhen(inA && !io.i, inA,
    "from_A_low_stay_in_A")

  // From state A with io.i high, go to B
  assertNextStepWhen(inA && io.i, inB,
    "from_A_high_go_to_B")

  // From state B with io.i high, go to C
  assertNextStepWhen(inB && io.i, inC,
    "from_B_high_go_to_C")

  // From state B with io.i low, go to D
  assertNextStepWhen(inB && !io.i, inD,
    "from_B_low_go_to_D")

  // ---- Output correctness ----
  // io.o must be high if and only if the FSM is in state A
  fvAssert(io.o === inA,
    "output_high_only_in_state_A")

  // ---- Liveness: forward progress ----
  // When in state C, the FSM must leave C within 5 cycles.
  // (From C the transition goes to B unconditionally, so this should
  //  always hold, protecting against stuck-at-C bugs.)
  astRelaxedLiveness(inC, state =/= States.C, 5,
    "from_C_eventually_leave_C")
}
