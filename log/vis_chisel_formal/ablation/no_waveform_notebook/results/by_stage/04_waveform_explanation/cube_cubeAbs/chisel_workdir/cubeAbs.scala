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

  // =====================
  // Formal Verification Assertions
  // =====================

  // Safety: position register always stays within valid cube positions [0, 26]
  fvAssert(posReg <= 26.U, "pos_in_range")

  // Safety: computed destination is always within valid cube positions [0, 26]
  fvAssert(dest <= 26.U, "dest_in_range")

  // Safety: once a position is marked visited, it stays visited forever
  // This is the core invariant of the exploration algorithm
  for (i <- 0 until 27) {
    val prevVisited = RegNext(visited(i), false.B)
    fvAssert(!prevVisited || visited(i), s"visited_${i}_persistent")
  }

  // Safety: after initialization completes, the current position is always
  // marked as visited (consistency between posReg and visited array)
  fvAssert(!initDone || visited(posReg), "current_pos_visited")

  // Safety: initDone transitions from false to true exactly once and never
  // reverts to false (initialization is one-shot)
  val prevInitDone = RegNext(initDone, false.B)
  fvAssert(!prevInitDone || initDone, "init_done_stable")

  // Liveness: during normal operation, whenever the current destination is
  // unvisited, it must become visited within 5 cycles. This ensures the
  // exploration algorithm never stalls indefinitely on an unvisited position.
  astRelaxedLiveness(initDone && !visited(dest), visited(dest), 5, "liveness_visit_unvisited")
}

object VerilogGenerator extends App {
  emitVerilog(new cubeAbs(), args)
}
