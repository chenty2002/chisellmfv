package llmverify

import chisel3._
import chisel3.util._

/**
  * Chisel implementation of the round-robin arbiter described in
  * the CHARME99 paper by Katz, Grumberg, and Geist.
  * 
  * Author: Fabio Somenzi <Fabio@Colorado.EDU>
  */
class rrobin extends Module {
  val io = IO(new Bundle {
    val ir0 = Input(Bool())
    val ir1 = Input(Bool())
    val ack0 = Output(Bool())
    val ack1 = Output(Bool())
  })
  
  // Internal registers
  val req0 = RegInit(io.ir0)  // nondeterministic initial requests
  val req1 = RegInit(io.ir1)
  val ack0 = RegInit(false.B)
  val ack1 = RegInit(false.B)
  val robin = RegInit(false.B)
  
  // Update ack0 logic
  when (!req0) {
    ack0 := false.B  // no request -> no ack
  }.elsewhen (!req1) {
    ack0 := true.B   // a single request
  }.elsewhen (!ack0 && !ack1) {
    ack0 := !robin    // simultaneous request assertions
  }.otherwise {
    ack0 := !ack0     // both requesting: toggle ack
  }
  
  // Update ack1 logic
  when (!req1) {
    ack1 := false.B  // no request -> no ack
  }.elsewhen (!req0) {
    ack1 := true.B   // a single request
  }.elsewhen (!ack0 && !ack1) {
    ack1 := robin     // simultaneous request assertions
  }.otherwise {
    ack1 := !ack1     // both requesting: toggle ack
  }
  
  // Update robin logic
  when (req0 && req1 && !ack0 && !ack1) {
    robin := !robin   // simultaneous request assertions
  }
  
  // Latched inputs
  req0 := io.ir0
  req1 := io.ir1
  
  // Connect outputs
  io.ack0 := ack0
  io.ack1 := ack1
}

object VerilogGenerator extends App {
  emitVerilog(new rrobin(), args)
}