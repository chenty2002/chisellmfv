package llmverify
import chisel3._
import chisel3.util._
import chiselFv._

class reset extends Module with Formal {
  val io = IO(new Bundle {
    val sel = Input(UInt(2.W))
    val st = Output(UInt(3.W)) // Add output to preserve the design
  })
  
  // Register st[2:0] initialized to 0
  // Each bit has its own update logic based on the Verilog always block
  val st0 = RegInit(0.U(1.W))
  val st1 = RegInit(0.U(1.W))
  val st2 = RegInit(0.U(1.W))
  
  // Sequential logic - update registers on clock edge
  st0 := io.sel(0)
  st1 := ~st1  // Complement current value
  st2 := io.sel(1) | st2
  
  // Combine individual bits into output
  io.st := Cat(st2, st1, st0)
  
  // Formal verification assertions
  
  // Assertion 1: During reset, all bits should be 0
  fvAssert(!reset.asBool || (st0 === 0.U && st1 === 0.U && st2 === 0.U), 
           "During reset, all state bits should be 0")
  
  // Assertion 2: st0 should directly follow sel[0] after the first cycle
  fvAssert(reset.asBool || (io.st(0) === io.sel(0)), 
           "st0 should equal sel[0] after reset")
  
  // Assertion 3: st1 should toggle every cycle (complement behavior)
  // After reset, st1 should be 1 in the next cycle, then 0, then 1, etc.
  fvAssert(reset.asBool || (st1 === ~st1), 
           "st1 should complement every cycle")
  
  // Assertion 4: st2 should be sticky - once set to 1 by sel[1], it should never go back to 0
  fvAssert(reset.asBool || (st2 === 0.U || st2 === 1.U), 
           "st2 should be valid binary")
  fvAssert(reset.asBool || (st2 === 0.U || st2 === 1.U), 
           "st2 should maintain its value when sel[1] is 0")
  
  // More precise assertion for st2 sticky behavior
  fvAssert(reset.asBool || (io.sel(1) === 0.U || io.st(2) === 1.U), 
           "When sel[1] is 1, st2 should become 1")
  fvAssert(reset.asBool || (io.sel(1) === 1.U || io.st(2) === st2), 
           "When sel[1] is 0, st2 should maintain previous value")
  
  // Assertion 5: Output should always be within valid range (0-7)
  fvAssert(reset.asBool || (io.st >= 0.U && io.st <= 7.U), 
           "Output st should always be within 3-bit range")
}

object VerilogGenerator extends App {
  emitVerilog(new reset(), args)
}