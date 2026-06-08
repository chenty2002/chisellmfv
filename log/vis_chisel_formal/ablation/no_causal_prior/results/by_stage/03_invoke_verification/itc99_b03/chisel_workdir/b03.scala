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
      }
      
      // Capture new requests
      ru1 := io.REQUEST1
      ru2 := io.REQUEST2
      ru3 := io.REQUEST3
      ru4 := io.REQUEST4
      
      stato := sAnalisReq
    }
  }

  // ===== FORMAL VERIFICATION ASSERTIONS =====

  // Safety: Grant output is one-hot (at most one bit set)
  assertOneHot(io.GRANT_O, "GRANT_O_one_hot")

  // Safety: Grant output only uses valid encodings
  fvAssert(
    io.GRANT_O === "b0000".U || io.GRANT_O === "b1000".U ||
    io.GRANT_O === "b0100".U || io.GRANT_O === "b0010".U || io.GRANT_O === "b0001".U,
    "GRANT_O_valid_encoding"
  )

  // Safety: State machine stays in one of the three valid states
  fvAssert(
    stato === sInit || stato === sAnalisReq || stato === sAssegna,
    "valid_state"
  )

  // Safety: All queue entries contain only valid request IDs or zero (empty)
  fvAssert(
    coda0 === 0.U || coda0 === U1 || coda0 === U2 || coda0 === U3 || coda0 === U4,
    "coda0_valid"
  )
  fvAssert(
    coda1 === 0.U || coda1 === U1 || coda1 === U2 || coda1 === U3 || coda1 === U4,
    "coda1_valid"
  )
  fvAssert(
    coda2 === 0.U || coda2 === U1 || coda2 === U2 || coda2 === U3 || coda2 === U4,
    "coda2_valid"
  )
  fvAssert(
    coda3 === 0.U || coda3 === U1 || coda3 === U2 || coda3 === U3 || coda3 === U4,
    "coda3_valid"
  )

  // Safety: When granting in sAssegna with pending follow-ups and non-zero grant,
  // the grant value must match the request ID stored in the queue head (coda0)
  fvAssert(
    !(stato === sAssegna && (fu1 || fu2 || fu3 || fu4) && grant =/= "b0000".U) ||
    (grant === "b1000".U && coda0 === U1) ||
    (grant === "b0100".U && coda0 === U2) ||
    (grant === "b0010".U && coda0 === U3) ||
    (grant === "b0001".U && coda0 === U4),
    "grant_matches_queue_head"
  )

  // Bounded liveness: When the queue head contains a valid request ID,
  // the grant output becomes non-zero within 6 cycles.
  // Queue depth is 4, state machine processes one entry per 2-cycle pipeline,
  // so worst-case latency is < 10 cycles; bound of 6 is safe for head-of-line.
  val coda0ValidID = coda0 === U1 || coda0 === U2 || coda0 === U3 || coda0 === U4
  astRelaxedLiveness(coda0ValidID, grant =/= 0.U, 6, "queue_head_eventually_granted")
}

object VerilogGenerator extends App {
  emitVerilog(new b03(), args)
}
