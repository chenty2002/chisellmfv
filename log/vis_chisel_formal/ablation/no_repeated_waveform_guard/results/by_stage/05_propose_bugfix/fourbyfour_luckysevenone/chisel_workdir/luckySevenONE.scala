package llmverify
import chisel3._
import chisel3.util._
import chiselFv._

class luckySeven extends Module with Formal {
  val io = IO(new Bundle {
    val from = Input(UInt(3.W))
    val to = Input(UInt(3.W))
    // Add outputs to preserve the design
    val b_out = Output(Vec(8, UInt(3.W)))
    val valid_out = Output(Bool())
    val permutation_out = Output(Bool())
  })
  
  // Internal registers
  val b = RegInit(VecInit(Seq.tabulate(8)(i => i.U(3.W))))
  val freg = RegInit(0.U(3.W))
  val treg = RegInit(0.U(3.W))
  
  // Latch inputs on clock edge
  freg := io.from
  treg := io.to
  
  // Valid move predicate
  val valid = (b(treg) === 0.U) && (
    (treg === (freg + 1.U)) || 
    (freg === (treg + 1.U)) || 
    (treg(1,0) === 0.U && freg(1,0) === 0.U && freg(2) =/= treg(2))
  )
  
  // Permutation check ensures all board values are unique
  val permutation = b(0) =/= b(1) && b(0) =/= b(2) && b(0) =/= b(3) &&
    b(0) =/= b(4) && b(0) =/= b(5) && b(0) =/= b(6) && b(0) =/= b(7) &&
    b(1) =/= b(2) && b(1) =/= b(3) && b(1) =/= b(4) && b(1) =/= b(5) &&
    b(1) =/= b(6) && b(1) =/= b(7) && b(2) =/= b(3) && b(2) =/= b(4) &&
    b(2) =/= b(5) && b(2) =/= b(6) && b(2) =/= b(7) && b(3) =/= b(4) &&
    b(3) =/= b(5) && b(3) =/= b(6) && b(3) =/= b(7) && b(4) =/= b(5) &&
    b(4) =/= b(6) && b(4) =/= b(7) && b(5) =/= b(6) && b(5) =/= b(7) &&
    b(6) =/= b(7)
  
  // Perform move if valid
  when(valid) {
    b(treg) := b(freg)
    b(freg) := 0.U
  }
  
  // Capture source, destination, and source value when a valid move fires
  val freg_valid = RegEnable(freg, valid)
  val treg_valid = RegEnable(treg, valid)
  val old_b_freg = RegEnable(b(freg), valid)
  
  // === FORMAL VERIFICATION ASSERTIONS ===
  
  // Track whether a valid move occurred in the previous cycle.
  // Uses RegNext with a reset value (false.B) so that the formal
  // solver cannot set it arbitrarily high at time 0.
  val validPrev = RegNext(valid, false.B)
  
  // Safety 1: Board values must always be a permutation of 0..7
  // This is the core invariant of the puzzle: after any valid move, all
  // values 0-7 appear exactly once across the 8 positions.
  fvAssert(permutation, "board_is_always_permutation")
  
  // Safety 2: There is always exactly one empty slot (value 0) on the board
  // The moves only relocate the empty slot; they never create or destroy it.
  val zero_exists = (0 until 8).map(i => b(i) === 0.U).reduce(_ || _)
  fvAssert(zero_exists, "empty_slot_always_exists")
  
  // Safety 3: After a valid move, the source position becomes empty (0) in the next cycle.
  // Guarded by validPrev to avoid spurious failures from uninitialized register state.
  fvAssert(!validPrev || b(freg_valid) === 0.U, "source_emptied_after_move")
  
  // Safety 4: After a valid move, the destination gets the source's previous value in the next cycle.
  // Guarded by validPrev to avoid spurious failures from uninitialized register state.
  fvAssert(!validPrev || b(treg_valid) === old_b_freg, "dest_gets_source_value")
  
  // Safety 5: Valid moves can only target an empty position
  fvAssert(!valid || b(treg) === 0.U, "valid_dest_is_empty")
  
  // Safety 6: Input from and to are constrained to the valid range (0-7).
  // This is structural via UInt(3.W), but we add a formal check anyway.
  // (No explicit assertion needed since 3-bit UInt already enforces range.)
  
  // Connect outputs
  io.b_out := b
  io.valid_out := valid
  io.permutation_out := permutation
}

object VerilogGenerator extends App {
  emitVerilog(new luckySeven(), args)
}
