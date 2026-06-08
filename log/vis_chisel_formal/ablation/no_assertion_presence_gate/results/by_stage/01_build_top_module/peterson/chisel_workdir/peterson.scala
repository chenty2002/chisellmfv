package llmverify

import chisel3._
import chisel3.util._

// Enum for location states in Peterson's algorithm
object Loc extends ChiselEnum {
  val L0, L1, L2, L3, L4, L5 = Value
}

class peterson extends Module {
  val io = IO(new Bundle {
    val select = Input(Bool())
    val pause = Input(Bool())
    
    // Expose internal state for verification
    val interested = Output(Vec(2, Bool()))
    val turn = Output(Bool())
    val self = Output(Bool())
    val pc = Output(Vec(2, Loc()))
  })
  
  // Internal registers
  val interested = RegInit(VecInit(Seq(false.B, false.B)))
  val turn = RegInit(0.U(1.W))
  val self = RegInit(0.U(1.W))
  val pc = RegInit(VecInit(Loc.L0, Loc.L0))
  
  // Connect outputs to internal state
  io.interested := interested
  io.turn := turn
  io.self := self
  io.pc := pc
  
  // Update self register based on select input
  self := io.select
  
  // State machine logic - implicitly clocked
  val selfIdx = self
  val otherIdx = ~self
  
  switch(pc(selfIdx)) {
    is(Loc.L0) {
      when(!io.pause) {
        pc(selfIdx) := Loc.L1
      }
    }
    is(Loc.L1) {
      interested(selfIdx) := true.B
      pc(selfIdx) := Loc.L2
    }
    is(Loc.L2) {
      turn := ~self
      pc(selfIdx) := Loc.L3
    }
    is(Loc.L3) {
      when(!interested(otherIdx) || (turn === self)) {
        pc(selfIdx) := Loc.L4
      }
    }
    is(Loc.L4) {
      when(!io.pause) {
        pc(selfIdx) := Loc.L5
      }
    }
    is(Loc.L5) {
      interested(selfIdx) := false.B
      pc(selfIdx) := Loc.L0
    }
  }
}

object VerilogGenerator extends App {
  emitVerilog(new peterson(), args)
}