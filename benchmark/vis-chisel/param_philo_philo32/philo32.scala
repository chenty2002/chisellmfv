package llmverify

import chisel3._
import chisel3.util._

// State enumeration for philosophers
object PhilosopherState {
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
  
  import PhilosopherState._
  
  // Simple pseudo-random generator for coin flip
  val coinReg = RegInit(1.U(1.W))
  val coinNext = coinReg ^ (coinReg << 1)(0)  // Simple LFSR
  coinReg := coinNext
  val coin = coinReg
  
  // State register
  val self = RegInit(io.init)
  
  // Output current state
  io.out := self
  
  // State transition logic - use when/otherwise chain instead of switch
  // This ensures all paths are covered
  when(self === READING) {
    when(io.left === THINKING) {
      self := THINKING
    }
  }.elsewhen(self === THINKING) {
    when(io.right === READING) {
      self := READING
    }.elsewhen(coin === 1.U) {
      self := THINKING
    }.otherwise {
      self := HUNGRY
    }
  }.elsewhen(self === EATING) {
    when(coin === 1.U) {
      self := THINKING
    }
  }.elsewhen(self === HUNGRY) {
    when(io.left =/= EATING && io.right =/= HUNGRY && io.right =/= EATING) {
      self := EATING
    }
  }
  // Note: If none of the conditions are met, self retains its current value
}

class Philo32 extends Module {
  val io = IO(new Bundle {
    // Output all philosopher states to preserve the design
    val states = Output(Vec(32, UInt(2.W)))
  })
  
  import PhilosopherState._
  
  // Create 32 philosopher modules
  val philosophers = VecInit(Seq.fill(32)(Module(new Philosopher).io))
  
  // State wires for each philosopher - need to initialize these
  val states = RegInit(VecInit(Seq.fill(32)(THINKING)))
  states(0) := READING  // First philosopher starts in READING state
  
  // Connect philosophers in a ring
  for (i <- 0 until 32) {
    val leftIdx = if (i == 0) 31 else i - 1
    val rightIdx = if (i == 31) 0 else i + 1
    
    philosophers(i).left := states(leftIdx)
    philosophers(i).right := states(rightIdx)
    
    // Initialize first philosopher to READING, others to THINKING
    if (i == 0) {
      philosophers(i).init := READING
    } else {
      philosophers(i).init := THINKING
    }
  }
  
  // Update states from philosopher outputs
  for (i <- 0 until 32) {
    states(i) := philosophers(i).out
  }
  
  // Output all states
  io.states := states
}

object VerilogGenerator extends App {
  emitVerilog(new Philo32(), args)
}