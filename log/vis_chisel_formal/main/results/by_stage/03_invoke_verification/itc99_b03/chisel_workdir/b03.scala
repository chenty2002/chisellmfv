package llmverify

import chisel3._
import chisel3.util._
import chisel3.ltl._
import chisel3.ltl.Sequence._

class b03 extends Module {
  val io = IO(new Bundle {
    val REQUEST1 = Input(Bool())
    val REQUEST2 = Input(Bool())
    val REQUEST3 = Input(Bool())
    val REQUEST4 = Input(Bool())
    val GRANT_O = Output(UInt(4.W))
  })

  // Constants
  val U1 = "b100".U(3.W)
  val U2 = "b010".U(3.W)
  val U3 = "b001".U(3.W)
  val U4 = "b111".U(3.W)

  // State enumeration
  val sInit :: sAnalisReq :: sAssegna :: Nil = Enum(3)
  val stato = RegInit(sInit)

  // Queue registers
  val coda0 = RegInit(0.U(3.W))
  val coda1 = RegInit(0.U(3.W))
  val coda2 = RegInit(0.U(3.W))
  val coda3 = RegInit(0.U(3.W))

  // Request registers
  val ru1 = RegInit(false.B)
  val ru2 = RegInit(false.B)
  val ru3 = RegInit(false.B)
  val ru4 = RegInit(false.B)

  // Follow-up registers
  val fu1 = RegInit(false.B)
  val fu2 = RegInit(false.B)
  val fu3 = RegInit(false.B)
  val fu4 = RegInit(false.B)

  // Grant register
  val grant = RegInit(0.U(4.W))

  // Output register
  io.GRANT_O := grant

  // State machine
  switch(stato) {
    is(sInit) {
      ru1 := io.REQUEST1
      ru2 := io.REQUEST2
      ru3 := io.REQUEST3
      ru4 := io.REQUEST4
      stato := sAnalisReq
    }

    is(sAnalisReq) {
      // Queue update logic
      when(ru1 && !fu1) {
        coda3 := coda2
        coda2 := coda1
        coda1 := coda0
        coda0 := U1
      }.elsewhen(ru2 && !fu2) {
        coda3 := coda2
        coda2 := coda1
        coda1 := coda0
        coda0 := U2
      }.elsewhen(ru3 && !fu3) {
        coda3 := coda2
        coda2 := coda1
        coda1 := coda0
        coda0 := U3
      }.elsewhen(ru4 && !fu4) {
        coda3 := coda2
        coda2 := coda1
        coda1 := coda0
        coda0 := U4
      }

      // Update follow-up registers
      fu1 := ru1
      fu2 := ru2
      fu3 := ru3
      fu4 := ru4

      stato := sAssegna
    }

    is(sAssegna) {
      when(fu1 || fu2 || fu3 || fu4) {
        // Grant assignment based on queue head
        switch(coda0) {
          is(U1) { grant := "b1000".U }
          is(U2) { grant := "b0100".U }
          is(U3) { grant := "b0010".U }
          is(U4) { grant := "b0001".U }
        }

        // Default case for grant assignment
        when(!(coda0 === U1 || coda0 === U2 || coda0 === U3 || coda0 === U4)) {
          grant := "b0000".U
        }

        // Shift queue
        coda0 := coda1
        coda1 := coda2
        coda2 := coda3
        coda3 := 0.U
      }

      // Capture new requests
      ru1 := io.REQUEST1
      ru2 := io.REQUEST2
      ru3 := io.REQUEST3
      ru4 := io.REQUEST4

      stato := sAnalisReq
    }
  }

  // ========== FORMAL ASSERTIONS ==========

  // Safety 1: Grant output has at most one bit set (one-hot0)
  assert(PopCount(grant.asBools) <= 1.U, "GRANT_O_one_hot0")

  // Safety 2: FSM stays in valid state encoding
  assert(stato === sInit || stato === sAnalisReq || stato === sAssegna, "FSM_valid_state")

  // Safety 3: Queue entries contain only valid encodings (0, U1, U2, U3, U4)
  assert(coda0 === 0.U || coda0 === U1 || coda0 === U2 || coda0 === U3 || coda0 === U4, "coda0_valid_encoding")
  assert(coda1 === 0.U || coda1 === U1 || coda1 === U2 || coda1 === U3 || coda1 === U4, "coda1_valid_encoding")
  assert(coda2 === 0.U || coda2 === U1 || coda2 === U2 || coda2 === U3 || coda2 === U4, "coda2_valid_encoding")
  assert(coda3 === 0.U || coda3 === U1 || coda3 === U2 || coda3 === U3 || coda3 === U4, "coda3_valid_encoding")

  // Safety 4: Grant matches queue head when pending requests exist
  assert(!((coda0 === U1) && (fu1 || fu2 || fu3 || fu4)) || grant(3), "grant_matches_U1")
  assert(!((coda0 === U2) && (fu1 || fu2 || fu3 || fu4)) || grant(2), "grant_matches_U2")
  assert(!((coda0 === U3) && (fu1 || fu2 || fu3 || fu4)) || grant(1), "grant_matches_U3")
  assert(!((coda0 === U4) && (fu1 || fu2 || fu3 || fu4)) || grant(0), "grant_matches_U4")

  // Safety 5: When no valid encoding in coda0 and pending requests, grant must be zero
  assert(!(!(coda0 === U1 || coda0 === U2 || coda0 === U3 || coda0 === U4) && (fu1 || fu2 || fu3 || fu4)) || grant === 0.U, "grant_zero_on_invalid_coda0")

  // Bounded liveness 1: FSM makes progress (state changes every cycle after the first)
  val first_cycle = RegInit(true.B)
  first_cycle := false.B
  val stato_prev = RegNext(stato)
  assert(first_cycle || stato =/= stato_prev, "FSM_progress")

  // Bounded liveness 2: Requests eventually lead to grants within 15 cycles
  // When a request is asserted, the corresponding grant bit must become true within 1..15 cycles
  AssertProperty(io.REQUEST1 |-> Sequence(grant(3)).delayRange(1, 15), None, None, Some("REQUEST1_bounded_liveness"))
  AssertProperty(io.REQUEST2 |-> Sequence(grant(2)).delayRange(1, 15), None, None, Some("REQUEST2_bounded_liveness"))
  AssertProperty(io.REQUEST3 |-> Sequence(grant(1)).delayRange(1, 15), None, None, Some("REQUEST3_bounded_liveness"))
  AssertProperty(io.REQUEST4 |-> Sequence(grant(0)).delayRange(1, 15), None, None, Some("REQUEST4_bounded_liveness"))
}

object VerilogGenerator extends App {
  emitVerilog(new b03(), args)
}
