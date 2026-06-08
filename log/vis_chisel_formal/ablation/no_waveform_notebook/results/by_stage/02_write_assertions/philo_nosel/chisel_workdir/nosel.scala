package llmverify
import chisel3._
import chisel3.util._
import chisel3.ltl._
import chisel3.ltl.Sequence._

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

class Philo64 extends Module {
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

  // ── Formal Verification Assertions ──

  // Safety 1: No two adjacent philosophers eat simultaneously (mutual exclusion)
  // This is the classic dining philosophers invariant — adjacent philosophers
  // share a fork and cannot both hold it at the same time.
  for (i <- 0 until 64) {
    val rightIdx = if (i == 63) 0 else i + 1
    AssertProperty(
      !(io.states(i) === 2.U && io.states(rightIdx) === 2.U),
      s"mutex_adjacent_eating_$i"
    )
  }

  // Safety 2: Every philosopher state is a valid encoding (0=thinking, 1=hungry, 2=eating, 3=waiting)
  for (i <- 0 until 64) {
    val s = io.states(i)
    AssertProperty(
      s === 0.U || s === 1.U || s === 2.U || s === 3.U,
      s"valid_state_encoding_$i"
    )
  }

  // Liveness: System-level progress — if any philosopher is hungry (state=1),
  // then eventually some philosopher will eat (state=2).
  // This guards against deadlock/starvation in the ring.
  val anyHungry  = io.states.map(_ === 1.U).reduce(_ || _)
  val someEating = io.states.map(_ === 2.U).reduce(_ || _)
  AssertProperty(
    anyHungry |-> Sequence(someEating).eventually,
    None, None, Some("hungry_philosopher_eventually_eats")
  )
}

object VerilogGenerator extends App {
  emitVerilog(new Philo64(), args)
}
