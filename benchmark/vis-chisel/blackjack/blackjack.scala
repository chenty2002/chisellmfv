package llmverify

import chisel3._
import chisel3.util._

object BlackjackStates extends ChiselEnum {
  val PLAYER_INIT, DEALER_INIT, PLAYER_HIT, DEALER_HIT, ENDGAME, DONE = Value
}

class blackjack extends Module {
  val io = IO(new Bundle {
    val pick = Input(UInt(4.W))
    val stay = Input(Bool())
    val win = Output(Bool())
    val lose = Output(Bool())
    val push = Output(Bool())
  })

  // State register
  val state = RegInit(BlackjackStates.PLAYER_INIT)
  
  // Deck array - 16 elements, each 5 bits wide
  // Initialize deck according to original Verilog
  val deckInit = Seq(0.U) ++ Seq.fill(9)(4.U) ++ Seq(16.U) ++ Seq.fill(5)(0.U)
  val deck = RegInit(VecInit(deckInit))
  
  // Scores for player and dealer
  val pScore = RegInit(0.U(5.W))
  val dScore = RegInit(0.U(5.W))
  
  // Number of cards for player and dealer
  val pCards = RegInit(0.U(4.W))
  val dCards = RegInit(0.U(4.W))
  
  // Ace flags for player and dealer
  val pAces = RegInit(false.B)
  val dAces = RegInit(false.B)
  
  // Valid flag
  val valid = Wire(Bool())
  valid := deck(io.pick) > 0.U
  
  // Blackjack detection
  val pBJ = Wire(Bool())
  val dBJ = Wire(Bool())
  pBJ := (pScore === 21.U) && (pCards === 2.U)
  dBJ := (dScore === 21.U) && (dCards === 2.U)
  
  // Output assignments
  io.lose := (state === BlackjackStates.DONE) && (dScore < 22.U) &&
    ((pScore > 21.U) || (pScore < dScore) || (dBJ && !pBJ))
  io.win := (state === BlackjackStates.DONE) && (pScore < 22.U) &&
    ((dScore > 21.U) || (dScore < pScore) || (pBJ && !dBJ))
  io.push := (state === BlackjackStates.DONE) && !io.lose && !io.win
  
  // Sequential logic
  when (valid) {
    switch (state) {
      is (BlackjackStates.PLAYER_INIT) {
        deck(io.pick) := deck(io.pick) - 1.U
        pScore := pScore + io.pick
        pCards := pCards + 1.U
        when (io.pick === 1.U) {
          pAces := true.B
        }
        when (pCards === 2.U) {
          state := BlackjackStates.DEALER_INIT
        }
      }
      is (BlackjackStates.DEALER_INIT) {
        deck(io.pick) := deck(io.pick) - 1.U
        dScore := dScore + io.pick
        dCards := dCards + 1.U
        when (io.pick === 1.U) {
          dAces := true.B
        }
        when (dCards === 2.U) {
          state := BlackjackStates.PLAYER_HIT
        }
      }
      is (BlackjackStates.PLAYER_HIT) {
        when (io.stay || (pScore > 20.U)) {
          state := BlackjackStates.DEALER_HIT
        }.otherwise {
          deck(io.pick) := deck(io.pick) - 1.U
          pScore := pScore + io.pick
          pCards := pCards + 1.U
          when (io.pick === 1.U) {
            pAces := true.B
          }
          when (pScore > 20.U) {
            state := BlackjackStates.DEALER_HIT
          }
        }
      }
      is (BlackjackStates.DEALER_HIT) {
        when ((dScore > 16.U) || (dAces && (dScore > 6.U))) {
          state := BlackjackStates.ENDGAME
        }.otherwise {
          deck(io.pick) := deck(io.pick) - 1.U
          dScore := dScore + io.pick
          dCards := dCards + 1.U
          when (io.pick === 1.U) {
            dAces := true.B
          }
          when (dScore > 20.U) {
            state := BlackjackStates.ENDGAME
          }
        }
      }
      is (BlackjackStates.ENDGAME) {
        when ((pScore < 11.U) && pAces) {
          pScore := pScore + 10.U
        }
        when ((dScore < 11.U) && dAces) {
          dScore := dScore + 10.U
        }
        state := BlackjackStates.DONE
      }
      is (BlackjackStates.DONE) {
        // No state change
      }
    }
  }
}

object VerilogGenerator extends App {
  emitVerilog(new blackjack(), args)
}