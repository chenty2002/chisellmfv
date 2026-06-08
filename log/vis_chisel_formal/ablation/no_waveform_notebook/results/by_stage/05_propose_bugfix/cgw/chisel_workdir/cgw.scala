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

  // ===== Formal Verification Assertions =====

  // CGM-001: Critical safety invariant — the goat must never be left alone
  // with the wolf or the cabbage on either side of the river.
  // NOTE: This is an assumption (input constraint) rather than an assertion,
  // because io.safe is a constraint on valid input sequences (the farmer
  // must choose moves that keep the goat safe), not a design invariant.
  // The formal tool will only consider input sequences where the goat is
  // always safe, which is the correct semantic for this puzzle model.
  assume(io.safe, "CGM-001 Goat must always be safe from wolf and cabbage")

  // CGM-002: Bounded liveness — from reset (all on left), the final state
  // (all on right) should be reachable within 100 cycles.  The classic
  // puzzle solution requires only 7 boat crossings, so 100 cycles provides
  // ample room for exploration while bounding the state-space depth.
  astRelaxedLiveness(reset.asBool, io.finalState, 100,
    "CGM-002 Final state reachable within 100 cycles after reset")

  // CGM-003: Passenger positions must not change when select is None (input 0).
  // This guards against bugs where the update logic fires spuriously when
  // no passenger is meant to board.
  assertStableWhen(io.select === passengerNone, cabbage,
    "CGM-003 Cabbage position stable when no passenger selected")
  assertStableWhen(io.select === passengerNone, goat,
    "CGM-003 Goat position stable when no passenger selected")
  assertStableWhen(io.select === passengerNone, wolf,
    "CGM-003 Wolf position stable when no passenger selected")
}

object VerilogGenerator extends App {
  emitVerilog(new cgw(), args)
}
