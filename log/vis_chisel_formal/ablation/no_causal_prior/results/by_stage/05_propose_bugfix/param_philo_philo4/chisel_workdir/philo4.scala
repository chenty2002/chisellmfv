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

  // ==========================================================
  // Formal Verification Assertions
  // ==========================================================

  // ----- Safety: Adjacent Mutex on EATING -----
  // No two adjacent philosophers can be EATING at the same time.
  // Adjacent pairs: (0,1), (1,2), (2,3), (3,0)
  fvAssert(!(ph0.io.out === PhilosopherState.EATING && ph1.io.out === PhilosopherState.EATING),
    "Mutex_eating_adjacent_ph0_ph1")
  fvAssert(!(ph1.io.out === PhilosopherState.EATING && ph2.io.out === PhilosopherState.EATING),
    "Mutex_eating_adjacent_ph1_ph2")
  fvAssert(!(ph2.io.out === PhilosopherState.EATING && ph3.io.out === PhilosopherState.EATING),
    "Mutex_eating_adjacent_ph2_ph3")
  fvAssert(!(ph3.io.out === PhilosopherState.EATING && ph0.io.out === PhilosopherState.EATING),
    "Mutex_eating_adjacent_ph3_ph0")

  // ----- Safety: Global maximum simultaneous eaters -----
  // In a 4-philosopher ring, at most 2 non-adjacent philosophers
  // can eat simultaneously (ph0+ph2 or ph1+ph3).
  val eating_count = (ph0.io.out === PhilosopherState.EATING).asUInt +
                     (ph1.io.out === PhilosopherState.EATING).asUInt +
                     (ph2.io.out === PhilosopherState.EATING).asUInt +
                     (ph3.io.out === PhilosopherState.EATING).asUInt
  fvAssert(eating_count <= 2.U, "At_most_two_eating_simultaneously")

  // ----- Safety: No philosopher stuck in invalid combination -----
  // A philosopher in EATING must have adjacent neighbors not blocking it.
  // (Structural invariant: if ph0 is EATING, then ph3 (left) is not EATING
  //  and ph1 (right) is not EATING — the HUNGRY state is a valid intermediate
  //  neighbor state because simultaneous register updates can cause a neighbor
  //  to become HUNGRY in the same cycle the philosopher transitions to EATING.)
  fvAssert(!(ph0.io.out === PhilosopherState.EATING &&
             (ph3.io.out === PhilosopherState.EATING ||
              ph1.io.out === PhilosopherState.EATING)),
    "Eating_guard_holds_ph0")
  fvAssert(!(ph1.io.out === PhilosopherState.EATING &&
             (ph0.io.out === PhilosopherState.EATING ||
              ph2.io.out === PhilosopherState.EATING)),
    "Eating_guard_holds_ph1")
  fvAssert(!(ph2.io.out === PhilosopherState.EATING &&
             (ph1.io.out === PhilosopherState.EATING ||
              ph3.io.out === PhilosopherState.EATING)),
    "Eating_guard_holds_ph2")
  fvAssert(!(ph3.io.out === PhilosopherState.EATING &&
             (ph2.io.out === PhilosopherState.EATING ||
              ph0.io.out === PhilosopherState.EATING)),
    "Eating_guard_holds_ph3")

  // ----- Bounded Liveness: HUNGRY → EATING (when unblocked) -----
  // If a philosopher is HUNGRY and no neighbor is blocking,
  // they must become EATING within 5 cycles.
  // Ph0 blocked if left (ph3) is EATING or right (ph1) is EATING/HUNGRY
  val ph0_blocked = (ph3.io.out === PhilosopherState.EATING) ||
                    (ph1.io.out === PhilosopherState.EATING) ||
                    (ph1.io.out === PhilosopherState.HUNGRY)
  val ph1_blocked = (ph0.io.out === PhilosopherState.EATING) ||
                    (ph2.io.out === PhilosopherState.EATING) ||
                    (ph2.io.out === PhilosopherState.HUNGRY)
  val ph2_blocked = (ph1.io.out === PhilosopherState.EATING) ||
                    (ph3.io.out === PhilosopherState.EATING) ||
                    (ph3.io.out === PhilosopherState.HUNGRY)
  val ph3_blocked = (ph2.io.out === PhilosopherState.EATING) ||
                    (ph0.io.out === PhilosopherState.EATING) ||
                    (ph0.io.out === PhilosopherState.HUNGRY)

  astRelaxedLiveness(
    (ph0.io.out === PhilosopherState.HUNGRY) && !ph0_blocked,
    ph0.io.out === PhilosopherState.EATING,
    5,
    "liveness_hungry_unblocked_to_eating_ph0")
  astRelaxedLiveness(
    (ph1.io.out === PhilosopherState.HUNGRY) && !ph1_blocked,
    ph1.io.out === PhilosopherState.EATING,
    5,
    "liveness_hungry_unblocked_to_eating_ph1")
  astRelaxedLiveness(
    (ph2.io.out === PhilosopherState.HUNGRY) && !ph2_blocked,
    ph2.io.out === PhilosopherState.EATING,
    5,
    "liveness_hungry_unblocked_to_eating_ph2")
  astRelaxedLiveness(
    (ph3.io.out === PhilosopherState.HUNGRY) && !ph3_blocked,
    ph3.io.out === PhilosopherState.EATING,
    5,
    "liveness_hungry_unblocked_to_eating_ph3")

  // ----- Bounded Liveness: READING → THINKING (when left neighbor is THINKING) -----
  // A READING philosopher transitions to THINKING when left neighbor is THINKING.
  // Ph0's left = ph3, Ph1's left = ph0, Ph2's left = ph1, Ph3's left = ph2
  astRelaxedLiveness(
    (ph0.io.out === PhilosopherState.READING) && (ph3.io.out === PhilosopherState.THINKING),
    ph0.io.out === PhilosopherState.THINKING,
    2,
    "liveness_reading_left_thinking_to_thinking_ph0")
  astRelaxedLiveness(
    (ph1.io.out === PhilosopherState.READING) && (ph0.io.out === PhilosopherState.THINKING),
    ph1.io.out === PhilosopherState.THINKING,
    2,
    "liveness_reading_left_thinking_to_thinking_ph1")
  astRelaxedLiveness(
    (ph2.io.out === PhilosopherState.READING) && (ph1.io.out === PhilosopherState.THINKING),
    ph2.io.out === PhilosopherState.THINKING,
    2,
    "liveness_reading_left_thinking_to_thinking_ph2")
  astRelaxedLiveness(
    (ph3.io.out === PhilosopherState.READING) && (ph2.io.out === PhilosopherState.THINKING),
    ph3.io.out === PhilosopherState.THINKING,
    2,
    "liveness_reading_left_thinking_to_thinking_ph3")
}

object VerilogGenerator extends App {
  emitVerilog(new Philo4(), args)
}
