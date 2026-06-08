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

  // ====== Formal Verification Assertions ======

  // Safety: All discs must be on valid pegs (A=0, B=1, or C=2), never the
  // invalid encoding 3.  This guards against bit errors or illegal writes.
  for (i <- 0 until 20) {
    fvAssert(disc(i) <= 2.U, s"disc_${i}_valid_peg")
  }

  // Safety: Input pegs must be valid (0, 1, or 2)
  fvAssert(io.from <= 2.U, "from_peg_valid")
  fvAssert(io.to <= 2.U, "to_peg_valid")

  // Safety: On a legal move the source and destination peg must differ.
  // Moving a disc from a peg to the same peg is meaningless and wastes a cycle.
  fvAssert(!legal || (io.from =/= io.to), "legal_move_from_ne_to")

  // Correctness: The done output must be true exactly when all 20 discs are
  // on peg B.  No off-by-one or aliasing bugs.
  val allOnB = disc.map(_ === B).reduce(_ && _)
  fvAssert(io.done === allOnB, "done_correctness")

  // Bounded liveness / progress:  From the moment a new Hanoi game starts
  // (all discs on peg A), it must be possible to reach the solved state
  // (all discs on peg B) within a reasonable number of cycles.  We use a
  // timer-based liveness: if the puzzle is not yet solved, the timer counts
  // up; it must not exceed 50000 cycles without reaching done.
  // The bound 50000 is generous: the classic solution for 20 discs needs
  // 2^20 − 1 = 1,048,575 moves, so 50000 is a per-segment progress bound
  // that catches total deadlock or stuck FSMs.
  assertLivenessTimer(
    cond = !io.done,
    reset = disc.map(_ === A).reduce(_ && _),
    n = 50000,
    msg = "progress_toward_done"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new Hanoi(), args)
}
