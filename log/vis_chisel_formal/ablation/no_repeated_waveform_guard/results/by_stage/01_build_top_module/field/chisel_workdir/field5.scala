package llmverify

import chisel3._
import chisel3.util._

// Model of a puzzle played on a square board with five rows of five holes.
// Initially, pegs are placed in 23 holes, leaving the hole in the center
// and the one in the lower right corner empty.
// Each move consist in removing one peg by jumping over it with another peg.
// A valid move required the jumping peg to be adjacent to the one to be
// removed and the hole on the other side to be empty.
// The objective is to remove all pegs except one.

class field5 extends Module {
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
}

object VerilogGenerator extends App {
  emitVerilog(new field5(), args)
}
