package llmverify

import chisel3._
import chisel3.util._

// Enum for cup types
class Cup extends Bundle {
  val value = UInt(2.W)
}

object Cup {
  val LARGE = 0.U(2.W)
  val MEDIUM = 1.U(2.W)
  val SMALL = 2.U(3.W)
}

class cups extends Module {
  val io = IO(new Bundle {
    val to = Input(UInt(2.W))
    val from = Input(UInt(2.W))
    val done = Output(Bool())
    
    // Add outputs to preserve internal state for verification
    val Large = Output(UInt(4.W))
    val Medium = Output(UInt(4.W))
    val Small = Output(UInt(4.W))
  })
  
  // Initialize cup amounts
  val Large = RegInit(12.U(4.W))
  val Medium = RegInit(0.U(4.W))
  val Small = RegInit(0.U(4.W))
  
  // Latch inputs on clock edge
  val freg = RegNext(io.from, Cup.LARGE)
  val treg = RegNext(io.to, Cup.MEDIUM) // Default to MEDIUM for non-deterministic choice
  
  // Calculate remaining capacity in each cup
  val resiS = Wire(UInt(4.W))
  val resiM = Wire(UInt(4.W))
  val resiL = Wire(UInt(4.W))
  
  resiS := 5.U - Small
  resiM := 8.U - Medium
  resiL := 12.U - Large
  
  // Done condition: 6 ounces in each of the larger cups
  io.done := (Large === 6.U) && (Medium === 6.U)
  
  // Pouring logic on clock edge
  when(freg === Cup.LARGE) {
    when(treg === Cup.MEDIUM) {
      when(Large >= resiM) {
        Large := Large - resiM
        Medium := 8.U
      }.otherwise {
        Medium := Medium + Large
        Large := 0.U
      }
    }.elsewhen(treg === Cup.SMALL) {
      when(Large >= resiS) {
        Large := Large - resiS
        Small := 5.U
      }.otherwise {
        Small := Small + Large
        Large := 0.U
      }
    }
  }.elsewhen(freg === Cup.MEDIUM) {
    when(treg === Cup.LARGE) {
      when(Medium >= resiL) {
        Medium := Medium - resiL
        Large := 12.U
      }.otherwise {
        Large := Large + Medium
        Medium := 0.U
      }
    }.elsewhen(treg === Cup.SMALL) {
      when(Medium >= resiS) {
        Medium := Medium - resiS
        Small := 5.U
      }.otherwise {
        Small := Small + Medium
        Medium := 0.U
      }
    }
  }.elsewhen(freg === Cup.SMALL) {
    when(treg === Cup.LARGE) {
      when(Small >= resiL) {
        Small := Small - resiL
        Large := 12.U
      }.otherwise {
        Large := Large + Small
        Small := 0.U
      }
    }.elsewhen(treg === Cup.MEDIUM) {
      when(Small >= resiM) {
        Small := Small - resiM
        Medium := 8.U
      }.otherwise {
        Medium := Medium + Small
        Small := 0.U
      }
    }
  }
  
  // Connect internal state to outputs for verification
  io.Large := Large
  io.Medium := Medium
  io.Small := Small
}

object VerilogGenerator extends App {
  emitVerilog(new cups(), args)
}