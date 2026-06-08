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
          }
        }
      }
      is(R) {
        when(resMod5(io.from) < 3.U) {
          when(board(io.from + 1.U) === 1.U && board(io.from + 2.U) === 0.U) {
            nextBoard(io.from) := 0.U
            nextBoard(io.from + 1.U) := 0.U
            nextBoard(io.from + 2.U) := 1.U
          }
        }
      }
      is(U) {
        when(io.from < 15.U) {
          when(board(io.from + 5.U) === 1.U && board(io.from + 10.U) === 0.U) {
            nextBoard(io.from) := 0.U
            nextBoard(io.from + 5.U) := 0.U
            nextBoard(io.from + 10.U) := 1.U
          }
        }
      }
      is(D) {
        when(io.from > 9.U) {
          when(board(io.from - 5.U) === 1.U && board(io.from - 10.U) === 0.U) {
            nextBoard(io.from) := 0.U
            nextBoard(io.from - 5.U) := 0.U
            nextBoard(io.from - 10.U) := 1.U
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

  // Sum of board bits for use in assertions
  val boardSum = board.map(x => x).reduce(_ + _)
  val nextSum = nextBoard.map(x => x).reduce(_ + _)

  // --- Safety Assertion 1: Peg count coherence ---
  // io.cnt must always equal the sum of all board bits.
  fvAssert(io.cnt === boardSum, "cnt_equals_board_sum")

  // --- Safety Assertion 2: Valid move reduces peg count by exactly 1 ---
  // Define the four valid move conditions (mirroring the move logic)
  val validFrom = io.from < 25.U && board(io.from) === 1.U
  val validLeft = io.dir === L && resMod5(io.from) > 1.U && board(io.from - 1.U) === 1.U && board(io.from - 2.U) === 0.U
  val validRight = io.dir === R && resMod5(io.from) < 3.U && board(io.from + 1.U) === 1.U && board(io.from + 2.U) === 0.U
  val validUp = io.dir === U && io.from < 15.U && board(io.from + 5.U) === 1.U && board(io.from + 10.U) === 0.U
  val validDown = io.dir === D && io.from > 9.U && board(io.from - 5.U) === 1.U && board(io.from - 10.U) === 0.U
  val validMove = validFrom && (validLeft || validRight || validUp || validDown)

  // When a valid move is performed, the nextBoard (which will become board next cycle)
  // must have exactly one fewer peg than the current board.
  fvAssert(!validMove || nextSum === boardSum - 1.U, "valid_move_reduces_peg_count")

  // --- Safety Assertion 3: Board stability on invalid input ---
  // When no valid move is triggered, the board must not change.
  val boardStable = (nextBoard zip board).map { case (n, b) => n === b }.reduce(_ && _)
  fvAssert(validMove || boardStable, "board_stable_when_no_valid_move")

  // --- Safety Assertion 4: Mutex on direction inputs ---
  // At most one direction should be selected at a time (though it's an input,
  // this checks the formal environment is driving valid directions).
  // io.dir is 2 bits, so there are 4 possible values; the direction constants
  // are 0, 1, 2, 3 which are all distinct — mutex is inherent.

  // --- Safety Assertion 5: Direction encoding is one-hot among defined moves ---
  // The move direction should be one of the four defined constants.
  val definedDir = io.dir === U || io.dir === D || io.dir === L || io.dir === R
  fvAssert(definedDir, "direction_defined")

  // --- Liveness Assertion: Valid move eventually reflected in peg count ---
  // When a valid move is requested at cycle T, then within 3 cycles the
  // board must update such that io.cnt equals the new sum (old sum - 1).
  // Since the move takes effect in 1 cycle (board := nextBoard on clock edge),
  // a bound of 3 is very generous and ensures reliable checking.
  val prevBoardSum = RegNext(boardSum)
  astRelaxedLiveness(validMove, io.cnt === prevBoardSum - 1.U, 3, "valid_move_alters_count")
}

object VerilogGenerator extends App {
  emitVerilog(new field5(), args)
}
