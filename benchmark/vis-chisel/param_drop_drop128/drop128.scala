package llmverify
import chisel3._
import chisel3.util._

// Define the State enum
object PhilosopherState extends ChiselEnum {
  val THINKING = Value(0.U)
  val READING  = Value(1.U)
  val EATING   = Value(2.U)
  val HUNGRY   = Value(3.U)
}

class Philosopher extends Module {
  val io = IO(new Bundle {
    val left  = Input(PhilosopherState())
    val right = Input(PhilosopherState())
    val init  = Input(PhilosopherState())
    val out   = Output(PhilosopherState())
  })
  
  // State register
  val self = RegInit(io.init)
  
  // Simulate coin toss with a simple LFSR for pseudo-randomness
  val lfsr = RegInit(1.U(8.W))
  lfsr := Cat(lfsr(6), lfsr(5), lfsr(4), lfsr(3), lfsr(2), lfsr(1), lfsr(0), lfsr(7) ^ lfsr(5) ^ lfsr(4) ^ lfsr(3))
  val coin = lfsr(0)
  
  // Output assignment
  io.out := self
  
  // State machine logic
  switch(self) {
    is(PhilosopherState.READING) {
      when(io.left === PhilosopherState.THINKING) {
        self := PhilosopherState.THINKING
      }
    }
    is(PhilosopherState.THINKING) {
      when(coin && (io.right === PhilosopherState.READING)) {
        self := PhilosopherState.READING
      }.otherwise {
        self := Mux(coin, PhilosopherState.THINKING, PhilosopherState.HUNGRY)
      }
    }
    is(PhilosopherState.EATING) {
      self := Mux(coin, PhilosopherState.THINKING, PhilosopherState.EATING)
    }
    is(PhilosopherState.HUNGRY) {
      when((io.left =/= PhilosopherState.EATING) && 
           (io.right =/= PhilosopherState.HUNGRY) && 
           (io.right =/= PhilosopherState.EATING)) {
        self := PhilosopherState.EATING
      }
    }
  }
}

class Philo128 extends Module {
  val io = IO(new Bundle {
    // Add outputs to preserve all philosopher states
    val states = Output(Vec(128, PhilosopherState()))
  })
  
  // Create 128 philosopher modules
  val philosophers = VecInit(Seq.fill(128)(Module(new Philosopher()).io))
  
  // Connect philosophers in a ring
  for (i <- 0 until 128) {
    val leftIdx = if (i == 0) 127 else i - 1
    val rightIdx = if (i == 127) 0 else i + 1
    
    philosophers(i).left := philosophers(leftIdx).out
    philosophers(i).right := philosophers(rightIdx).out
    
    // Set initial states - first philosopher starts READING, others THINKING
    philosophers(i).init := Mux(i.U === 0.U, PhilosopherState.READING, PhilosopherState.THINKING)
    
    // Connect to output
    io.states(i) := philosophers(i).out
  }
}

object VerilogGenerator extends App {
  emitVerilog(new Philo128(), args)
}