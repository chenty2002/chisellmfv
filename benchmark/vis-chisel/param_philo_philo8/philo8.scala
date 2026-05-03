package llmverify

import chisel3._
import chisel3.util._

object Philo8States {
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
  
  import Philo8States._
  
  // State register
  val selfReg = RegInit(io.init)
  
  // Simulate non-deterministic coin flip using a simple counter
  val coinCounter = RegInit(0.U(8.W))
  coinCounter := coinCounter + 1.U
  val coin = coinCounter(0) // Use LSB as pseudo-random coin
  
  // Output current state
  io.out := selfReg
  
  // State transition logic
  val nextState = Wire(UInt(2.W))
  nextState := selfReg
  
  switch(selfReg) {
    is(READING) {
      when(io.left === THINKING) {
        nextState := THINKING
      }
    }
    is(THINKING) {
      when(io.right === READING) {
        nextState := READING
      }.otherwise {
        nextState := Mux(coin, THINKING, HUNGRY)
      }
    }
    is(EATING) {
      nextState := Mux(coin, THINKING, EATING)
    }
    is(HUNGRY) {
      when(io.left =/= EATING && io.right =/= HUNGRY && io.right =/= EATING) {
        nextState := EATING
      }
    }
  }
  
  // Update state on clock edge
  selfReg := nextState
}

class Philo8 extends Module {
  val io = IO(new Bundle {
    // Expose all philosopher states for verification
    val st0 = Output(UInt(2.W))
    val st1 = Output(UInt(2.W))
    val st2 = Output(UInt(2.W))
    val st3 = Output(UInt(2.W))
    val st4 = Output(UInt(2.W))
    val st5 = Output(UInt(2.W))
    val st6 = Output(UInt(2.W))
    val st7 = Output(UInt(2.W))
  })
  
  import Philo8States._
  
  // Create 8 philosopher modules
  val ph0 = Module(new Philosopher())
  val ph1 = Module(new Philosopher())
  val ph2 = Module(new Philosopher())
  val ph3 = Module(new Philosopher())
  val ph4 = Module(new Philosopher())
  val ph5 = Module(new Philosopher())
  val ph6 = Module(new Philosopher())
  val ph7 = Module(new Philosopher())
  
  // Connect philosopher 0
  ph0.io.init := READING
  ph0.io.left := ph7.io.out
  ph0.io.right := ph1.io.out
  
  // Connect philosopher 1
  ph1.io.init := THINKING
  ph1.io.left := ph0.io.out
  ph1.io.right := ph2.io.out
  
  // Connect philosopher 2
  ph2.io.init := THINKING
  ph2.io.left := ph1.io.out
  ph2.io.right := ph3.io.out
  
  // Connect philosopher 3
  ph3.io.init := THINKING
  ph3.io.left := ph2.io.out
  ph3.io.right := ph4.io.out
  
  // Connect philosopher 4
  ph4.io.init := THINKING
  ph4.io.left := ph3.io.out
  ph4.io.right := ph5.io.out
  
  // Connect philosopher 5
  ph5.io.init := THINKING
  ph5.io.left := ph4.io.out
  ph5.io.right := ph6.io.out
  
  // Connect philosopher 6
  ph6.io.init := THINKING
  ph6.io.left := ph5.io.out
  ph6.io.right := ph7.io.out
  
  // Connect philosopher 7
  ph7.io.init := THINKING
  ph7.io.left := ph6.io.out
  ph7.io.right := ph0.io.out
  
  // Output all states
  io.st0 := ph0.io.out
  io.st1 := ph1.io.out
  io.st2 := ph2.io.out
  io.st3 := ph3.io.out
  io.st4 := ph4.io.out
  io.st5 := ph5.io.out
  io.st6 := ph6.io.out
  io.st7 := ph7.io.out
}

object VerilogGenerator extends App {
  emitVerilog(new Philo8(), args)
}