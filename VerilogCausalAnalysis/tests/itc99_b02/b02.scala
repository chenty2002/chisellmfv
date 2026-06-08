package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

// Verilog translation of the original b02 circuit from the ITC99
// benchmark set.

// Author: Fabio Somenzi <Fabio@Colorado.EDU>

class b02 extends Module with Formal {
  val io = IO(new Bundle {
    val LINEA = Input(Bool())
    val U = Output(Bool())
  })
  
  // State enumeration using UInt
  val StateA = 0.U(3.W)
  val StateB = 1.U(3.W)
  val StateC = 2.U(3.W)
  val StateD = 3.U(3.W)
  val StateE = 4.U(3.W)
  val StateF = 5.U(3.W)
  val StateG = 6.U(3.W)
  
  // State register with initial state A
  val stato = RegInit(StateA)
  
  // Output register U with initial value 0
  val U_reg = RegInit(false.B)
  io.U := U_reg
  
  // State machine logic
  switch(stato) {
    is(StateA) {
      stato := StateB
      U_reg := false.B
    }
    is(StateB) {
      when(io.LINEA === false.B) {
        stato := StateC
      }.otherwise {
        stato := StateF
      }
      U_reg := false.B
    }
    is(StateC) {
      when(io.LINEA === false.B) {
        stato := StateD
      }.otherwise {
        stato := StateG
      }
      U_reg := false.B
    }
    is(StateD) {
      stato := StateE
      U_reg := false.B
    }
    is(StateE) {
      stato := StateB
      U_reg := true.B
    }
    is(StateF) {
      stato := StateG
      U_reg := false.B
    }
    is(StateG) {
      when(io.LINEA === false.B) {
        stato := StateE
      }.otherwise {
        stato := StateA
      }
      U_reg := false.B
    }
  }
  
  // Formal verification assertions
  
  // Assertion 1: State encoding - ensure state is always valid (one-hot encoding check)
  assertOneHot0(Cat(
    stato === StateA,
    stato === StateB,
    stato === StateC,
    stato === StateD,
    stato === StateE,
    stato === StateF,
    stato === StateG
  ), "State encoding: exactly one state should be active")
  
  // Assertion 2: Output U is true only when coming from StateE (fixed timing)
  // Due to register timing, U_reg becomes true one cycle after leaving StateE
  fvAssert(!U_reg || (stato === StateB && U_reg), "Output U can only be true when coming from StateE")
  
  // Alternative assertion 2: Check that U_reg is true only when previous state was StateE
  // This is equivalent to the above but more explicit about the timing relationship
  
  // Assertion 3: When in StateE, next state must be StateB
  assertNextStepWhen(stato === StateE, stato === StateB, "From StateE, next state must be StateB")
  
  // Assertion 4: When in StateA, next state must be StateB
  assertNextStepWhen(stato === StateA, stato === StateB, "From StateA, next state must be StateB")
  
  // Assertion 5: When in StateD, next state must be StateE
  assertNextStepWhen(stato === StateD, stato === StateE, "From StateD, next state must be StateE")
  
  // Assertion 6: When in StateF, next state must be StateG
  assertNextStepWhen(stato === StateF, stato === StateG, "From StateF, next state must be StateG")
  
  // Assertion 7: State transition from StateB depends on LINEA
  assertImplies(stato === StateB && io.LINEA === false.B, stato === StateC, "From StateB with LINEA=0, next state is StateC")
  assertImplies(stato === StateB && io.LINEA === true.B, stato === StateF, "From StateB with LINEA=1, next state is StateF")
  
  // Assertion 8: State transition from StateC depends on LINEA
  assertImplies(stato === StateC && io.LINEA === false.B, stato === StateD, "From StateC with LINEA=0, next state is StateD")
  assertImplies(stato === StateC && io.LINEA === true.B, stato === StateG, "From StateC with LINEA=1, next state is StateG")
  
  // Assertion 9: State transition from StateG depends on LINEA
  assertImplies(stato === StateG && io.LINEA === false.B, stato === StateE, "From StateG with LINEA=0, next state is StateE")
  assertImplies(stato === StateG && io.LINEA === true.B, stato === StateA, "From StateG with LINEA=1, next state is StateA")
  
  // Assertion 10: U is false in all states except when coming from E (comprehensive check)
  fvAssert(
    (stato === StateA && !U_reg) &&
    (stato === StateB && U_reg) &&  // U can be true in StateB (coming from E)
    (stato === StateC && !U_reg) &&
    (stato === StateD && !U_reg) &&
    (stato === StateF && !U_reg) &&
    (stato === StateG && !U_reg),
    "U must be false in states A,C,D,F,G and can be true in StateB"
  )
  
  // Assertion 11: Liveness - the state machine should eventually reach StateE
  // This is a relaxed liveness with a reasonable bound
  astRelaxedLiveness(reset.asBool || (stato =/= StateE), stato === StateE, 50, "State machine should eventually reach StateE")
}

object VerilogGenerator extends App {
  emitVerilog(new b02(), args)
}