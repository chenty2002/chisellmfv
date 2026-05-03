package llmverify

import chisel3._
import chisel3.util._

object PhiloState extends ChiselEnum {
  val THINKING = Value(0.U)
  val READING = Value(1.U)
  val EATING = Value(2.U)
  val HUNGRY = Value(3.U)
}

class Philosopher extends Module {
  val io = IO(new Bundle {
    val left = Input(PhiloState())
    val right = Input(PhiloState())
    val init = Input(PhiloState())
    val out = Output(PhiloState())
    val coin = Input(Bool()) // External coin flip input for non-determinism
  })
  
  val self = RegInit(io.init)
  
  io.out := self
  
  // Use when/elsewhen/otherwise structure to ensure complete assignment
  when(self === PhiloState.READING) {
    when(io.left === PhiloState.THINKING) {
      self := PhiloState.THINKING
    }.otherwise {
      self := PhiloState.READING // Stay in READING state
    }
  }.elsewhen(self === PhiloState.THINKING) {
    when(io.coin && (io.right === PhiloState.READING)) {
      self := PhiloState.READING
    }.elsewhen(io.coin) {
      self := PhiloState.THINKING
    }.otherwise {
      self := PhiloState.HUNGRY
    }
  }.elsewhen(self === PhiloState.EATING) {
    when(io.coin) {
      self := PhiloState.THINKING
    }.otherwise {
      self := PhiloState.EATING
    }
  }.elsewhen(self === PhiloState.HUNGRY) {
    when((io.left =/= PhiloState.EATING) && 
         (io.right =/= PhiloState.HUNGRY) && 
         (io.right =/= PhiloState.EATING)) {
      self := PhiloState.EATING
    }.otherwise {
      self := PhiloState.HUNGRY // Stay in HUNGRY state
    }
  }.otherwise {
    self := self // Default case (should never happen)
  }
}

class Philo32 extends Module {
  val io = IO(new Bundle {
    // Add outputs to preserve all internal states
    val states = Output(Vec(32, PhiloState()))
    val coin_inputs = Input(Vec(32, Bool()))
  })
  
  // Create 32 philosopher modules
  val philosophers = Array.fill(32)(Module(new Philosopher()))
  
  // Connect coin inputs
  for (i <- 0 until 32) {
    philosophers(i).io.coin := io.coin_inputs(i)
  }
  
  // Connect left and right neighbors in ring topology
  for (i <- 0 until 32) {
    val leftIdx = if (i == 0) 31 else i - 1
    val rightIdx = if (i == 31) 0 else i + 1
    
    philosophers(i).io.left := philosophers(leftIdx).io.out
    philosophers(i).io.right := philosophers(rightIdx).io.out
  }
  
  // Set initial states
  philosophers(0).io.init := PhiloState.READING
  for (i <- 1 until 32) {
    philosophers(i).io.init := PhiloState.THINKING
  }
  
  // Connect outputs
  for (i <- 0 until 32) {
    io.states(i) := philosophers(i).io.out
  }
}

object VerilogGenerator extends App {
  emitVerilog(new Philo32(), args)
}