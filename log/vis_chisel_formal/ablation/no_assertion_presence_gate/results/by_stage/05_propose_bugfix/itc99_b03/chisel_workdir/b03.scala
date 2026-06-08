package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

class b03 extends Module with Formal {
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
      }.otherwise {
        // Clear grant when no follow-up flag is set, preventing stale grants
        // from persisting after the corresponding request has been serviced
        grant := 0.U
      }
      
      // Capture new requests
      ru1 := io.REQUEST1
      ru2 := io.REQUEST2
      ru3 := io.REQUEST3
      ru4 := io.REQUEST4
      
      stato := sAnalisReq
    }
  }

  // ========== Formal Verification Assertions ==========

  // --- Safety: Grant is one-hot (at most one bit set) ---
  assertOneHot0(io.GRANT_O, "grant_one_hot0")

  // --- Safety: Grant only fires when the corresponding request was made ---
  // GRANT_O(3) corresponds to REQUEST1 (encoded as U1)
  assertImplies(io.GRANT_O(3), fu1, "grant_bit3_from_request1")
  // GRANT_O(2) corresponds to REQUEST2 (encoded as U2)
  assertImplies(io.GRANT_O(2), fu2, "grant_bit2_from_request2")
  // GRANT_O(1) corresponds to REQUEST3 (encoded as U3)
  assertImplies(io.GRANT_O(1), fu3, "grant_bit1_from_request3")
  // GRANT_O(0) corresponds to REQUEST4 (encoded as U4)
  assertImplies(io.GRANT_O(0), fu4, "grant_bit0_from_request4")

  // --- Safety: When in Assegna state with pending requests, grant must be non-zero ---
  // If any follow-up is set, the queue head should contain a valid encoding and grant should fire
  assertImplies(stato === sAssegna && (fu1 || fu2 || fu3 || fu4), io.GRANT_O.orR, "grant_when_pending")

  // --- Liveness: FSM makes progress (not stuck) ---
  // The normal cycle is: sAnalisReq -> sAssegna -> sAnalisReq -> ...
  // When in sAnalisReq, assert that sAssegna is reached within 3 cycles
  astRelaxedLiveness(stato === sAnalisReq, stato === sAssegna, 3, "fsm_analisreq_to_assegna_liveness")
  // When in sAssegna, assert that sAnalisReq is reached within 3 cycles
  astRelaxedLiveness(stato === sAssegna, stato === sAnalisReq, 3, "fsm_assegna_to_analisreq_liveness")
}

object VerilogGenerator extends App {
  emitVerilog(new b03(), args)
}
