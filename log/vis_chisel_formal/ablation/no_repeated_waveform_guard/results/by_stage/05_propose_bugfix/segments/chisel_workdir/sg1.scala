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
  
  // ========== Formal Verification Assertions ==========
  
  // Correct next-step assertion: checks that when cond is true in cycle N,
  // asert must hold in cycle N+1. Uses a register to capture the condition
  // and fvAssert with an implication, avoiding the bug where when-blocks
  // cannot guard concurrent assert property statements.
  // The register is initialized to false.B to avoid spurious failures at time 0
  // (before any clock edge) caused by the formal solver choosing an arbitrary
  // initial value for an uninitialized RegNext register.
  def assertNextStep(cond: Bool, asert: Bool, msg: String): Unit = {
    val prevCond = RegNext(cond && notChaos, false.B)
    fvAssert(!prevCond || asert, msg)
  }
  
  // Safety: Output correctness - io.o true iff in state A
  fvAssert((state === States.A) === io.o, "output_eq_state_A")
  
  // Safety: State A transitions
  assertNextStep(state === States.A && io.i, state === States.B, "A_i_to_B")
  assertNextStep(state === States.A && !io.i, state === States.A, "A_not_i_to_A")
  
  // Safety: State B transitions
  assertNextStep(state === States.B && io.i, state === States.C, "B_i_to_C")
  assertNextStep(state === States.B && !io.i, state === States.D, "B_not_i_to_D")
  
  // Safety: State C transitions back to B
  assertNextStep(state === States.C, state === States.B, "C_to_B")
  
  // Safety: State D transitions to sink E
  assertNextStep(state === States.D, state === States.E, "D_to_E")
  
  // Safety: State E is a sink (self-loop)
  assertNextStep(state === States.E, state === States.E, "E_stays_E")
  
  // Bounded liveness: Progress from state A when input is asserted
  astRelaxedLiveness(state === States.A && io.i, state === States.B, 5, "A_progress_on_i")
}

object VerilogGenerator extends App {
  emitVerilog(new sg1(), args)
}
