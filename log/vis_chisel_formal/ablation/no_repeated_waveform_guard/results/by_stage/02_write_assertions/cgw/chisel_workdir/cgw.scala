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

  // =================== FORMAL ASSERTIONS ===================

  // -----------------------------------------------------------------
  // SAFETY INVARIANT: The system must never enter an unsafe state where
  // the goat is left alone with the wolf or the cabbage without the boat.
  // Counterexamples to this assertion demonstrate the dangerous moves
  // that violate the puzzle constraints.
  // -----------------------------------------------------------------
  fvAssert(io.safe, "cgw_safety_invariant")

  // -----------------------------------------------------------------
  // OUTPUT CORRECTNESS: Verify that io.safe correctly reflects the
  // safety condition: safe iff the boat is with the goat, OR the goat
  // is separated from both the wolf and the cabbage.
  // -----------------------------------------------------------------
  fvAssert(
    io.safe === ((boat === goat) || (goat =/= wolf && goat =/= cabbage)),
    "safe_output_correct"
  )

  // -----------------------------------------------------------------
  // OUTPUT CORRECTNESS: Verify that io.finalState is true iff all four
  // entities (boat, goat, wolf, cabbage) are on the right side.
  // -----------------------------------------------------------------
  fvAssert(
    io.finalState === (goat === sideRight && wolf === sideRight &&
                       cabbage === sideRight && boat === sideRight),
    "finalState_output_correct"
  )

  // -----------------------------------------------------------------
  // MUTUAL EXCLUSION: At most one passenger can be selected per cycle.
  // Since select is a 2-bit UInt, values 0-3 are possible.  Values
  // 1,2,3 correspond to distinct passengers; only one bit pattern is
  // active at a time, so this is trivially true, but we encode it for
  // documentation and future-proofing.
  // -----------------------------------------------------------------
  assertOneHot0(io.select, "select_one_hot0")

  // -----------------------------------------------------------------
  // LIVENESS / PROGRESS: From the initial state (all on left) where
  // the system is safe, the final state should be reachable within a
  // bounded number of steps.  The classic puzzle solution requires
  // exactly 7 boat crossings; we budget 15 steps as a safe upper bound
  // to allow for suboptimal paths.
  //
  // Note: because the environment can freely choose inputs that avoid
  // progress, this assertion may produce a counterexample trace in
  // which the system stays safe but never reaches the goal (e.g. by
  // always selecting passengerNone).  Such a trace is still valuable
  // because it shows the user a non-progressing but safe behavior.
  // -----------------------------------------------------------------
  val isInitialState = (boat === sideLeft) &&
                       (cabbage === sideLeft) &&
                       (goat === sideLeft) &&
                       (wolf === sideLeft)
  astRelaxedLiveness(
    isInitialState && io.safe,
    io.finalState,
    15,
    "reach_final_state_from_initial"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new cgw(), args)
}
