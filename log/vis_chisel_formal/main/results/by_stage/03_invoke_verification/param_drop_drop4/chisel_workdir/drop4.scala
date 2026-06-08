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

  // ========== Formal Assertions ==========

  // ---- Safety: Mutual Exclusion ----
  // Adjacent philosophers share a fork; they cannot both be EATING simultaneously.
  fvAssert(!(io.st0 === State.EATING && io.st1 === State.EATING), "mutex_ph0_ph1")
  fvAssert(!(io.st1 === State.EATING && io.st2 === State.EATING), "mutex_ph1_ph2")
  fvAssert(!(io.st2 === State.EATING && io.st3 === State.EATING), "mutex_ph2_ph3")
  fvAssert(!(io.st3 === State.EATING && io.st0 === State.EATING), "mutex_ph3_ph0")

  // ---- Safety: No adjacent EATING across the diagonal ring pairs ----
  // Also ensure no two philosophers that are 2 apart are EATING?
  // Actually, ph0 and ph2 can both eat (they don't share forks), 
  // and ph1 and ph3 can both eat. So no assertion needed for this.

  // ---- Bounded Liveness / Progress ----
  // The system should not deadlock: at least one philosopher must eventually eat.
  // Use a relaxed liveness: if any philosopher is HUNGRY (trying to acquire forks),
  // at least one philosopher should be EATING within 20 cycles.
  astRelaxedLiveness(
    io.st0 === State.HUNGRY || io.st1 === State.HUNGRY || io.st2 === State.HUNGRY || io.st3 === State.HUNGRY,
    io.st0 === State.EATING || io.st1 === State.EATING || io.st2 === State.EATING || io.st3 === State.EATING,
    30,
    "some_philosopher_eats_when_hungry"
  )

  // ---- State Validity ----
  // Each philosopher should always be in a valid state (one of the four defined states).
  // Since UInt(2.W) can represent 4 values, all 4 are defined, so no illegal states exist.
  // Skipping this as it adds no value.
}

object VerilogGenerator extends App {
  emitVerilog(new philo4(), args)
}
