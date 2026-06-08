package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

// This model encodes the famous cabbage/goat/wolf puzzle.
// A man has to cross a river on a boat.  He is traveling with a cabbage,
// a goat, and a wolf.
// If left unattended, the wolf will eat the goat, and the goat will eat
// the cabbage.  Only one passenger can be carried by the boat besides the
// man himself.
// How can the man proceed to successfully cross the river without losing
// either the cabbage or the goat?

class cgw extends Module with Formal {
  val io = IO(new Bundle {
    val select = Input(UInt(2.W))  // 0:NONE, 1:CABBAGE, 2:GOAT, 3:WOLF
    val safe = Output(Bool())
    val finalState = Output(Bool())
  })
  
  // Enum definitions
  val passengerNone :: passengerCabbage :: passengerGoat :: passengerWolf :: Nil = Enum(4)
  val sideLeft :: sideRight :: Nil = Enum(2)
  
  // State registers for positions
  val boat = RegInit(sideLeft)
  val cabbage = RegInit(sideLeft)
  val goat = RegInit(sideLeft)
  val wolf = RegInit(sideLeft)
  
  // Determine which move is selected (if any)
  val moveCabbage = io.select === passengerCabbage && boat === cabbage
  val moveGoat    = io.select === passengerGoat && boat === goat
  val moveWolf    = io.select === passengerWolf && boat === wolf
  val moveNone    = io.select === passengerNone
  val anyMove     = moveCabbage || moveGoat || moveWolf || moveNone

  // Compute the opposite side
  val nextSide = Mux(boat === sideRight, sideLeft, sideRight)

  // Proposed next positions for safety check
  val nextCabbage = Mux(moveCabbage, nextSide, cabbage)
  val nextGoat    = Mux(moveGoat,    nextSide, goat)
  val nextWolf    = Mux(moveWolf,    nextSide, wolf)
  val nextBoat    = Mux(anyMove,     nextSide, boat)

  // A move is safe if the resulting state preserves the safety invariant:
  //   boat is with goat  OR  goat is not with wolf AND goat is not with cabbage
  val moveIsSafe = (nextBoat === nextGoat) || (nextGoat =/= nextWolf && nextGoat =/= nextCabbage)

  // Update positions only if the move is safe
  when(moveCabbage && moveIsSafe) {
    cabbage := nextCabbage
  }
  when(moveGoat && moveIsSafe) {
    goat := nextGoat
  }
  when(moveWolf && moveIsSafe) {
    wolf := nextWolf
  }
  when(anyMove && moveIsSafe) {
    boat := nextBoat
  }
  
  // Safety condition: boat is with goat OR goat is not with wolf AND goat is not with cabbage
  io.safe := (boat === goat) || (goat =/= wolf && goat =/= cabbage)
  
  // Final condition: all entities on the right side
  io.finalState := (goat === sideRight) && (wolf === sideRight) && 
                   (cabbage === sideRight) && (boat === sideRight)

  // ===== FORMAL ASSERTIONS =====

  // === Safety Invariant ===
  // The core puzzle constraint: the goat must never be left alone with the wolf
  // or the cabbage without the man (boat) present. This must hold at every cycle.
  fvAssert(io.safe, "cgw_safe_invariant")

  // === Reachability Cover ===
  // Verify that the puzzle solution exists: from the initial state, the final state
  // (all entities on the right side) can be reached under some input sequence.
  // A universal liveness (forall input sequences) is not appropriate here because
  // the solver controls io.select and can always pick adversarial inputs that stall
  // progress. Instead we use a cover to check existential reachability.
  cover(io.finalState, "cgw_reach_final_state")
}

object VerilogGenerator extends App {
  emitVerilog(new cgw(), args)
}
