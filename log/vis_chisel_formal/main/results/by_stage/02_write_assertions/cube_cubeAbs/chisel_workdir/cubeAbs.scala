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

  // ========== Formal Verification Assertions ==========

  // Safety: After initialization, the position must always be within the
  // valid cube range (0-26). A value >= 27 would be an out-of-bounds error
  // since the cube has exactly 27 cells.
  fvAssert(!initDone || posReg < 27.U, "pos_in_bounds")

  // Safety: After initialization, the computed destination must always be
  // within the valid cube range (0-26). An out-of-bounds destination would
  // prevent the system from making progress.
  fvAssert(!initDone || dest < 27.U, "dest_in_bounds")

  // Safety: The current position must always be marked as visited in the
  // visited array. This is a core invariant of the traversal - every cell
  // the system occupies must have been visited.
  fvAssert(!initDone || visited(posReg), "current_position_visited")

  // Liveness/Progress: When there is a valid unvisited destination, the
  // system must visit it within a bounded number of cycles (27 cycles max,
  // covering the entire state space diameter of the 27-cell cube).
  astRelaxedLiveness(initDone && !visited(dest) && dest < 27.U, visited(dest), 27, "progress_to_visit")
}

object VerilogGenerator extends App {
  emitVerilog(new cubeAbs(), args)
}