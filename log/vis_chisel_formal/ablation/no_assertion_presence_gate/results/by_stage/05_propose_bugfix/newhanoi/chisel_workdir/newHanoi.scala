package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

class Hanoi extends Module with Formal {
  val io = IO(new Bundle {
    val from = Input(UInt(2.W))  // 2 bits for enum (A=0, B=1, C=2)
    val to = Input(UInt(2.W))    // 2 bits for enum
    val done = Output(Bool())
  })

  // Enum values
  val A = 0.U(2.W)
  val B = 1.U(2.W)
  val C = 2.U(2.W)

  // Disc array - 20 discs, each storing a peg (2 bits)
  val disc = RegInit(VecInit(Seq.fill(20)(A)))

  // Calculate sizeFrom - find the first disc on the 'from' peg
  val sizeFrom = Wire(UInt(5.W))
  val fromMatches = disc.map(_ === io.from)
  val fromPriorityEncoder = PriorityEncoder(fromMatches.reverse)
  sizeFrom := Mux(fromMatches.reduce(_ || _), 
                  19.U - fromPriorityEncoder,
                  20.U)

  // Calculate sizeTo - find the first disc on the 'to' peg
  val sizeTo = Wire(UInt(5.W))
  val toMatches = disc.map(_ === io.to)
  val toPriorityEncoder = PriorityEncoder(toMatches.reverse)
  sizeTo := Mux(toMatches.reduce(_ || _),
                19.U - toPriorityEncoder,
                20.U)

  // Legal move check
  val legal = (sizeFrom < 20.U) && (sizeFrom < sizeTo)

  // Only allow moves when both from and to are valid pegs (0, 1, or 2).
  // Without this guard the formal solver can assign invalid peg 3 to the
  // inputs, causing discs to be placed on an invalid peg and violating
  // the disc_*_on_valid_peg assertions.  fvAssume is unavailable in this
  // chiselFv version so we guard the update path instead.
  val validPegs = (io.from <= 2.U) && (io.to <= 2.U)

  // Sequential logic - update disc position if move is legal and inputs valid
  when(legal && validPegs) {
    disc(sizeFrom) := io.to
  }

  // Done check - all discs on peg B
  io.done := disc.map(_ === B).reduce(_ && _)

  // ========== Formal Verification Assertions ==========

  // Safety 1: All discs must be on valid pegs (A=0, B=1, or C=2).
  // The 2-bit encoding allows value 3 (invalid); this must never occur.
  for (i <- 0 until 20) {
    fvAssert(disc(i) <= 2.U, s"disc_${i}_on_valid_peg")
  }

  // Safety 2: Input pegs must be valid (A, B, or C).
  fvAssert(io.from <= 2.U, "from_valid_peg")
  fvAssert(io.to <= 2.U, "to_valid_peg")

  // Safety 3: A legal move must have source and destination pegs different.
  // Moving a disc to the same peg it is already on is meaningless.
  fvAssert(!legal || (io.from =/= io.to), "legal_move_from_to_different")

  // Safety 4: sizeFrom and sizeTo are always in the valid range [0, 20].
  fvAssert(sizeFrom <= 20.U, "sizeFrom_in_range")
  fvAssert(sizeTo <= 20.U, "sizeTo_in_range")

  // Safety 5: When a disc is moved legally, it must end up on the 'to' peg
  // in the next cycle.  Capture the move-time values with RegNext so the
  // consequent is evaluated with the same address and destination.
  // Guard with prevValid to suppress spurious failure from unconstrained
  // RegNext initial values in the formal model.  RegNext registers have no
  // explicit reset and the solver can pick arbitrary initial values, so we
  // ignore the first cycle before they have been clocked.
  // Also guard with validPegsPrev because the disc-update path is guarded
  // by legal && validPegs; when validPegs is false the disc is never moved,
  // so the assertion must not fire in that case.
  val prevValid = RegInit(false.B)
  prevValid := true.B
  val legalPrev = RegNext(legal)
  val sizeFromPrev = RegNext(sizeFrom)
  val toPrev = RegNext(io.to)
  val validPegsPrev = RegNext(validPegs)
  fvAssert(!prevValid || !legalPrev || !validPegsPrev || (disc(sizeFromPrev) === toPrev),
           "disc_moved_to_destination")

  // Correctness 6: The done output correctly reflects all discs on B.
  val allOnB = disc.map(_ === B).reduce(_ && _)
  fvAssert(io.done === allOnB, "done_signal_correct")
}

object VerilogGenerator extends App {
  emitVerilog(new Hanoi(), args)
}
