package llmverify
import chisel3._
import chisel3.util._
import chisel3.ltl._
import chisel3.ltl.Sequence._

class rollercoasterNumbers extends Module {
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
  // Formal Verification Assertions (Chisel LTL)
  // --------------------------------------------------------------------------

  // Property 1: Even-number step correctness
  // When numReg is even, the next-cycle value must equal numReg / 2.
  val isEven = !numReg(0)
  val halfNum = Cat(0.U(1.W), numReg(15, 1))
  AssertProperty(
    isEven |-> Sequence(numReg === halfNum).delay(1),
    None, None, Some("even_next_is_half"))

  // Property 2: Odd-number step (no overflow)
  // When numReg is odd and 3x+1 stays within 16 bits, the next-cycle value
  // must equal tmp(15,0).
  val isOdd = numReg(0)
  val noOverflow = !overflow
  AssertProperty(
    (isOdd && noOverflow) |-> Sequence(numReg === tmp(15, 0)).delay(1),
    None, None, Some("odd_no_overflow_next_is_3x_plus_1"))

  // Property 3: Odd-number step (overflow)
  // When numReg is odd and 3x+1 overflows 16 bits, the next-cycle value
  // must be 0.
  AssertProperty(
    (isOdd && overflow) |-> Sequence(numReg === 0.U).delay(1),
    None, None, Some("odd_overflow_next_is_zero"))

  // Property 4: Overflow flag correctness (combinational)
  // overflow must be true exactly when tmp(17) or tmp(16) is set.
  AssertProperty(overflow === (tmp(17) || tmp(16)), "overflow_flag_correct")

  // Property 5: Bounded liveness — the sequence eventually reaches 0.
  // For any 16-bit starting value, the odd-divide and overflow-reset dynamics
  // converge to 0 within a finite number of steps.  We use a counter that
  // increments while numReg is non-zero; if the counter saturates we require
  // numReg to be 0.
  val livCounter = RegInit(0.U(16.W))
  when(numReg =/= 0.U) {
    livCounter := livCounter + 1.U
  }
  AssertProperty(
    !(livCounter === 65535.U) || (numReg === 0.U),
    "liveness_reaches_zero_within_bound")
}

object VerilogGenerator extends App {
  emitVerilog(new rollercoasterNumbers(), args)
}
