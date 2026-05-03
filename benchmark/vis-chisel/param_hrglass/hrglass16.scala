package llmverify

import chisel3._
import chisel3.util._

class ghg extends Module {
  val io = IO(new Bundle {
    val turnSmall = Input(Bool())
    val turnLarge = Input(Bool())
    val startTime = Input(UInt(16.W))
    val done = Output(Bool())
    val failed = Output(Bool())
  })
  
  // Constants
  val SMALL = 4.U(3.W)
  val LARGE = 7.U(3.W)
  
  // Registers
  val elapsed = RegInit(io.startTime)
  val small = RegInit(0.U(3.W))
  val large = RegInit(0.U(3.W))
  val ts = RegInit(false.B)
  val tl = RegInit(false.B)
  
  // Combinational outputs
  io.done := elapsed === 0.U
  io.failed := (elapsed === 1.U) || (elapsed === 2.U) || (elapsed === 3.U) ||
               (elapsed === 5.U) || (elapsed === 6.U)
  
  // Register turn signals
  ts := io.turnSmall
  tl := io.turnLarge
  
  // Hourglass logic
  when(small < large) {
    when(small > 0.U) {
      when(elapsed >= small) {
        elapsed := elapsed - small
        large := large - small
        small := 0.U
      }
    }.otherwise {
      when(elapsed >= large) {
        elapsed := elapsed - large
        large := 0.U
      }
    }
  }.otherwise {
    when(large > 0.U) {
      when(elapsed >= large) {
        elapsed := elapsed - large
        small := small - large
        large := 0.U
      }
    }.otherwise {
      when(elapsed >= small) {
        elapsed := elapsed - small
        small := 0.U
      }
    }
  }
  
  // Handle turning hourglasses
  when(ts) {
    small := SMALL - small
  }
  when(tl) {
    large := LARGE - large
  }
}

object VerilogGenerator extends App {
  emitVerilog(new ghg(), args)
}