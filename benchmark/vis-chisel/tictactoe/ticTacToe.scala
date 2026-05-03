package llmverify
import chisel3._
import chisel3.util._

// Define the content enumeration
object Content {
  val EMPTY = 0.U(2.W)
  val X = 1.U(2.W)
  val O = 2.U(2.W)
}

class ticTacToe extends Module {
  val io = IO(new Bundle {
    val imove = Input(UInt(4.W))
    val winX = Output(Bool())
    val winO = Output(Bool())
    val finished = Output(Bool())
  })
  
  // Board state - 9 cells, each 2 bits (EMPTY, X, O)
  val board = RegInit(VecInit(Seq.fill(9)(Content.EMPTY)))
  
  // Current turn - X or O
  val turn = RegInit(Content.X)
  
  // Processed move input
  val move = Wire(UInt(4.W))
  move := Mux(io.imove < 9.U, io.imove, 0.U)
  
  // Game logic on clock edge
  when(!io.finished && board(move) === Content.EMPTY) {
    board(move) := turn
    turn := Mux(turn === Content.X, Content.O, Content.X)
  }
  
  // Winning condition checks for X
  val winX = (board(0) === Content.X && ((board(1) === Content.X && board(2) === Content.X) || (board(3) === Content.X && board(6) === Content.X))) ||
             (board(8) === Content.X && ((board(7) === Content.X && board(6) === Content.X) || (board(5) === Content.X && board(2) === Content.X))) ||
             (board(4) === Content.X && ((board(0) === Content.X && board(8) === Content.X) || (board(2) === Content.X && board(6) === Content.X) ||
                                        (board(1) === Content.X && board(7) === Content.X) || (board(3) === Content.X && board(5) === Content.X)))
  
  // Winning condition checks for O
  val winO = (board(0) === Content.O && ((board(1) === Content.O && board(2) === Content.O) || (board(3) === Content.O && board(6) === Content.O))) ||
             (board(8) === Content.O && ((board(7) === Content.O && board(6) === Content.O) || (board(5) === Content.O && board(2) === Content.O))) ||
             (board(4) === Content.O && ((board(0) === Content.O && board(8) === Content.O) || (board(2) === Content.O && board(6) === Content.O) ||
                                        (board(1) === Content.O && board(7) === Content.O) || (board(3) === Content.O && board(5) === Content.O)))
  
  // Game finished condition
  val finished = winX || winO || (board(0) =/= Content.EMPTY && board(1) =/= Content.EMPTY && board(2) =/= Content.EMPTY &&
                                  board(3) =/= Content.EMPTY && board(4) =/= Content.EMPTY && board(5) =/= Content.EMPTY &&
                                  board(6) =/= Content.EMPTY && board(7) =/= Content.EMPTY && board(8) =/= Content.EMPTY)
  
  // Connect outputs
  io.winX := winX
  io.winO := winO
  io.finished := finished
}

object VerilogGenerator extends App {
  emitVerilog(new ticTacToe(), args)
}