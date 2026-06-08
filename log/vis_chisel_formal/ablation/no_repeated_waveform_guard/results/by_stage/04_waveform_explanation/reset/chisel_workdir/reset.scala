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

  // ===== Formal Verification Assertions =====

  // Safety: st1 toggles every cycle (st1(k) = ~st1(k-1))
  fvAssert(st1 === ~RegNext(st1), "st1_toggles_every_cycle")

  // Safety: st2 is sticky - once set to 1, it never decreases
  fvAssert(st2 >= RegNext(st2), "st2_never_decreases")

  // Safety: st0 follows sel(0) with exactly one cycle delay
  fvAssert(st0 === RegNext(io.sel(0)), "st0_follows_sel0_one_cycle_delay")

  // Safety: output encoding matches the three internal registers
  fvAssert(io.st === Cat(st2, st1, st0), "output_matches_internal_state")

  // Bounded liveness: when sel(1) is asserted, st2 must be set within 1 cycle
  astRelaxedLiveness(io.sel(1), st2.asBool, 1, "sel1_causes_st2_within_1_cycle")
}

object VerilogGenerator extends App {
  emitVerilog(new reset(), args)
}
