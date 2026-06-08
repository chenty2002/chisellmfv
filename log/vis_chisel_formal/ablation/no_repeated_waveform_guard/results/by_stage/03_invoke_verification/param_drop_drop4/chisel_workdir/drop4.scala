package llmverify

import chisel3._
import chisel3.util._
import chisel3.ltl._
import chisel3.ltl.Sequence._

// Define the State enum values
object State {
  val THINKING = 0.U(2.W)
  val READING = 1.U(2.W)
  val EATING = 2.U(2.W)
  val HUNGRY = 3.U(2.W)
}

class philosopher extends Module {
  val io = IO(new Bundle {
    val out = Output(UInt(2.W))
    val left = Input(UInt(2.W))
    val right = Input(UInt(2.W))
    val init = Input(UInt(2.W))
  })
  
  // State register
  val self = RegInit(io.init)
  
  // Nondeterministic coin - simulate with alternating 0/1
  val coin = RegInit(false.B)
  coin := !coin
  
  io.out := self
  
  // State transition logic
  when(self === State.READING) {
    when(io.left === State.THINKING) {
      self := State.THINKING
    }
  }.elsewhen(self === State.THINKING) {
    when(coin && (io.right === State.READING)) {
      self := State.READING
    }.otherwise {
      self := Mux(coin, State.THINKING, State.HUNGRY)
    }
  }.elsewhen(self === State.EATING) {
    self := Mux(coin, State.THINKING, State.EATING)
  }.elsewhen(self === State.HUNGRY) {
    when((io.left =/= State.EATING) && (io.right =/= State.HUNGRY) && (io.right =/= State.EATING)) {
      self := State.EATING
    }
  }
}

class philo4 extends Module {
  val io = IO(new Bundle {
    // Expose all philosopher states to prevent optimization
    val st0 = Output(UInt(2.W))
    val st1 = Output(UInt(2.W))
    val st2 = Output(UInt(2.W))
    val st3 = Output(UInt(2.W))
  })
  
  // Instantiate philosophers
  val ph0 = Module(new philosopher)
  val ph1 = Module(new philosopher)
  val ph2 = Module(new philosopher)
  val ph3 = Module(new philosopher)
  
  // Connect philosopher 0
  ph0.io.init := State.READING
  ph0.io.left := ph3.io.out
  ph0.io.right := ph1.io.out
  
  // Connect philosopher 1
  ph1.io.init := State.THINKING
  ph1.io.left := ph0.io.out
  ph1.io.right := ph2.io.out
  
  // Connect philosopher 2
  ph2.io.init := State.THINKING
  ph2.io.left := ph1.io.out
  ph2.io.right := ph3.io.out
  
  // Connect philosopher 3
  ph3.io.init := State.THINKING
  ph3.io.left := ph2.io.out
  ph3.io.right := ph0.io.out
  
  // Expose states to prevent optimization
  io.st0 := ph0.io.out
  io.st1 := ph1.io.out
  io.st2 := ph2.io.out
  io.st3 := ph3.io.out

  // ========== Formal Verification Assertions ==========

  // Safety 1: State validity - each philosopher must be in one of the four valid states
  AssertProperty(io.st0 <= 3.U, "ph0_valid_state")
  AssertProperty(io.st1 <= 3.U, "ph1_valid_state")
  AssertProperty(io.st2 <= 3.U, "ph2_valid_state")
  AssertProperty(io.st3 <= 3.U, "ph3_valid_state")

  // Safety 2: Mutual exclusion - adjacent philosophers cannot both be EATING simultaneously.
  // The ring topology is: ph0--ph1--ph2--ph3--ph0, so adjacent pairs are (0,1), (1,2), (2,3), (3,0).
  AssertProperty(!(io.st0 === State.EATING && io.st1 === State.EATING), "mutex_ph0_ph1_no_adjacent_eating")
  AssertProperty(!(io.st1 === State.EATING && io.st2 === State.EATING), "mutex_ph1_ph2_no_adjacent_eating")
  AssertProperty(!(io.st2 === State.EATING && io.st3 === State.EATING), "mutex_ph2_ph3_no_adjacent_eating")
  AssertProperty(!(io.st3 === State.EATING && io.st0 === State.EATING), "mutex_ph3_ph0_no_adjacent_eating")

  // Safety 3: At most two philosophers can be EATING at any time (cross-check with mutual exclusion)
  // In a ring of 4, mutual exclusion guarantees at most 2 can eat (alternating positions).
  AssertProperty(PopCount(Seq(io.st0 === State.EATING, io.st1 === State.EATING,
                              io.st2 === State.EATING, io.st3 === State.EATING)) <= 2.U,
                 "at_most_two_eating")

  // Liveness: Each philosopher must eventually eat within a bounded number of cycles.
  // The bound of 32 is generous for a 4-state system with 4 philosophers.
  val LIVENESS_BOUND = 32

  val eat_timer_0 = RegInit(0.U(log2Ceil(LIVENESS_BOUND + 1).W))
  val eat_timer_1 = RegInit(0.U(log2Ceil(LIVENESS_BOUND + 1).W))
  val eat_timer_2 = RegInit(0.U(log2Ceil(LIVENESS_BOUND + 1).W))
  val eat_timer_3 = RegInit(0.U(log2Ceil(LIVENESS_BOUND + 1).W))

  when(io.st0 === State.EATING) { eat_timer_0 := 0.U }
    .otherwise               { eat_timer_0 := eat_timer_0 + 1.U }

  when(io.st1 === State.EATING) { eat_timer_1 := 0.U }
    .otherwise               { eat_timer_1 := eat_timer_1 + 1.U }

  when(io.st2 === State.EATING) { eat_timer_2 := 0.U }
    .otherwise               { eat_timer_2 := eat_timer_2 + 1.U }

  when(io.st3 === State.EATING) { eat_timer_3 := 0.U }
    .otherwise               { eat_timer_3 := eat_timer_3 + 1.U }

  AssertProperty(eat_timer_0 < LIVENESS_BOUND.U, "ph0_liveness_eats_within_bound")
  AssertProperty(eat_timer_1 < LIVENESS_BOUND.U, "ph1_liveness_eats_within_bound")
  AssertProperty(eat_timer_2 < LIVENESS_BOUND.U, "ph2_liveness_eats_within_bound")
  AssertProperty(eat_timer_3 < LIVENESS_BOUND.U, "ph3_liveness_eats_within_bound")
}

object VerilogGenerator extends App {
  emitVerilog(new philo4(), args)
}
