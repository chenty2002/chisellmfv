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

  // ========== Formal Verification Assertions ==========

  // Safety: Mutual exclusion — no two adjacent philosophers can be EATING simultaneously
  // (ph0,ph1), (ph1,ph2), (ph2,ph3), (ph3,ph0) share forks
  fvAssert(!(ph0.io.out === State.EATING && ph1.io.out === State.EATING), "mutex_ph0_ph1_eating")
  fvAssert(!(ph1.io.out === State.EATING && ph2.io.out === State.EATING), "mutex_ph1_ph2_eating")
  fvAssert(!(ph2.io.out === State.EATING && ph3.io.out === State.EATING), "mutex_ph2_ph3_eating")
  fvAssert(!(ph3.io.out === State.EATING && ph0.io.out === State.EATING), "mutex_ph3_ph0_eating")

  // Liveness: Starvation freedom — a philosopher who becomes HUNGRY must eat within 10 cycles
  assertLivenessTimer(ph0.io.out === State.HUNGRY, ph0.io.out === State.EATING, 10, "ph0_hungry_progress")
  assertLivenessTimer(ph1.io.out === State.HUNGRY, ph1.io.out === State.EATING, 10, "ph1_hungry_progress")
  assertLivenessTimer(ph2.io.out === State.HUNGRY, ph2.io.out === State.EATING, 10, "ph2_hungry_progress")
  assertLivenessTimer(ph3.io.out === State.HUNGRY, ph3.io.out === State.EATING, 10, "ph3_hungry_progress")
}

object VerilogGenerator extends App {
  emitVerilog(new philo4(), args)
}
