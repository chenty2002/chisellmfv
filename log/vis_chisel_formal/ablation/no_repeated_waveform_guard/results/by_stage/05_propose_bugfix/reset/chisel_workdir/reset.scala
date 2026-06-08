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
  // Use timeSinceReset < 1.U as a cycle-0 guard to suppress checking when
  // prev_st1 may not yet have captured a meaningful toggle (both st1 and
  // prev_st1 were set to 0 during reset, so ~prev_st1 = 1 != st1(0) at cycle 0).
  // timeSinceReset is an external wire from ResetCounter and cannot be optimized
  // away by FIRRTL, unlike a Chisel RegInit guard inside a when block.
  val prev_st1 = RegNext(st1)
  fvAssert(timeSinceReset < 1.U || st1 === ~prev_st1, "st1_toggles_every_cycle")

  // Safety: st2 is sticky - once set to 1, it never decreases
  fvAssert(st2 >= RegNext(st2), "st2_never_decreases")

  // Safety: st0 follows sel(0) with exactly one cycle delay
  // Same cycle-0 guard via timeSinceReset: at cycle 0, st0 holds its reset
  // value (0) while prev_sel0 may have sampled io.sel(0)=1 during the reset
  // posedge, causing a spurious mismatch. The guard suppresses the check
  // until timeSinceReset >= 1, by which time st0 has been updated from io.sel(0).
  val prev_sel0 = RegNext(io.sel(0))
  fvAssert(timeSinceReset < 1.U || st0 === prev_sel0, "st0_follows_sel0_one_cycle_delay")

  // Safety: output encoding matches the three internal registers
  fvAssert(io.st === Cat(st2, st1, st0), "output_matches_internal_state")

  // Bounded liveness: when sel(1) is asserted, st2 must be set within 1 cycle
  astRelaxedLiveness(io.sel(1), st2.asBool, 1, "sel1_causes_st2_within_1_cycle")
}

object VerilogGenerator extends App {
  emitVerilog(new reset(), args)
}
