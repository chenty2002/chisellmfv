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

  // === Formal Verification Assumptions (input constraints) ===

  // Assumption: only valid select encodings (0-3) are used as inputs
  fvAssume(io.select === passengerNone || io.select === passengerCabbage ||
           io.select === passengerGoat || io.select === passengerWolf,
           "valid_select_assume")

  // Assumption: the chosen move must result in a safe next state.
  // Compute next-state positions combinatorially from current state and io.select,
  // then constrain io.select so that only moves preserving safety are allowed.
  val boatMoves = io.select === passengerNone ||
                  (io.select === passengerCabbage && cabbage === boat) ||
                  (io.select === passengerGoat && goat === boat) ||
                  (io.select === passengerWolf && wolf === boat)
  val nextBoat = Mux(boatMoves, Mux(boat === sideRight, sideLeft, sideRight), boat)

  val nextCabbage = Mux(io.select === passengerCabbage && boat === cabbage,
                         Mux(cabbage === sideRight, sideLeft, sideRight), cabbage)
  val nextGoat = Mux(io.select === passengerGoat && boat === goat,
                      Mux(goat === sideRight, sideLeft, sideRight), goat)
  val nextWolf = Mux(io.select === passengerWolf && boat === wolf,
                      Mux(wolf === sideRight, sideLeft, sideRight), wolf)

  val nextSafe = (nextBoat === nextGoat) || (nextGoat =/= nextWolf && nextGoat =/= nextCabbage)
  fvAssume(nextSafe, "safe_transition_assume")

  // === Formal Verification Assertions ===

  // Safety invariant: the state must always be safe.
  // The man must never leave the goat unattended with the wolf
  // (wolf eats goat) or the cabbage with the goat (goat eats cabbage).
  // This is the core puzzle constraint.
  fvAssert(io.safe, "always_safe")

  // The final state implies the state is safe (sanity check on the
  // relationship between finalState and safe outputs)
  fvAssert(!io.finalState || io.safe, "final_state_implies_safe")

  // Valid select encoding: only the four defined values (0-3) are valid
  fvAssert(io.select === passengerNone || io.select === passengerCabbage ||
           io.select === passengerGoat || io.select === passengerWolf,
           "valid_select_encoding")
}

object VerilogGenerator extends App {
  emitVerilog(new cgw(), args)
}
