package llmverify

import chisel3._
import chisel3.util._
import chisel3.ltl._
import chisel3.ltl.Sequence._

class Hanoi extends Module {
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

  // Legal move check.
  // A move is legal only if the target peg is valid (0, 1, or 2).
  // Without this guard, io.to=3 would make sizeTo=20 (no disc on invalid peg)
  // and the sizeFrom < sizeTo check would pass, incorrectly marking the move legal.
  val legal = (sizeFrom < 20.U) && (sizeFrom < sizeTo) && (io.to <= 2.U)

  // Sequential logic - update disc position if move is legal.
  // Once the puzzle is solved (all discs on peg B), disable all further moves
  // to preserve the solved state invariant.
  when(legal && !io.done) {
    disc(sizeFrom) := io.to
  }

  // Done check - all discs on peg B
  io.done := disc.map(_ === B).reduce(_ && _)

  // ── Formal Verification Assertions ──

  // 1. All 20 discs must always reside on a valid peg (A=0, B=1, or C=2).
  //    Because io.to is UInt(2.W), it can be 3 (invalid).  If a legal move
  //    uses io.to=3, the disc would be corrupted to an invalid peg value,
  //    breaking the game invariant.
  for (i <- 0 until 20) {
    AssertProperty(disc(i) <= 2.U, s"disc_${i}_valid_peg")
  }

  // 2. Once all discs are on peg B (io.done is true), no further move can
  //    disturb them — stability of the solved state.
  AssertProperty(io.done |-> Sequence(io.done).delay(1), None, None, Some("done_stays_done"))

  // 3. A legal move must target a valid peg (0, 1, or 2); otherwise a disc
  //    would be silently corrupted to an invalid value.
  AssertProperty(!legal || io.to <= 2.U, "legal_move_to_valid_peg")
}

object VerilogGenerator extends App {
  emitVerilog(new Hanoi(), args)
}
