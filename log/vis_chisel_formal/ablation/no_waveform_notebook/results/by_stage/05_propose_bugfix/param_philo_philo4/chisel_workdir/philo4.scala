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

class Philosopher(val isEven: Boolean) extends Module {
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

  // Bounded eating counter: limits how long a philosopher can stay in EATING
  // to prevent starvation of neighbors when coin=0 indefinitely.
  val MAX_EAT_CYCLES = 15.U
  val eatCounter = RegInit(0.U(8.W))
  
  // Tie-breaker for mutex: even-indexed philosophers check that BOTH left and
  // right neighbors are not HUNGRY before eating. This prevents two adjacent
  // hungry philosophers from both transitioning to EATING simultaneously.
  // For the ring topology:
  //   ph0 (even): left=ph3, right=ph1 → checks both
  //   ph1 (odd):  left=ph0, right=ph2 → no check (eats first, breaks deadlock)
  //   ph2 (even): left=ph1, right=ph3 → checks both
  //   ph3 (odd):  left=ph2, right=ph0 → no check (eats first, breaks deadlock)
  // This covers all four adjacent pairs: (ph0,ph1), (ph1,ph2), (ph2,ph3), (ph3,ph0).
  val neighborNotHungryCheck = if (isEven) {
    (io.left =/= PhilosopherState.HUNGRY) && (io.right =/= PhilosopherState.HUNGRY)
  } else {
    true.B
  }
  
  // State transition logic
  when(self === PhilosopherState.READING) {
    eatCounter := 0.U
    when(io.left === PhilosopherState.THINKING) {
      self := PhilosopherState.THINKING
    }
  }.elsewhen(self === PhilosopherState.THINKING) {
    eatCounter := 0.U
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
    when(io.coin || eatCounter >= MAX_EAT_CYCLES) {
      self := PhilosopherState.THINKING
      eatCounter := 0.U
    }.otherwise {
      self := PhilosopherState.EATING
      eatCounter := eatCounter + 1.U
    }
  }.elsewhen(self === PhilosopherState.HUNGRY) {
    eatCounter := 0.U
    when((io.left =/= PhilosopherState.EATING) && 
         (io.right =/= PhilosopherState.EATING) &&
         neighborNotHungryCheck) {
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
  
  // Instantiate philosopher 0 (even index, has both-neighbors hungry check)
  val ph0 = Module(new Philosopher(true))
  ph0.io.clk := io.clock
  ph0.io.init := PhilosopherState.READING
  ph0.io.coin := io.coin0
  
  // Instantiate philosopher 1 (odd index, no hungry check)
  val ph1 = Module(new Philosopher(false))
  ph1.io.clk := io.clock
  ph1.io.init := PhilosopherState.THINKING
  ph1.io.coin := io.coin1
  
  // Instantiate philosopher 2 (even index, has both-neighbors hungry check)
  val ph2 = Module(new Philosopher(true))
  ph2.io.clk := io.clock
  ph2.io.init := PhilosopherState.THINKING
  ph2.io.coin := io.coin2
  
  // Instantiate philosopher 3 (odd index, no hungry check)
  val ph3 = Module(new Philosopher(false))
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

  // ===== FORMAL ASSERTIONS =====

  // --- Safety: Mutual Exclusion ---
  // No two adjacent philosophers can be EATING simultaneously (the classic
  // dining philosophers invariant — adjacent philosophers share a fork).
  fvAssert(!(io.st0 === PhilosopherState.EATING && io.st1 === PhilosopherState.EATING),
    "mutex_ph0_ph1_no_adjacent_eating")
  fvAssert(!(io.st1 === PhilosopherState.EATING && io.st2 === PhilosopherState.EATING),
    "mutex_ph1_ph2_no_adjacent_eating")
  fvAssert(!(io.st2 === PhilosopherState.EATING && io.st3 === PhilosopherState.EATING),
    "mutex_ph2_ph3_no_adjacent_eating")
  fvAssert(!(io.st3 === PhilosopherState.EATING && io.st0 === PhilosopherState.EATING),
    "mutex_ph3_ph0_no_adjacent_eating")

  // --- Liveness: Progress Guarantee ---
  // A philosopher who is HUNGRY must eventually become EATING within a bounded
  // number of cycles.  The bound is set to 200 cycles, which is well above the
  // maximum expected latency for 4 philosophers contending in a ring.
  astRelaxedLiveness(
    io.st0 === PhilosopherState.HUNGRY,
    io.st0 === PhilosopherState.EATING,
    200,
    "liveness_ph0_hungry_eventually_eats")
  astRelaxedLiveness(
    io.st1 === PhilosopherState.HUNGRY,
    io.st1 === PhilosopherState.EATING,
    200,
    "liveness_ph1_hungry_eventually_eats")
  astRelaxedLiveness(
    io.st2 === PhilosopherState.HUNGRY,
    io.st2 === PhilosopherState.EATING,
    200,
    "liveness_ph2_hungry_eventually_eats")
  astRelaxedLiveness(
    io.st3 === PhilosopherState.HUNGRY,
    io.st3 === PhilosopherState.EATING,
    200,
    "liveness_ph3_hungry_eventually_eats")
}

object VerilogGenerator extends App {
  emitVerilog(new Philo4(), args)
}
