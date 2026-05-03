package llmverify
import chisel3._
import chisel3.util._

class rollercoasterNumbers extends Module {
  val io = IO(new Bundle {
    val numOut = Output(UInt(25.W))
  })
  
  // Register to hold the current number
  // Initialize with 0 instead of non-deterministic values
  val numReg = RegInit(0.U(25.W))
  
  io.numOut := numReg
  
  // Compute n[i] * 3 + 1 using the same method as Verilog
  // tmp = {2'b0,numOut} + {1'b0,numOut,1'b1}
  val tmp = (Cat(0.U(2.W), numReg) + Cat(0.U(1.W), numReg, 1.U(1.W)))
  
  // State transition logic
  when (numReg(0)) { // If odd (LSB is 1)
    // Check overflow: if bits 26 or 25 are set, set to 0
    when (tmp(26) | tmp(25)) {
      numReg := 0.U
    } .otherwise {
      numReg := tmp(24, 0)
    }
  } .otherwise { // If even (LSB is 0)
    // Divide by 2: right shift by 1 and pad with 0
    numReg := Cat(0.U(1.W), numReg(24, 1))
  }
}

object VerilogGenerator extends App {
  emitVerilog(new rollercoasterNumbers(), args)
}