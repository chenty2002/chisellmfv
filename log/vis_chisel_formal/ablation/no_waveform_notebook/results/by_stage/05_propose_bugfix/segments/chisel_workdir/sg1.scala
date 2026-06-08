package llmverify
import chisel3._
import chisel3.util._
import chisel3.ltl._
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

  // Safety 1: Output correctness — io.o must equal (state === A)
  AssertProperty(io.o === (state === States.A), "output_eq_state_A")

  // Safety 2: State E is a terminal sink — once in E, stay in E
  val E_pre = RegNext(state === States.E, false.B)
  AssertProperty(!E_pre || state === States.E, "E_is_terminal")

  // Safety 3: One-step FSM transition correctness
  // From A with io.i=1, next state must be B
  val A_i1_pre = RegNext(state === States.A && io.i, false.B)
  AssertProperty(!A_i1_pre || state === States.B, "A_i1_to_B")
  // From B with io.i=1, next state must be C
  val B_i1_pre = RegNext(state === States.B && io.i, false.B)
  AssertProperty(!B_i1_pre || state === States.C, "B_i1_to_C")
  // From B with io.i=0, next state must be D
  val B_i0_pre = RegNext(state === States.B && !io.i, false.B)
  AssertProperty(!B_i0_pre || state === States.D, "B_i0_to_D")
  // From C, next state must be B
  val C_pre = RegNext(state === States.C, false.B)
  AssertProperty(!C_pre || state === States.B, "C_to_B")
  // From D, next state must be E
  val D_pre = RegNext(state === States.D, false.B)
  AssertProperty(!D_pre || state === States.E, "D_to_E")

  // Bounded liveness: when in A with io.i=1, we must leave A (io.o goes false) within 2 cycles
  astRelaxedLiveness(state === States.A && io.i, !io.o, 2, "A_leave_liveness")
}

object VerilogGenerator extends App {
  emitVerilog(new sg1(), args)
}
