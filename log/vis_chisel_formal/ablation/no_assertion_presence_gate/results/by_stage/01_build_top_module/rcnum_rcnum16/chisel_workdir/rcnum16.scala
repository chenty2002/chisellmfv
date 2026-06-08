package llmverify
import chisel3._
import chisel3.util._

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
}

object VerilogGenerator extends App {
  emitVerilog(new rollercoasterNumbers(), args)
}