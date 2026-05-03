package llmverify

import chisel3._
import chisel3.util._

// Enum for philosopher states
object PhilosopherState {
  val THINKING = 0.U(2.W)
  val HUNGRY   = 1.U(2.W)
  val EATING   = 2.U(2.W)
  val READING  = 3.U(2.W)
}

class PhilosopherIO extends Bundle {
  val left = Input(UInt(2.W))
  val right = Input(UInt(2.W))
  val init = Input(UInt(2.W))
  val out = Output(UInt(2.W))
}

class Philosopher extends Module {
  val io = IO(new PhilosopherIO)
  
  val state = RegInit(io.init)
  
  // Simple pseudo-random generator using a counter
  val randCounter = RegInit(0.U(8.W))
  randCounter := randCounter + 1.U
  val randBit = randCounter(0) // Use LSB as pseudo-random bit
  
  // Nondeterministic assignments - using pseudo-random bit
  val r0_state = Mux(
    randBit,
    PhilosopherState.THINKING,
    PhilosopherState.HUNGRY
  )
  
  val r1_state = Mux(
    randBit,
    PhilosopherState.THINKING,
    PhilosopherState.EATING
  )
  
  io.out := state
  
  // State machine logic
  switch(state) {
    is(PhilosopherState.READING) {
      when(io.left === PhilosopherState.THINKING) {
        state := PhilosopherState.THINKING
      }
    }
    is(PhilosopherState.THINKING) {
      when(io.right === PhilosopherState.READING) {
        state := PhilosopherState.READING
      }.otherwise {
        state := r0_state
      }
    }
    is(PhilosopherState.EATING) {
      state := r1_state
    }
    is(PhilosopherState.HUNGRY) {
      when(io.left =/= PhilosopherState.EATING && 
           io.right =/= PhilosopherState.HUNGRY && 
           io.right =/= PhilosopherState.EATING) {
        state := PhilosopherState.EATING
      }
    }
  }
}

class StarvationIO extends Bundle {
  val starv = Input(UInt(2.W))
  val state = Output(UInt(1.W))
}

class Starvation extends Module {
  val io = IO(new StarvationIO)
  
  val state = RegInit(0.U(1.W))
  
  switch(state) {
    is(0.U) {
      when(io.starv === PhilosopherState.HUNGRY) {
        state := 1.U
      }
    }
    is(1.U) {
      when(io.starv === PhilosopherState.THINKING) {
        state := 0.U
      }
    }
  }
  
  io.state := state
}

class DinersIO extends Bundle {
  val s0 = Output(UInt(2.W))
  val s1 = Output(UInt(2.W))
  val s2 = Output(UInt(2.W))
  val starvation_state = Output(UInt(1.W))
}

class Diners extends Module {
  val io = IO(new DinersIO)
  
  // Instantiate philosophers
  val ph0 = Module(new Philosopher())
  val ph1 = Module(new Philosopher())
  val ph2 = Module(new Philosopher())
  
  // Instantiate starvation detector
  val str = Module(new Starvation())
  
  // Connect philosopher 0
  ph0.io.left := ph2.io.out
  ph0.io.right := ph1.io.out
  ph0.io.init := PhilosopherState.EATING
  
  // Connect philosopher 1
  ph1.io.left := ph0.io.out
  ph1.io.right := ph2.io.out
  ph1.io.init := PhilosopherState.READING
  
  // Connect philosopher 2
  ph2.io.left := ph1.io.out
  ph2.io.right := ph0.io.out
  ph2.io.init := PhilosopherState.HUNGRY
  
  // Connect starvation detector
  str.io.starv := ph0.io.out
  
  // Output signals
  io.s0 := ph0.io.out
  io.s1 := ph1.io.out
  io.s2 := ph2.io.out
  io.starvation_state := str.io.state
}

object VerilogGenerator extends App {
  emitVerilog(new Diners(), args)
}