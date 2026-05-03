package llmverify
import chisel3._
import chisel3.util._

class relinit extends Module {
  val io = IO(new Bundle {
    val a_out = Output(UInt(1.W))
    val b_out = Output(UInt(1.W))
  })
  
  // Registers a and b
  // Note: Original Verilog used non-deterministic initialization with $ND
  // Chisel doesn't support non-deterministic initialization, so we use RegInit
  // The original logic would remap invalid initial states (a != b) to (0,0)
  val a = RegInit(0.U(1.W))
  val b = RegInit(0.U(1.W))
  
  // Function to check if state is valid (a == b)
  def valid(aVal: UInt, bVal: UInt): Bool = {
    aVal === bVal
  }
  
  // Swap values on positive clock edge
  // In Verilog, both always blocks execute simultaneously
  // We need to capture old values before swapping to avoid race conditions
  val a_old = RegNext(a)
  val b_old = RegNext(b)
  
  a := b_old
  b := a_old
  
  // Connect outputs to preserve the design
  io.a_out := a
  io.b_out := b
}

object VerilogGenerator extends App {
  emitVerilog(new relinit(), args)
}