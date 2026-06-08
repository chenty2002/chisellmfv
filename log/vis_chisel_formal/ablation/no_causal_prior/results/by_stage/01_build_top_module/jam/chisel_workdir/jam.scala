package llmverify

import chisel3._
import chisel3.util._

// Cell states for the traffic jam game
object CellState {
  val EMPTY = 0.U(2.W)
  val LEFT = 1.U(2.W)
  val RIGHT = 2.U(2.W)
}

class Jam extends Module {
  val io = IO(new Bundle {
    val move = Input(UInt(3.W))
    val done = Output(Bool())
    // Add outputs to preserve internal signals for verification
    val slots_debug = Output(Vec(7, UInt(2.W)))
    val empty_debug = Output(UInt(3.W))
    val valid_debug = Output(Bool())
  })
  
  // Initialize slots with the starting configuration
  // RIGHT, RIGHT, RIGHT, EMPTY, LEFT, LEFT, LEFT
  val slots = RegInit(VecInit(
    CellState.RIGHT,
    CellState.RIGHT, 
    CellState.RIGHT,
    CellState.EMPTY,
    CellState.LEFT,
    CellState.LEFT,
    CellState.LEFT
  ))
  
  // Calculate the position of the empty slot
  val empty = Wire(UInt(3.W))
  empty := Mux1H(Seq(
    (slots(0) === CellState.EMPTY) -> 0.U,
    (slots(1) === CellState.EMPTY) -> 1.U,
    (slots(2) === CellState.EMPTY) -> 2.U,
    (slots(3) === CellState.EMPTY) -> 3.U,
    (slots(4) === CellState.EMPTY) -> 4.U,
    (slots(5) === CellState.EMPTY) -> 5.U,
    (slots(6) === CellState.EMPTY) -> 6.U
  ))
  
  // Calculate move position +/- 1 and +/- 2
  val mp1 = Wire(UInt(3.W))
  val mp2 = Wire(UInt(3.W))
  val mm1 = Wire(UInt(3.W))
  val mm2 = Wire(UInt(3.W))
  
  mp1 := io.move + 1.U
  mp2 := mp1 + 1.U
  mm1 := io.move - 1.U
  mm2 := mm1 - 1.U
  
  // Check if the move is valid
  val valid = Wire(Bool())
  valid := (
    // slide right: move < 6 and position+1 is empty
    ((io.move < 6.U) && (mp1 === empty)) ||
    // slide left: move > 0 and move-1 is empty
    ((io.move < 7.U) && (io.move > 0.U) && (mm1 === empty)) ||
    // jump right: move < 5, current is RIGHT, position+1 is LEFT, position+2 is empty
    ((io.move < 5.U) && (slots(io.move) === CellState.RIGHT) &&
     (slots(mp1) === CellState.LEFT) && (mp2 === empty)) ||
    // jump left: move > 1, current is LEFT, position-1 is RIGHT, position-2 is empty
    ((io.move < 7.U) && (io.move > 1.U) && (slots(io.move) === CellState.LEFT) &&
     (slots(mm1) === CellState.RIGHT) && (mm2 === empty))
  )
  
  // Check if puzzle is solved
  // LEFT, LEFT, LEFT, EMPTY, RIGHT, RIGHT, RIGHT
  val done = Wire(Bool())
  done := (slots(0) === CellState.LEFT) && (slots(1) === CellState.LEFT) &&
          (slots(2) === CellState.LEFT) && (slots(3) === CellState.EMPTY) &&
          (slots(4) === CellState.RIGHT) && (slots(5) === CellState.RIGHT) &&
          (slots(6) === CellState.RIGHT)
  
  // Update slots on valid moves
  when(valid) {
    slots(empty) := slots(io.move)
    slots(io.move) := CellState.EMPTY
  }
  
  // Connect outputs
  io.done := done
  io.slots_debug := slots
  io.empty_debug := empty
  io.valid_debug := valid
}

object VerilogGenerator extends App {
  emitVerilog(new Jam(), args)
}