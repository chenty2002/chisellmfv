package llmverify
import chisel3._
import chisel3.util._
import chiselFv._

class rollercoasterNumbers extends Module with Formal {
  val io = IO(new Bundle {
    val numOut = Output(UInt(16.W))
  })
  
  // Register to store the current number
  // Use Reg without initialization to simulate non-deterministic initial values
  val numReg = Reg(UInt(16.W))
  
  // Compute n[i] * 3 + 1
  // tmp = {2'b0,numOut} + {1'b0,numOut,1'b1}
  val tmp = (Cat(0.U(2.W), numReg) + Cat(0.U(1.W), numReg, 1.U(1.W)))
  
  // Check overflow: tmp[15+2] | tmp[15+1]
  val overflow = tmp(17) | tmp(16)
  
  // Next state logic
  when(numReg(0)) { // Odd number
    numReg := Mux(overflow, 0.U, tmp(15,0))
  }.otherwise { // Even number
    numReg := Cat(0.U(1.W), numReg(15,1)) // Divide by 2
  }
  
  io.numOut := numReg

  // --------------------------------------------------------------------------
  // Formal Verification Assertions
  // --------------------------------------------------------------------------

  // Register to capture the previous cycle's value for cross-cycle checks
  val prevNumReg = RegNext(numReg)

  // Recompute tmp and overflow based on the previous value for assertions
  // (prevNumReg is used to form the expected next value)
  val prevTmp = (Cat(0.U(2.W), prevNumReg) + Cat(0.U(1.W), prevNumReg, 1.U(1.W)))
  val prevOverflow = prevTmp(17) | prevTmp(16)

  // Safety 1: Even case – when prevNumReg is even, numReg must equal prevNumReg / 2
  // (right-shift by 1 implements division by 2 for unsigned integers)
  fvAssert(!(prevNumReg(0) === 0.U) || numReg === (prevNumReg >> 1), "even_num_halved")

  // Safety 2: Odd case without overflow – when prevNumReg is odd and the
  // 3*prevNumReg+1 computation does NOT overflow 16 bits, numReg must equal
  // the low 16 bits of that computation (prevNumReg * 3 + 1).
  fvAssert(!(prevNumReg(0) === 1.U && !prevOverflow) || numReg === prevTmp(15,0), "odd_num_3x_plus_1")

  // Safety 3: Odd case with overflow – when prevNumReg is odd and the
  // 3*prevNumReg+1 computation overflows 16 bits, numReg must be reset to 0.
  fvAssert(!(prevNumReg(0) === 1.U && prevOverflow) || numReg === 0.U, "odd_num_overflow_to_zero")

  // Safety 4: Zero is an absorbing (fixed-point) state – once prevNumReg is 0,
  // the current numReg must also be 0.  The next-state logic ensures 0 >> 1 = 0,
  // so this invariant holds.
  fvAssert(!(prevNumReg === 0.U) || numReg === 0.U, "zero_is_absorbing")
}

object VerilogGenerator extends App {
  emitVerilog(new rollercoasterNumbers(), args)
}
