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

  // ========== Formal Verification Assertions ==========

  // Capture the previous cycle's numReg value and compute expected next value
  val prev = RegNext(numReg)
  val prev_tmp = Cat(0.U(2.W), prev) + Cat(0.U(1.W), prev, 1.U(1.W))
  val prev_overflow = prev_tmp(17) | prev_tmp(16)
  val expected_next = Mux(prev(0),
    Mux(prev_overflow, 0.U, prev_tmp(15,0)),
    Cat(0.U(1.W), prev(15,1))
  )

  // Assertion 1: State transition correctness
  // After reset deasserts (pipeline filled), numReg must equal the expected
  // value computed from the previous cycle's numReg.
  // Fix: removed ! on RegNext(reset.asBool) — the guard should skip checking
  // when reset was asserted last cycle (pipeline not yet filled).
  AssertProperty(
    RegNext(reset.asBool) | (numReg === expected_next),
    None, None, Some("state_transition_correct")
  )

  // Assertion 2: Output always reflects the internal register
  AssertProperty(io.numOut === numReg, None, None, Some("output_matches_register"))

  // Assertion 3: Bounded liveness — a non-zero value must change regularly.
  // 0 is the only fixed point; any non-zero even halves, any non-zero odd
  // either grows or overflows to 0, so staying unchanged for 20 cycles
  // indicates a stuck-at bug.
  // Fix: added reset guard to avoid false CEX from uninitialized RegNext chain.
  AssertProperty(
    RegNext(reset.asBool) | !(numReg =/= 0.U && numReg === RegNext(numReg) && RegNext(RegNext(numReg)) === RegNext(numReg) && RegNext(RegNext(RegNext(numReg))) === RegNext(numReg)),
    None, None, Some("non_zero_makes_progress")
  )
}

object VerilogGenerator extends App {
  emitVerilog(new rollercoasterNumbers(), args)
}
