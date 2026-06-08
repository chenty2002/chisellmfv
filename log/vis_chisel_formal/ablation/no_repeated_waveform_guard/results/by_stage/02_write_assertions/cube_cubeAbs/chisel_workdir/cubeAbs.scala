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

  // Safety: posReg and dest must always be valid indices into the 27-element visited array
  fvAssert(posReg < 27.U, "posReg_in_range")
  fvAssert(dest < 27.U, "dest_in_range")

  // Safety: after initialization completes, the current position is always marked as visited
  fvAssert(!initDone || visited(posReg), "current_position_visited_after_init")

  // Safety: the visited count is non-decreasing (visited positions are never unvisited)
  val visitedCount = PopCount(visited.asUInt)
  fvAssert(visitedCount >= RegNext(visitedCount, 0.U), "visited_count_non_decreasing")

  // Bounded liveness: if init is done and there is an unvisited destination,
  // the position must change within 2 cycles (the move happens in 1 cycle,
  // n=2 gives 1 cycle of margin)
  astRelaxedLiveness(
    initDone && !visited(dest),
    posReg =/= RegNext(posReg),
    2,
    "progress_when_unvisited_dest"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new cubeAbs(), args)
}
