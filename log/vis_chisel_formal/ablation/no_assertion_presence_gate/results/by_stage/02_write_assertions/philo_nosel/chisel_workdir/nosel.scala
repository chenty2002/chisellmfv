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

  // Safety: Mutual exclusion — no two adjacent philosophers may eat (state 2) simultaneously
  for (i <- 0 until 64) {
    val next = if (i == 63) 0 else i + 1
    fvAssert(
      !(io.states(i) === 2.U && io.states(next) === 2.U),
      s"Adjacent_philosophers_${i}_and_${next}_not_both_eating"
    )
  }

  // Bounded Liveness: The system as a whole makes progress — some philosopher
  // must enter the eating state (2) within a bounded window.
  // If no philosopher has eaten for a long time, the system may be deadlocked.
  val anyEating = io.states.map(s => s === 2.U).reduce(_ || _)
  assertLivenessTimer(
    !anyEating,        // cond: count cycles while nobody is eating
    anyEating,         // reset: clear timer when someone eats
    500,               // n: bound — must eat at least every 500 cycles
    "Some_philosopher_eats_within_500_cycles"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new Philo64(), args)
}
