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

  // ============ FORMAL ASSERTIONS ============

  // Capture the previous register value to verify state transitions.
  // At cycle t+1, prevNumReg holds numReg(t), allowing us to check that the
  // new numReg correctly follows the transition rules from prevNumReg.
  val prevNumReg = RegNext(numReg)

  // Recompute tmp and overflow *for the previous cycle's value* so we can
  // check what transition should have occurred.
  val prevTmp   = (Cat(0.U(2.W), prevNumReg) + Cat(0.U(1.W), prevNumReg, 1.U(1.W)))
  val prevOver  = prevTmp(17) | prevTmp(16)

  // Past-valid indicator: at cycle 0, prevNumReg and numReg have independent
  // random initial values so no transition relationship holds.  After the first
  // posedge, pastValid is asserted and the transition-check assertions become
  // meaningful.
  val pastValid = RegInit(0.U(1.W))
  pastValid := 1.U(1.W)

  // Safety 1: When prevNumReg was ODD with NO overflow, numReg must equal
  //           the lower 16 bits of prevTmp (i.e. 3n+1).
  fvAssert(
    !pastValid || !(prevNumReg(0) && !prevOver) || (numReg === prevTmp(15,0)),
    "odd_no_overflow_transition"
  )

  // Safety 2: When prevNumReg was ODD WITH overflow, numReg must be 0.
  fvAssert(
    !pastValid || !(prevNumReg(0) && prevOver) || (numReg === 0.U),
    "odd_overflow_resets_to_zero"
  )

  // Safety 3: When prevNumReg was EVEN, numReg must equal prevNumReg / 2
  //           (right-shift by one with zero-extension).
  fvAssert(
    !pastValid || prevNumReg(0) || (numReg === Cat(0.U(1.W), prevNumReg(15,1))),
    "even_division_transition"
  )

  // Bounded liveness: once numReg is non-zero, it must eventually reach 0
  // within a bounded number of steps.  For a 16-bit register the state space
  // diameter is < 2^16, so we use a conservative bound of 100000 cycles.
  astRelaxedLiveness(numReg =/= 0.U, numReg === 0.U, 100000, "non_zero_eventually_zero")
}

object VerilogGenerator extends App {
  emitVerilog(new rollercoasterNumbers(), args)
}
