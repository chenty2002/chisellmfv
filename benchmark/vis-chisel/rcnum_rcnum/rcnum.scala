package llmverify

import chisel3._
import chisel3.util._

/**
 * This Chisel module describes a simple finite state machine for
 * the computation of the so-called rollercoaster numbers.
 * Given an initial number n[0] > 0, the rollercoaster number
 * sequence (n[i]) starting at n[0] is given by these rules:
 *   if n[i] is even, n[i+1] = n[i]/2;
 *   if n[i] is odd,  n[i+1] = n[i]*3+1.
 * The sequences for most (small) numbers eventually reach the
 * cycle (4,2,1).
 * This finite state machine has a state register storing n[i].
 * Since the register is finite, only a few positive integers can
 * be represented. Therefore 0 is used as trap state to indicate
 * overflow.
 *
 * This description intentionally avoids the use of '*' and '/'.
 *
 * Author: Fabio Somenzi <Fabio@Colorado.EDU>
 * Converted to Chisel
 */
class rollercoasterNumbers extends Module {
  val io = IO(new Bundle {
    val numOut = Output(UInt(25.W))
  })

  // State register for the current number
  val numReg = RegInit(0.U(25.W))
  
  // Initialize with non-deterministic values (using DontCare in Chisel)
  // In actual hardware, this would be initialized to 0 or have a reset
  numReg := DontCare
  
  // Compute n[i] * 3 + 1.
  // tmp = {2'b0,numOut} + {1'b0,numOut,1'b1}
  // This is equivalent to: (numOut << 2) + ((numOut << 1) | 1)
  // Which equals: 4*numOut + 2*numOut + 1 = 3*numOut + 1
  val tmp = (numReg << 2) + ((numReg << 1) | 1.U)
  
  // Sequential logic on clock edge (implicit in Chisel)
  when(numReg(0)) {
    // Odd number: compute 3*n + 1
    // Check overflow: if tmp[26] or tmp[25] is set, overflow occurred
    when(tmp(26) || tmp(25)) {
      numReg := 0.U  // Overflow trap state
    }.otherwise {
      numReg := tmp(24, 0)  // Take lower 25 bits
    }
  }.otherwise {
    // Even number: divide by 2 (right shift)
    // numOut = {1'b0,numOut[24:1]}
    numReg := numReg >> 1
  }
  
  // Output the current number
  io.numOut := numReg
}

object VerilogGenerator extends App {
  emitVerilog(new rollercoasterNumbers(), args)
}