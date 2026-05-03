package llmverify

import chisel3._
import chisel3.util._

/**
 * This model can be used to verify the following claim.
 *
 * We are given two hourglasses: one measures 4 minutes, and the other
 * measures 7 minutes.  The only intervals we cannot measure are:
 * 1, 2, 3, 5, and 6 minutes.
 *
 * We model this puzzle as follows.  The clock ticks mark the occurrence of
 * events.  An event occurs when the hourglass with the least amount of sand
 * in its upper half terminates.  If we only turn an hourglass in response to
 * an event, then we can keep track of how sand is divided in both hourglasses.
 *
 * Author: Fabio Somenzi <Fabio@Colorado.EDU>
 */
class ghg extends Module {
  val io = IO(new Bundle {
    val turnSmall = Input(Bool())
    val turnLarge = Input(Bool())
    val startTime = Input(UInt(16.W))
    val done = Output(Bool())
    val failed = Output(Bool())
    
    // Debug outputs to preserve internal signals
    val debugElapsed = Output(UInt(16.W))
    val debugSmall = Output(UInt(3.W))
    val debugLarge = Output(UInt(3.W))
  })
  
  // Constants
  val MSB = 15
  val SMALL = 4.U(3.W)
  val LARGE = 7.U(3.W)
  
  // Registers
  val elapsed = RegInit(io.startTime)
  val Small = RegInit(0.U(3.W))
  val Large = RegInit(0.U(3.W))
  val ts = RegInit(false.B)
  val tl = RegInit(false.B)
  
  // Output assignments
  io.done := elapsed === 0.U
  io.failed := (elapsed === 1.U) || (elapsed === 2.U) || (elapsed === 3.U) ||
              (elapsed === 5.U) || (elapsed === 6.U)
  
  // Debug outputs
  io.debugElapsed := elapsed
  io.debugSmall := Small
  io.debugLarge := Large
  
  // Register ts and tl from inputs
  ts := io.turnSmall
  tl := io.turnLarge
  
  // Main logic for hourglass simulation
  val nextElapsed = Wire(UInt(16.W))
  val nextSmall = Wire(UInt(3.W))
  val nextLarge = Wire(UInt(3.W))
  
  // Default values
  nextElapsed := elapsed
  nextSmall := Small
  nextLarge := Large
  
  // Hourglass simulation logic
  when(Small < Large) {
    when(Small > 0.U) {
      when(elapsed >= Small) {
        nextElapsed := elapsed - Small
        nextLarge := Large - Small
        nextSmall := 0.U
      }
    }.otherwise {
      when(elapsed >= Large) {
        nextElapsed := elapsed - Large
        nextLarge := 0.U
      }
    }
  }.otherwise {
    when(Large > 0.U) {
      when(elapsed >= Large) {
        nextElapsed := elapsed - Large
        nextSmall := Small - Large
        nextLarge := 0.U
      }
    }.otherwise {
      when(elapsed >= Small) {
        nextElapsed := elapsed - Small
        nextSmall := 0.U
      }
    }
  }
  
  // Handle turning hourglasses
  when(ts) {
    nextSmall := SMALL - Small
  }
  when(tl) {
    nextLarge := LARGE - Large
  }
  
  // Update registers
  elapsed := nextElapsed
  Small := nextSmall
  Large := nextLarge
}

object VerilogGenerator extends App {
  emitVerilog(new ghg(), args)
}