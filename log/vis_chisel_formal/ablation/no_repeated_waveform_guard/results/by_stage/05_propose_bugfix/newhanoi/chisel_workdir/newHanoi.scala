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
  // Need to validate that input pegs are valid (0, 1, or 2) before allowing the move
  val validPegs = io.from <= 2.U && io.to <= 2.U
  val legal = validPegs && (sizeFrom < 20.U) && (sizeFrom < sizeTo)

  // Sequential logic - update disc position if move is legal
  when(legal) {
    disc(sizeFrom) := io.to
  }

  // Done check - all discs on peg B
  io.done := disc.map(_ === B).reduce(_ && _)

  // === FORMAL ASSERTIONS ===

  // Safety 1: All disc peg values must be valid (only A=0, B=1, or C=2)
  for (i <- 0 until 20) {
    fvAssert(disc(i) <= 2.U, s"disc_${i}_valid_peg")
  }

  // Safety 2: When move is illegal, no disc register changes value
  // Use a registered version of legal to account for the one-cycle latency:
  // disc(i) updates at the clock edge based on the previous cycle's legal evaluation,
  // so we must check stability against the previous cycle's legality, not the current cycle's.
  val legalReg = RegNext(legal, false.B)
  for (i <- 0 until 20) {
    assertStableWhen(!legalReg, disc(i), s"disc_${i}_stable_when_illegal")
  }

  // Safety 3: When a move is legal, the source disc is indeed on the 'from' peg
  fvAssert(!legal || disc(sizeFrom) === io.from, "source_disc_on_from_peg")

  // Safety 4: When a move is legal, the 'from' peg has at least one disc
  fvAssert(!legal || sizeFrom < 20.U, "from_peg_has_disc")

  // Safety 5: The done output is true iff every disc is on peg B
  val allOnB = disc.map(_ === B).reduce(_ && _)
  fvAssert(io.done === allOnB, "done_signal_correct")
}

object VerilogGenerator extends App {
  emitVerilog(new Hanoi(), args)
}
