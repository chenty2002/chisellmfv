package llmverify
import chisel3._
import chisel3.util._
import chiselFv._

class cubeAbs extends Module with Formal {
  val io = IO(new Bundle {
    val dir = Input(UInt(3.W))  // unused
    val start = Input(UInt(5.W))
    val pos = Output(UInt(5.W))
  })
  
  // Register for position (also output)
  val posReg = RegInit(0.U(5.W))
  
  // Register array for visited (27 bits)
  val visited = RegInit(VecInit(Seq.fill(27)(false.B)))
  
  // Wire for next and dest
  val next = Wire(UInt(5.W))
  val dest = Wire(UInt(5.W))
  
  // Combinational logic
  next := Cat(io.start(4, 1), ~posReg(0))
  dest := Mux(next < 27.U, next, posReg)
  
  // Handle initialization - use reset to simulate initial block
  // When reset is asserted, initialize position and visited array
  val initDone = RegInit(false.B)
  
  when (!initDone) {
    // Initialize position with bounds checking
    posReg := Mux((io.start > 26.U) || (io.start === 13.U), 0.U, io.start)
    // Initialize visited array to all 0
    for (i <- 0 until 27) {
      visited(i) := false.B
    }
    // Mark initial position as visited
    visited(posReg) := true.B
    initDone := true.B
  }.otherwise {
    // Normal operation
    when (!visited(dest)) {
      posReg := dest
      visited(dest) := true.B
    }
  }
  
  // Connect output
  io.pos := posReg

  // ===== Formal Verification Assertions =====

  // Safety: position register is always in valid range [0, 26]
  fvAssert(posReg < 27.U, "pos_in_range")

  // Safety: destination is either in valid range [0, 26] or we stay at current position
  fvAssert(dest < 27.U || dest === posReg, "dest_valid")

  // Safety: output always matches internal position register
  fvAssert(io.pos === posReg, "output_consistent")

  // Safety: visited bits are monotonic (once set, never cleared) after initialization
  // A bit that was 1 in the previous cycle must still be 1 in the current cycle
  val visitedPrev = RegNext(visited.asUInt)
  fvAssert(!initDone || (visitedPrev & ~visited.asUInt) === 0.U, "visited_monotonic")

  // Liveness/Progress: when we are in normal operation and there is an unvisited
  // destination different from current position, we move to it in the next cycle
  assertNextStepWhen(
    initDone && !visited(dest) && dest =/= posReg,
    posReg === dest,
    "position_updates"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new cubeAbs(), args)
}
