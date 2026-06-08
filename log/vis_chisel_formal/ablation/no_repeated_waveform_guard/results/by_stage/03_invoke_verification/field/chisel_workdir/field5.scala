package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

// Model of a puzzle played on a square board with five rows of five holes.
// Initially, pegs are placed in 23 holes, leaving the hole in the center
// and the one in the lower right corner empty.
// Each move consist in removing one peg by jumping over it with another peg.
// A valid move required the jumping peg to be adjacent to the one to be
// removed and the hole on the other side to be empty.
// The objective is to remove all pegs except one.

class field5 extends Module with Formal {
  val io = IO(new Bundle {
    val from = Input(UInt(5.W))
    val dir = Input(UInt(2.W))
    val cnt = Output(UInt(5.W))
    // Add board output to preserve the design
    val board = Output(Vec(25, UInt(1.W)))
  })

  // Direction constants
  val U = 0.U(2.W)
  val D = 1.U(2.W)
  val L = 2.U(2.W)
  val R = 3.U(2.W)

  // Board representation as a vector of 25 bits
  val board = RegInit(VecInit(Seq.fill(25)(1.U(1.W))))
  
  // Initialize board: center (12) and lower right corner (24) are empty
  board(12) := 0.U
  board(24) := 0.U

  // Compute the residue of a 5-bit number mod 5
  def resMod5(n: UInt): UInt = {
    val result = Wire(UInt(3.W))
    // Default assignment
    result := 4.U
    
    switch(n) {
      is(0.U, 5.U, 10.U, 15.U, 20.U, 25.U, 30.U) { result := 0.U }
      is(1.U, 6.U, 11.U, 16.U, 21.U, 26.U, 31.U) { result := 1.U }
      is(2.U, 7.U, 12.U, 17.U, 22.U, 27.U) { result := 2.U }
      is(3.U, 8.U, 13.U, 18.U, 23.U, 28.U) { result := 3.U }
    }
    result
  }

  // Compute which direction is being attempted (before boundary checks)
  val attemptL = io.dir === L && io.from < 25.U && board(io.from) === 1.U
  val attemptR = io.dir === R && io.from < 25.U && board(io.from) === 1.U
  val attemptU = io.dir === U && io.from < 25.U && board(io.from) === 1.U
  val attemptD = io.dir === D && io.from < 25.U && board(io.from) === 1.U

  // Compute valid move indicator (boundary + adjacent peg + empty target)
  val validMove = Wire(Bool())
  validMove := false.B

  // Move execution logic
  val nextBoard = Wire(Vec(25, UInt(1.W)))
  nextBoard := board

  when(io.from < 25.U && board(io.from) === 1.U) {
    switch(io.dir) {
      is(L) {
        when(resMod5(io.from) > 1.U) {
          when(board(io.from - 1.U) === 1.U && board(io.from - 2.U) === 0.U) {
            nextBoard(io.from) := 0.U
            nextBoard(io.from - 1.U) := 0.U
            nextBoard(io.from - 2.U) := 1.U
            validMove := true.B
          }
        }
      }
      is(R) {
        when(resMod5(io.from) < 3.U) {
          when(board(io.from + 1.U) === 1.U && board(io.from + 2.U) === 0.U) {
            nextBoard(io.from) := 0.U
            nextBoard(io.from + 1.U) := 0.U
            nextBoard(io.from + 2.U) := 1.U
            validMove := true.B
          }
        }
      }
      is(U) {
        when(io.from < 15.U) {
          when(board(io.from + 5.U) === 1.U && board(io.from + 10.U) === 0.U) {
            nextBoard(io.from) := 0.U
            nextBoard(io.from + 5.U) := 0.U
            nextBoard(io.from + 10.U) := 1.U
            validMove := true.B
          }
        }
      }
      is(D) {
        when(io.from > 9.U) {
          when(board(io.from - 5.U) === 1.U && board(io.from - 10.U) === 0.U) {
            nextBoard(io.from) := 0.U
            nextBoard(io.from - 5.U) := 0.U
            nextBoard(io.from - 10.U) := 1.U
            validMove := true.B
          }
        }
      }
    }
  }

  // Update board on clock edge
  board := nextBoard

  // Count the number of pegs on the board
  io.cnt := board.map(x => x).reduce(_ + _)
  
  // Output board state for verification
  io.board := board

  // ========== Formal Verification Assertions ==========

  // Safety 1: Peg count is always within the valid range [1, 25]
  fvAssert(io.cnt >= 1.U && io.cnt <= 25.U, "cnt_range")

  // Safety 2: Center hole (position 12) is always empty
  fvAssert(board(12) === 0.U, "center_empty")

  // Safety 3: Lower-right corner (position 24) is always empty
  fvAssert(board(24) === 0.U, "corner_empty")

  // Safety 4: Every valid move decreases the peg count by exactly 1
  // validMove is true in the cycle when the move is decided;
  // board updates at the clock edge, so io.cnt reflects the new count
  // in the next cycle. We use RegNext for cross-cycle comparison.
  val prevValidMove = RegNext(validMove)
  val prevCnt = RegNext(io.cnt)
  fvAssert(!prevValidMove || io.cnt === prevCnt - 1.U, "cnt_decreases_by_one")

  // Safety 5: Move boundary checks prevent wrapping around board edges.
  // Left moves require column >= 2 (resMod5(from) > 1).
  fvAssert(!attemptL || resMod5(io.from) > 1.U, "left_move_boundary")
  // Right moves require column <= 2 (resMod5(from) < 3).
  fvAssert(!attemptR || resMod5(io.from) < 3.U, "right_move_boundary")
  // Up moves require row <= 2 (from < 15).
  fvAssert(!attemptU || io.from < 15.U, "up_move_boundary")
  // Down moves require row >= 2 (from > 9).
  fvAssert(!attemptD || io.from > 9.U, "down_move_boundary")
}

object VerilogGenerator extends App {
  emitVerilog(new field5(), args)
}
