package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

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

class philo4 extends Module with Formal {
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

  // ---------------------------------------------------------------
  // Formal Verification Assertions
  // ---------------------------------------------------------------

  // SAFETY 1: Mutual Exclusion on EATING for adjacent philosophers.
  // In a dining philosophers ring, no two adjacent philosophers can eat simultaneously.
  fvAssert(!(io.st0 === State.EATING && io.st1 === State.EATING), "mutex_eat_ph0_ph1")
  fvAssert(!(io.st1 === State.EATING && io.st2 === State.EATING), "mutex_eat_ph1_ph2")
  fvAssert(!(io.st2 === State.EATING && io.st3 === State.EATING), "mutex_eat_ph2_ph3")
  fvAssert(!(io.st3 === State.EATING && io.st0 === State.EATING), "mutex_eat_ph3_ph0")

  // SAFETY 2: Two neighbors cannot both be READING simultaneously.
  // The READING state represents holding one fork; adjacent philosophers
  // cannot both hold the shared fork.
  fvAssert(!(io.st0 === State.READING && io.st1 === State.READING), "mutex_read_ph0_ph1")
  fvAssert(!(io.st1 === State.READING && io.st2 === State.READING), "mutex_read_ph1_ph2")
  fvAssert(!(io.st2 === State.READING && io.st3 === State.READING), "mutex_read_ph2_ph3")
  fvAssert(!(io.st3 === State.READING && io.st0 === State.READING), "mutex_read_ph3_ph0")

  // LIVENESS 1: When a philosopher is HUNGRY, they should eventually become EATING
  // within a bounded number of cycles (bound of 12 cycles, which is a reasonable
  // upper bound given 4 philosophers in a ring with nondeterministic coin).
  astRelaxedLiveness(io.st0 === State.HUNGRY, io.st0 === State.EATING, 12, "liveness_hungry_to_eat_ph0")
  astRelaxedLiveness(io.st1 === State.HUNGRY, io.st1 === State.EATING, 12, "liveness_hungry_to_eat_ph1")
  astRelaxedLiveness(io.st2 === State.HUNGRY, io.st2 === State.EATING, 12, "liveness_hungry_to_eat_ph2")
  astRelaxedLiveness(io.st3 === State.HUNGRY, io.st3 === State.EATING, 12, "liveness_hungry_to_eat_ph3")

  // LIVENESS 2: System-level progress - at least one philosopher should be EATING
  // within any window of 8 cycles (avoids global starvation / deadlock).
  astRelaxedLiveness(true.B, io.st0 === State.EATING || io.st1 === State.EATING ||
    io.st2 === State.EATING || io.st3 === State.EATING, 8, "liveness_someone_eats")
}

object VerilogGenerator extends App {
  emitVerilog(new philo4(), args)
}
