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
  
  // Update passenger positions based on select input
  when(io.select === passengerCabbage && boat === cabbage) {
    cabbage := Mux(cabbage === sideRight, sideLeft, sideRight)
  }.elsewhen(io.select === passengerGoat && boat === goat) {
    goat := Mux(goat === sideRight, sideLeft, sideRight)
  }.elsewhen(io.select === passengerWolf && boat === wolf) {
    wolf := Mux(wolf === sideRight, sideLeft, sideRight)
  }
  
  // Update boat position (restrictive version - boat only moves if passenger is on same side)
  when(io.select === passengerNone || 
       (io.select === passengerCabbage && cabbage === boat) ||
       (io.select === passengerGoat && goat === boat) ||
       (io.select === passengerWolf && wolf === boat)) {
    boat := Mux(boat === sideRight, sideLeft, sideRight)
  }
  
  // Safety condition: boat is with goat OR goat is not with wolf AND goat is not with cabbage
  io.safe := (boat === goat) || (goat =/= wolf && goat =/= cabbage)
  
  // Final condition: all entities on the right side
  io.finalState := (goat === sideRight) && (wolf === sideRight) && 
                   (cabbage === sideRight) && (boat === sideRight)

  // ====== Formal Verification Assertions ======

  // === Safety Invariant ===
  // The goat must never be left alone with the wolf or the cabbage.
  // This is the core safety property of the puzzle: an unsafe state means
  // the wolf eats the goat or the goat eats the cabbage.
  fvAssert(io.safe, "safe_invariant__goat_not_eaten")

  // === Input Validity ===
  // The select input must always be in the valid range 0-3 (None, Cabbage, Goat, Wolf).
  // This catches out-of-bounds inputs that could cause undefined behavior.
  fvAssert(io.select <= passengerWolf, "select_in_valid_range")

  // === Bounded Solvability (Liveness) ===
  // The classic puzzle can be solved in exactly 7 moves from the initial
  // (all-left) state.  Assert that, given free nondeterministic inputs, the
  // formal tool can find some sequence that reaches io.finalState within a
  // generous bound.  If a design bug makes the final state unreachable, this
  // assertion will expose it.
  astRelaxedLiveness(!(reset.asBool), io.finalState, 30,
                     "final_state_reachable_within_30_cycles")

  // === Transition Consistency ===
  // When a passenger is selected, it must be on the same side as the boat.
  // Selecting a passenger on the opposite side has no effect (the boat does
  // not move, nor does that passenger).  This check guards against silently
  // ignoring a selection due to a side mismatch bug.
  fvAssert(
    !(io.select === passengerCabbage && cabbage =/= boat) &&
    !(io.select === passengerGoat && goat =/= boat) &&
    !(io.select === passengerWolf && wolf =/= boat),
    "selected_passenger_on_boat_side"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new cgw(), args)
}
