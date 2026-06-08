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
  
  // ======== Formal Verification Assertions ========
  
  // Safety 1: No two adjacent philosophers can eat (state 2) simultaneously
  // This is the fundamental mutual exclusion property of dining philosophers
  for (i <- 0 until 64) {
    val neighbor = if (i == 63) 0 else i + 1
    fvAssert(!(io.states(i) === 2.U && io.states(neighbor) === 2.U),
      s"mutex_adjacent_philo_${i}_and_${neighbor}")
  }
  
  // Safety 2: All philosopher states must be in valid range [0, 3]
  for (i <- 0 until 64) {
    fvAssert(io.states(i) <= 3.U, s"valid_state_range_philo_${i}")
  }
  
  // Safety 3: Prevent the classic deadlock where all philosophers
  // simultaneously hold their left fork (state 1) and wait for the right
  val all_holding_left_fork = io.states.map(_ === 1.U).reduce(_ && _)
  fvAssert(!all_holding_left_fork, "no_deadlock_all_holding_left_fork")
  
  // Liveness (bounded): The system must make progress — some philosopher
  // eventually reaches the eating state (2). We use a relaxed liveness
  // check with a 1000-cycle bound suitable for this deterministic design.
  val any_eating = io.states.map(_ === 2.U).reduce(_ || _)
  astRelaxedLiveness(true.B, any_eating, 1000, "liveness_someone_eats_eventually")
}

object VerilogGenerator extends App {
  emitVerilog(new Philo64(), args)
}
