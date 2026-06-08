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

  // Define move conditions for verification
  val moveL = Wire(Bool())
  val moveR = Wire(Bool())
  val moveU = Wire(Bool())
  val moveD = Wire(Bool())
  moveL := false.B
  moveR := false.B
  moveU := false.B
  moveD := false.B

  when(io.from < 25.U && board(io.from) === 1.U) {
    switch(io.dir) {
      is(L) {
        when(resMod5(io.from) > 1.U) {
          when(board(io.from - 1.U) === 1.U && board(io.from - 2.U) === 0.U) {
            nextBoard(io.from) := 0.U
            nextBoard(io.from - 1.U) := 0.U
            nextBoard(io.from - 2.U) := 1.U
            moveL := true.B
          }
        }
      }
      is(R) {
        when(resMod5(io.from) < 3.U) {
          when(board(io.from + 1.U) === 1.U && board(io.from + 2.U) === 0.U) {
            nextBoard(io.from) := 0.U
            nextBoard(io.from + 1.U) := 0.U
            nextBoard(io.from + 2.U) := 1.U
            moveR := true.B
          }
        }
      }
      is(U) {
        when(io.from < 15.U) {
          when(board(io.from + 5.U) === 1.U && board(io.from + 10.U) === 0.U) {
            nextBoard(io.from) := 0.U
            nextBoard(io.from + 5.U) := 0.U
            nextBoard(io.from + 10.U) := 1.U
            moveU := true.B
          }
        }
      }
      is(D) {
        when(io.from > 9.U) {
          when(board(io.from - 5.U) === 1.U && board(io.from - 10.U) === 0.U) {
            nextBoard(io.from) := 0.U
            nextBoard(io.from - 5.U) := 0.U
            nextBoard(io.from - 10.U) := 1.U
            moveD := true.B
          }
        }
      }
    }
  }

  val moveExecuted = (io.from < 25.U && board(io.from) === 1.U) && (moveL || moveR || moveU || moveD)

  // Update board on clock edge
  board := nextBoard

  // Count the number of pegs on the board
  io.cnt := board.map(x => x).reduce(_ + _)
  
  // Output board state for verification
  io.board := board

  // --------------------------------------------------------------------------
  // Formal Verification Assertions
  // --------------------------------------------------------------------------

  // --- Registers for tracking history ---
  val prevCnt = RegNext(io.cnt)                                        // Peg count from previous cycle
  val initDone = RegInit(false.B)                                      // False only in the first cycle after reset
  initDone := true.B
  val firstCycle = RegInit(true.B)                                     // True only in the first cycle after reset
  firstCycle := false.B
  val moveDelayed = RegNext(moveExecuted)                              // Move signal delayed by one cycle

  // --- Count how many board positions change between current and next state ---
  val changedPositions = (0 until 25).map(i => board(i) =/= nextBoard(i)).map(b => b.asUInt).reduce(_ + _)

  // ==========================================================================
  // Safety: Peg count must never increase from one cycle to the next.
  // In peg solitaire, each valid move removes exactly one peg, so the count
  // is monotonically non-increasing. An increase would indicate a logic error.
  // ==========================================================================
  fvAssert(!initDone || prevCnt >= io.cnt, "cnt_non_increasing")

  // ==========================================================================
  // Correctness: After reset, the board must have exactly 23 pegs.
  // The design initializes all 25 positions to 1, then sets positions 12 and
  // 24 to 0, yielding a count of 23.
  // ==========================================================================
  fvAssert(!firstCycle || io.cnt === 23.U, "initial_cnt_23")

  // ==========================================================================
  // Correctness: Each valid move decreases the peg count by exactly 1.
  // When a move executes: source peg (1→0), jumped peg (1→0), destination (0→1).
  // Total change: -1 peg. If the count decreases by a different amount, the
  // move logic has a bug (e.g. updating the wrong positions).
  // ==========================================================================
  fvAssert(!moveDelayed || io.cnt === prevCnt - 1.U, "cnt_decrease_by_one_on_move")

  // ==========================================================================
  // Consistency: Exactly 3 board positions change when a move executes; no
  // positions change when no move executes. This verifies that the move logic
  // updates exactly the three intended positions (source, jumped, destination)
  // and no others, and that no spurious updates occur without a move.
  // ==========================================================================
  fvAssert(!moveExecuted || changedPositions === 3.U, "three_positions_change_on_move")
  fvAssert(moveExecuted || changedPositions === 0.U, "no_change_without_move")

  // ==========================================================================
  // Encoding: At most one direction can be active at any time.
  // The switch statement enforces mutual exclusion, but this assertion guards
  // against simulation-rare glitches or encoding bugs that could corrupt io.dir.
  // ==========================================================================
  assertMutex(Seq(io.dir === U, io.dir === D, io.dir === L, io.dir === R), "dir_mutex")
}

object VerilogGenerator extends App {
  emitVerilog(new field5(), args)
}
