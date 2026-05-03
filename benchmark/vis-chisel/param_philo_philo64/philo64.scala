package llmverify
import chisel3._
import chisel3.util._

object State {
  val THINKING = 0.U(2.W)
  val READING = 1.U(2.W)
  val EATING = 2.U(2.W)
  val HUNGRY = 3.U(2.W)
}

class Philosopher extends Module {
  val io = IO(new Bundle {
    val left = Input(UInt(2.W))
    val right = Input(UInt(2.W))
    val init = Input(UInt(2.W))
    val out = Output(UInt(2.W))
  })
  
  val self = RegInit(io.init)
  
  // Simple pseudo-random coin flip using LFSR
  val lfsr = RegInit(1.U(8.W))
  val coin = lfsr(0)
  lfsr := Cat(lfsr(6), lfsr(5), lfsr(4), lfsr(3), lfsr(2), lfsr(1), lfsr(0) ^ lfsr(1), lfsr(7))
  
  io.out := self
  
  when(self === State.READING) {
    when(io.left === State.THINKING) {
      self := State.THINKING
    }
  }.elsewhen(self === State.THINKING) {
    when(io.right === State.READING) {
      self := State.READING
    }.elsewhen(coin) {
      self := State.THINKING
    }.otherwise {
      self := State.HUNGRY
    }
  }.elsewhen(self === State.EATING) {
    when(coin) {
      self := State.THINKING
    }.otherwise {
      self := State.EATING
    }
  }.elsewhen(self === State.HUNGRY) {
    when(io.left =/= State.EATING && io.right =/= State.HUNGRY && io.right =/= State.EATING) {
      self := State.EATING
    }
  }
}

class Philo64 extends Module {
  val io = IO(new Bundle {
    // Expose all philosopher states to prevent optimization
    val states = Output(Vec(64, UInt(2.W)))
  })
  
  // Create state wires for all philosophers
  val states = Wire(Vec(64, UInt(2.W)))
  
  // Instantiate all 64 philosophers
  val philosophers = Array.fill(64)(Module(new Philosopher()))
  
  // Connect philosopher 0 (special case - starts as READING)
  philosophers(0).io.left := states(63)
  philosophers(0).io.right := states(1)
  philosophers(0).io.init := State.READING
  states(0) := philosophers(0).io.out
  
  // Connect philosophers 1-62 (all start as THINKING)
  for (i <- 1 until 63) {
    philosophers(i).io.left := states(i-1)
    philosophers(i).io.right := states(i+1)
    philosophers(i).io.init := State.THINKING
    states(i) := philosophers(i).io.out
  }
  
  // Connect philosopher 63 (starts as THINKING)
  philosophers(63).io.left := states(62)
  philosophers(63).io.right := states(0)
  philosophers(63).io.init := State.THINKING
  states(63) := philosophers(63).io.out
  
  // Output all states to prevent optimization
  io.states := states
}

object VerilogGenerator extends App {
  emitVerilog(new Philo64(), args)
}