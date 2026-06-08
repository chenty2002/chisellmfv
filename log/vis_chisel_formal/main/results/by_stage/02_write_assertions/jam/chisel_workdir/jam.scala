package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

// Cell states for the traffic jam game
object CellState {
  val EMPTY = 0.U(2.W)
  val LEFT = 1.U(2.W)
  val RIGHT = 2.U(2.W)
}

class Jam extends Module with Formal {
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
  
  // ========== FORMAL ASSERTIONS ==========
  
  // Count pieces of each type using PopCount
  val emptyCount = PopCount(slots.map(s => s === CellState.EMPTY))
  val leftCount  = PopCount(slots.map(s => s === CellState.LEFT))
  val rightCount = PopCount(slots.map(s => s === CellState.RIGHT))
  
  // Invariant 1: Exactly one empty slot (piece conservation across moves)
  fvAssert(emptyCount === 1.U, "exactly_one_empty_slot")
  
  // Invariant 2: Exactly three LEFT pieces (conservation)
  fvAssert(leftCount === 3.U, "exactly_three_left_pieces")
  
  // Invariant 3: Exactly three RIGHT pieces (conservation)
  fvAssert(rightCount === 3.U, "exactly_three_right_pieces")
  
  // Invariant 4: All slots contain only valid cell states (no illegal values)
  val allValidStates = slots.map(s => 
    s === CellState.EMPTY || s === CellState.LEFT || s === CellState.RIGHT
  ).reduce(_ && _)
  fvAssert(allValidStates, "all_slots_valid_state")
  
  // Invariant 5: Slots are stable when the move is invalid
  fvAssert(valid || slots === RegNext(slots), "slots_stable_when_invalid")
  
  // Invariant 6: Done signal matches the actual solved configuration
  val solvedConfig = (slots(0) === CellState.LEFT) && (slots(1) === CellState.LEFT) &&
                     (slots(2) === CellState.LEFT) && (slots(3) === CellState.EMPTY) &&
                     (slots(4) === CellState.RIGHT) && (slots(5) === CellState.RIGHT) &&
                     (slots(6) === CellState.RIGHT)
  fvAssert(done === solvedConfig, "done_matches_solved_config")
  
  // Invariant 7: The empty slot position is always correctly computed
  fvAssert(slots(empty) === CellState.EMPTY, "empty_slot_computed_correctly")
  
  // Invariant 8: Progress - there exists at least one valid move unless puzzle is solved
  val anyValidMove = (0 until 7).map { i =>
    val iPos = i.U(3.W)
    val mp1_i = iPos + 1.U(3.W)
    val mp2_i = mp1_i + 1.U(3.W)
    val mm1_i = iPos - 1.U(3.W)
    val mm2_i = mm1_i - 1.U(3.W)
    // slide right: position+1 is empty
    ((iPos < 6.U) && (mp1_i === empty)) ||
    // slide left: position-1 is empty
    ((iPos > 0.U) && (mm1_i === empty)) ||
    // jump right: current is RIGHT, position+1 is LEFT, position+2 is empty
    ((iPos < 5.U) && (slots(iPos) === CellState.RIGHT) &&
     (slots(mp1_i) === CellState.LEFT) && (mp2_i === empty)) ||
    // jump left: current is LEFT, position-1 is RIGHT, position-2 is empty
    ((iPos > 1.U) && (slots(iPos) === CellState.LEFT) &&
     (slots(mm1_i) === CellState.RIGHT) && (mm2_i === empty))
  }.reduce(_ || _)
  
  fvAssert(done || anyValidMove, "progress_at_least_one_valid_move")
}

object VerilogGenerator extends App {
  emitVerilog(new Jam(), args)
}
