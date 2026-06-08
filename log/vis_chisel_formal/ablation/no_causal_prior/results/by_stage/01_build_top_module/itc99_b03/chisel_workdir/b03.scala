package llmverify

import chisel3._
import chisel3.util._

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
}

object VerilogGenerator extends App {
  emitVerilog(new b03(), args)
}