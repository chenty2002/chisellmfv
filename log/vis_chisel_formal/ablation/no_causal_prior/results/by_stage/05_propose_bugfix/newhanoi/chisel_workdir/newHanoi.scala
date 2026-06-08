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

  // Sequential logic - update disc position if move is legal
  when(legal) {
    disc(sizeFrom) := io.to
  }

  // Done check - all discs on peg B
  io.done := disc.map(_ === B).reduce(_ && _)

  // ========== Formal Verification Assertions ==========

  // 0. Input constraints: constrain formal inputs to valid peg values
  fvAssume(io.from <= 2.U, "assume_from_valid")
  fvAssume(io.to <= 2.U, "assume_to_valid")
  fvAssume(io.from =/= io.to, "assume_from_to_different")

  // 1. Input peg values are valid (only A=0, B=1, C=2 allowed)
  fvAssert(io.from <= 2.U, "from_peg_valid")
  fvAssert(io.to <= 2.U, "to_peg_valid")
  fvAssert(io.from =/= io.to, "from_to_different")

  // 2. All discs are always on valid pegs (not in undefined state 3)
  for (i <- 0 until 20) {
    fvAssert(disc(i) <= 2.U, s"disc_${i}_valid_peg")
  }

  // 3. Legal move implies the source peg has at least one disc
  fvAssert(!legal || (sizeFrom < 20.U), "legal_move_from_not_empty")

  // 4. Legal move implies the moving disc is smaller than the top disc on target peg
  fvAssert(!legal || (sizeFrom < sizeTo), "legal_move_smaller_than_target")

  // 5. After a legal move, the moved disc is on the target peg 1 cycle later
  assertAlwaysAfterNStepWhen(legal, 1, disc(sizeFrom) === io.to, "moved_disc_reaches_target")

  // 6. Stability: when no legal move occurs, the disc state does not change
  for (i <- 0 until 20) {
    assertStableWhen(!legal, disc(i), s"disc_${i}_stable_when_no_legal_move")
  }

  // 7. The done signal is true iff all discs are on peg B
  fvAssert(io.done === disc.map(_ === B).reduce(_ && _), "done_signal_correct")
}

object VerilogGenerator extends App {
  emitVerilog(new Hanoi(), args)
}
