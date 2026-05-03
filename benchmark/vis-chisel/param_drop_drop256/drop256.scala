package llmverify

import chisel3._
import chisel3.util._

object PhiloState {
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
  
  import PhiloState._
  
  // State register
  val self = RegInit(io.init)
  
  // Simple pseudo-random coin flip using LFSR
  val lfsr = RegInit(1.U(8.W))
  lfsr := Cat(lfsr(6), lfsr(5), lfsr(4), lfsr(3), lfsr(2), lfsr(1), lfsr(0), lfsr(7) ^ lfsr(5) ^ lfsr(4) ^ lfsr(3))
  val coin = lfsr(0)
  
  // Output current state
  io.out := self
  
  // State machine
  switch(self) {
    is(READING) {
      when(io.left === THINKING) {
        self := THINKING
      }
    }
    is(THINKING) {
      when(coin && (io.right === READING)) {
        self := READING
      }.elsewhen(coin) {
        self := THINKING
      }.otherwise {
        self := HUNGRY
      }
    }
    is(EATING) {
      when(coin) {
        self := THINKING
      }.otherwise {
        self := EATING
      }
    }
    is(HUNGRY) {
      when((io.left =/= EATING) && (io.right =/= HUNGRY) && (io.right =/= EATING)) {
        self := EATING
      }
    }
  }
}

class Philo256 extends Module {
  val io = IO(new Bundle {
    // Add outputs to preserve the design
    val states = Output(Vec(256, UInt(2.W)))
  })
  
  import PhiloState._
  
  // Create state wires for all philosophers
  val states = Wire(Vec(256, UInt(2.W)))
  
  // Instantiate 256 philosophers in a ring
  val philosophers = (0 until 256).map { i =>
    val phil = Module(new Philosopher())
    
    // Connect inputs
    phil.io.left := states((i + 255) % 256)  // Left neighbor (wrap around)
    phil.io.right := states((i + 1) % 256)   // Right neighbor (wrap around)
    
    // Set initial state - only philosopher 0 starts as READING, others as THINKING
    // Fixed: Convert Scala Boolean to Chisel Bool
    phil.io.init := Mux(i.U === 0.U, READING, THINKING)
    
    // Connect output to state wire
    states(i) := phil.io.out
    
    phil
  }
  
  // Connect states to output for preservation
  io.states := states
}

object VerilogGenerator extends App {
  emitVerilog(new Philo256(), args)
}