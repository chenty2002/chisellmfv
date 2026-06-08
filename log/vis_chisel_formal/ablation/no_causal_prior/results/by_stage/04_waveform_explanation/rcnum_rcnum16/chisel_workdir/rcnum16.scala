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
  
  // Compute n * 3 + 1
  // tmp = {2'b00, numReg} + {1'b0, numReg, 1'b1}
  //     = numReg + (2*numReg + 1) = 3*numReg + 1
  val tmp = (Cat(0.U(2.W), numReg) + Cat(0.U(1.W), numReg, 1.U(1.W)))
  
  // Check overflow: result exceeds 16 bits (tmp(17) | tmp(16))
  val overflow = tmp(17) | tmp(16)
  
  // Next state logic: Collatz step
  // Odd -> 3n+1 (or 0 on overflow), Even -> n/2
  when(numReg(0)) { // Odd number
    numReg := Mux(overflow, 0.U, tmp(15,0))
  }.otherwise { // Even number
    numReg := Cat(0.U(1.W), numReg(15,1)) // Divide by 2
  }
  
  io.numOut := numReg
  
  // ===== FORMAL ASSERTIONS =====
  
  // 1. Overflow detection correctness:
  //    overflow must be true exactly when 3*numReg+1 > 65535 (exceeds 16-bit range)
  val threeNplus1 = 3.U * numReg + 1.U
  fvAssert(overflow === (threeNplus1 > 65535.U), "overflow_detection_correct")
  
  // 2. Even-number transition correctness:
  //    When numReg is even, the computed next value equals numReg right-shifted by 1
  fvAssert(numReg(0) || (Cat(0.U(1.W), numReg(15,1)) === (numReg >> 1.U)(15,0)),
    "even_division_by_two")
  
  // 3. Odd-number no-overflow computation:
  //    When numReg is odd and no overflow, tmp(15,0) must equal (3*numReg+1) lower 16 bits
  fvAssert(!numReg(0) || overflow || (tmp(15,0) === threeNplus1(15,0)),
    "odd_three_n_plus_one_correct")
  
  // 4. Overflow behavior:
  //    When numReg is odd and overflow occurs, the Mux selects 0
  fvAssert(!numReg(0) || !overflow || (Mux(overflow, 0.U, tmp(15,0)) === 0.U),
    "odd_overflow_resets_to_zero")
  
  // 5. Non-zero progress:
  //    For any non-zero numReg, the next value must differ from the current value.
  //    The only fixed point is 0 (even: 0/2 = 0).
  val nextVal = Mux(numReg(0), Mux(overflow, 0.U, tmp(15,0)), Cat(0.U(1.W), numReg(15,1)))
  fvAssert((numReg === 0.U) || (nextVal =/= numReg), "non_zero_always_changes")
}

object VerilogGenerator extends App {
  emitVerilog(new rollercoasterNumbers(), args)
}
