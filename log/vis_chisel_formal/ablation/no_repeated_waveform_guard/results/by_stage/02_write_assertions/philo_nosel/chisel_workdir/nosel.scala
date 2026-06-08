package llmverify
import chisel3._
import chisel3.util._
import chiselFv._

class Philosopher extends Module {
  val io = IO(new Bundle {
    val left = Input(UInt(2.W))
    val right = Input(UInt(2.W))
    val init = Input(UInt(2.W))
    val out = Output(UInt(2.W))
  })
  
  val state = RegInit(io.init)
  
  // Simulate non-deterministic coin toss using a simple counter
  val coinCounter = RegInit(0.U(8.W))
  coinCounter := coinCounter + 1.U
  val coin = coinCounter(0) // Use LSB as pseudo-random
  
  io.out := state
  
  switch(state) {
    is(1.U) {
      when(io.left === 0.U) {
        state := 0.U
      }
    }
    is(0.U) {
      when(io.right === 1.U) {
        state := 1.U
      }.otherwise {
        state := Mux(coin, 0.U, 3.U)
      }
    }
    is(2.U) {
      state := Mux(coin, 0.U, 2.U)
    }
    is(3.U) {
      when(io.left =/= 2.U && io.right =/= 3.U && io.right =/= 2.U) {
        state := 2.U
      }
    }
  }
}

class Philo64 extends Module with Formal {
  val io = IO(new Bundle {
    // Add outputs to preserve all internal states
    val states = Output(Vec(64, UInt(2.W)))
  })
  
  // Create 64 philosopher modules
  val philosophers = VecInit(Seq.fill(64)(Module(new Philosopher()).io))
  
  // Connect philosophers in a ring topology
  for (i <- 0 until 64) {
    val leftIdx = if (i == 0) 63 else i - 1
    val rightIdx = if (i == 63) 0 else i + 1
    
    philosophers(i).left := philosophers(leftIdx).out
    philosophers(i).right := philosophers(rightIdx).out
    philosophers(i).init := Mux(i.U === 0.U, 1.U, 0.U) // Fixed: use Chisel Bool
    
    // Connect to output for preservation
    io.states(i) := philosophers(i).out
  }
  
  // === FORMAL ASSERTIONS ===
  
  // Safety: Mutual exclusion - no two adjacent philosophers can eat simultaneously
  // State 2 = eating; adjacent philosophers share a fork and cannot both eat
  for (i <- 0 until 64) {
    val leftIdx = if (i == 0) 63 else i - 1
    val rightIdx = if (i == 63) 0 else i + 1
    
    fvAssert(
      !(io.states(i) === 2.U) || 
      (io.states(leftIdx) =/= 2.U && io.states(rightIdx) =/= 2.U),
      s"mutual_exclusion_adjacent_philosophers_${i}"
    )
  }
  
  // Bounded liveness: Philosopher 0 (initialized to state 1 / has left fork) eventually eats
  astRelaxedLiveness(
    io.states(0) === 1.U,
    io.states(0) === 2.U,
    2000,
    "philosopher_0_eventually_eats_from_left_fork"
  )
  
  // Progress: Philosopher 1 (initialized to state 0 / thinking) eventually picks up left fork
  astRelaxedLiveness(
    io.states(1) === 0.U,
    io.states(1) === 1.U,
    2000,
    "philosopher_1_progress_to_left_fork"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new Philo64(), args)
}
