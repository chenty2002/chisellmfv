package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

// Define the State enumeration
object PhilosopherState extends ChiselEnum {
  val THINKING = Value(0.U)
  val READING  = Value(1.U)
  val EATING   = Value(2.U)
  val HUNGRY   = Value(3.U)
}

class Philosopher extends Module {
  val io = IO(new Bundle {
    val clk   = Input(Clock())
    val left  = Input(PhilosopherState())
    val right = Input(PhilosopherState())
    val init  = Input(PhilosopherState())
    val out   = Output(PhilosopherState())
    val coin  = Input(Bool()) // External coin flip input for nondeterminism
  })
  
  // State register
  val self = RegInit(io.init)
  
  // Output assignment
  io.out := self
  
  // State transition logic
  when(self === PhilosopherState.READING) {
    when(io.left === PhilosopherState.THINKING) {
      self := PhilosopherState.THINKING
    }
  }.elsewhen(self === PhilosopherState.THINKING) {
    when(io.right === PhilosopherState.READING) {
      self := PhilosopherState.READING
    }.otherwise {
      when(io.coin) {
        self := PhilosopherState.THINKING
      }.otherwise {
        self := PhilosopherState.HUNGRY
      }
    }
  }.elsewhen(self === PhilosopherState.EATING) {
    when(io.coin) {
      self := PhilosopherState.THINKING
    }.otherwise {
      self := PhilosopherState.EATING
    }
  }.elsewhen(self === PhilosopherState.HUNGRY) {
    when((io.left =/= PhilosopherState.EATING) && 
         (io.right =/= PhilosopherState.HUNGRY) && 
         (io.right =/= PhilosopherState.EATING)) {
      self := PhilosopherState.EATING
    }
  }
}

class Philo4 extends Module with Formal {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    // Add outputs to preserve all internal states
    val st0 = Output(PhilosopherState())
    val st1 = Output(PhilosopherState())
    val st2 = Output(PhilosopherState())
    val st3 = Output(PhilosopherState())
    // Coin inputs for each philosopher
    val coin0 = Input(Bool())
    val coin1 = Input(Bool())
    val coin2 = Input(Bool())
    val coin3 = Input(Bool())
  })
  
  // Instantiate philosopher 0
  val ph0 = Module(new Philosopher())
  ph0.io.clk := io.clock
  ph0.io.init := PhilosopherState.READING
  ph0.io.coin := io.coin0
  
  // Instantiate philosopher 1
  val ph1 = Module(new Philosopher())
  ph1.io.clk := io.clock
  ph1.io.init := PhilosopherState.THINKING
  ph1.io.coin := io.coin1
  
  // Instantiate philosopher 2
  val ph2 = Module(new Philosopher())
  ph2.io.clk := io.clock
  ph2.io.init := PhilosopherState.THINKING
  ph2.io.coin := io.coin2
  
  // Instantiate philosopher 3
  val ph3 = Module(new Philosopher())
  ph3.io.clk := io.clock
  ph3.io.init := PhilosopherState.THINKING
  ph3.io.coin := io.coin3
  
  // Connect the philosophers in a ring
  // Philosopher 0: left=ph3, right=ph1
  ph0.io.left := ph3.io.out
  ph0.io.right := ph1.io.out
  
  // Philosopher 1: left=ph0, right=ph2
  ph1.io.left := ph0.io.out
  ph1.io.right := ph2.io.out
  
  // Philosopher 2: left=ph1, right=ph3
  ph2.io.left := ph1.io.out
  ph2.io.right := ph3.io.out
  
  // Philosopher 3: left=ph2, right=ph0
  ph3.io.left := ph2.io.out
  ph3.io.right := ph0.io.out
  
  // Output states
  io.st0 := ph0.io.out
  io.st1 := ph1.io.out
  io.st2 := ph2.io.out
  io.st3 := ph3.io.out

  // ============================================================
  // Formal Verification Assertions
  // ============================================================

  // Mutual Exclusion (Safety): No two adjacent philosophers may eat simultaneously.
  // Pair (0,1): ph0 and ph1 share a fork.
  fvAssert(
    !(io.st0 === PhilosopherState.EATING && io.st1 === PhilosopherState.EATING),
    "mutex_eating_ph0_ph1"
  )
  // Pair (1,2): ph1 and ph2 share a fork.
  fvAssert(
    !(io.st1 === PhilosopherState.EATING && io.st2 === PhilosopherState.EATING),
    "mutex_eating_ph1_ph2"
  )
  // Pair (2,3): ph2 and ph3 share a fork.
  fvAssert(
    !(io.st2 === PhilosopherState.EATING && io.st3 === PhilosopherState.EATING),
    "mutex_eating_ph2_ph3"
  )
  // Pair (3,0): ph3 and ph0 share a fork (ring closure).
  fvAssert(
    !(io.st3 === PhilosopherState.EATING && io.st0 === PhilosopherState.EATING),
    "mutex_eating_ph3_ph0"
  )

  // Deadlock Freedom (Safety): The classic dining-philosophers deadlock occurs when
  // every philosopher is HUNGRY simultaneously. In this design the HUNGRY→EATING
  // transition requires `right =/= HUNGRY`, so when all four are HUNGRY each is
  // blocked by its right neighbour and no progress can be made.  Assert this state
  // is never reached.
  fvAssert(
    !(io.st0 === PhilosopherState.HUNGRY &&
      io.st1 === PhilosopherState.HUNGRY &&
      io.st2 === PhilosopherState.HUNGRY &&
      io.st3 === PhilosopherState.HUNGRY),
    "no_all_hungry_deadlock"
  )

  // Bounded Liveness (Progress): Whenever any philosopher is HUNGRY, at least one
  // philosopher must become EATING within the next 12 cycles.  This catches
  // system-level starvation where the scheduler (coin inputs) prevents all
  // philosophers from making forward progress indefinitely.
  astRelaxedLiveness(
    io.st0 === PhilosopherState.HUNGRY ||
      io.st1 === PhilosopherState.HUNGRY ||
      io.st2 === PhilosopherState.HUNGRY ||
      io.st3 === PhilosopherState.HUNGRY,
    io.st0 === PhilosopherState.EATING ||
      io.st1 === PhilosopherState.EATING ||
      io.st2 === PhilosopherState.EATING ||
      io.st3 === PhilosopherState.EATING,
    12,
    "progress_hungry_to_eating"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new Philo4(), args)
}
