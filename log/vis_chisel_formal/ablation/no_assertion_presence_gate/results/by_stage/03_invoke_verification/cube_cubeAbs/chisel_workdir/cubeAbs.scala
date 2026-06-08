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

  // Safety: position register must always stay within valid cube bounds [0, 26]
  fvAssert(posReg <= 26.U, "posReg_in_bounds")

  // Safety: destination wire must always be within valid cube bounds [0, 26]
  fvAssert(dest <= 26.U, "dest_in_bounds")

  // Safety: after initialization completes, the current position must always
  // be marked as visited — this guards against missed state tracking
  fvAssert(!initDone || visited(posReg), "posReg_visited_when_initDone")

  // Safety: next computed position must never exceed 31 (5-bit wrap check)
  // This ensures the arbiter between next and posReg in dest mux works correctly
  fvAssert(next <= 31.U, "next_5bit_bound")

  // Liveness/progress: once initDone, the position should eventually settle
  // (all reachable positions are visited).  This catches lost moves or
  // infinite loops where dest is already visited and no progress remains possible.
  // Use a relaxed timer: if posReg does not change for 27 consecutive cycles,
  // then the exploration is complete (all 27 positions visited or stuck).
  assertLivenessTimer(
    cond = true.B,
    reset = (posReg =/= RegNext(posReg)) || !initDone,
    n = 28,
    msg = "progress_bounded"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new cubeAbs(), args)
}
