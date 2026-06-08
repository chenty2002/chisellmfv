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

  // ===== FORMAL ASSERTIONS =====

  // === Safety Invariant ===
  // The core puzzle constraint: the goat must never be left alone with the wolf
  // or the cabbage without the man (boat) present. This must hold at every cycle.
  fvAssert(io.safe, "cgw_safe_invariant")

  // === Bounded Liveness / Progress ===
  // From any state, the system should reach the final state (all entities on the
  // right side) within 20 steps. The classic puzzle solution takes 7 moves,
  // so 20 provides ample margin while bounding the verification depth.
  // Using relaxed liveness: the final state must be reached within 1..20 cycles
  // from any state (request is always true).
  astRelaxedLiveness(true.B, io.finalState, 20, "cgw_reach_final_state")
}

object VerilogGenerator extends App {
  emitVerilog(new cgw(), args)
}
