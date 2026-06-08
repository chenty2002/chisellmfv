package llmverify
import chisel3._
import chisel3.util._
import chisel3.ltl._
import chisel3.ltl.Sequence._

class reset extends Module {
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

  // === Formal Verification Assertions ===

  // Flag to skip initial cycle for assertions that compare against RegNext
  val initDone = RegInit(false.B)
  initDone := true.B

  // Safety: st1 toggles every cycle (toggle flip-flop invariant)
  // After initDone, st1 must equal the complement of its previous value
  AssertProperty(!initDone || (st1 === ~RegNext(st1)), "st1_toggles_every_cycle")

  // Safety: st2 is sticky (monotonic) — once set to 1, it never decreases
  // st2 >= RegNext(st2) ensures the value is non-decreasing over time
  AssertProperty(st2 >= RegNext(st2), "st2_sticky")

  // Safety: st0 follows io.sel(0) with a one-cycle delay through the register
  // Guard with initDone to skip first cycle where RegNext is uninitialized
  AssertProperty(!initDone || (st0 === RegNext(io.sel(0))), "st0_follows_sel0")
}

object VerilogGenerator extends App {
  emitVerilog(new reset(), args)
}
