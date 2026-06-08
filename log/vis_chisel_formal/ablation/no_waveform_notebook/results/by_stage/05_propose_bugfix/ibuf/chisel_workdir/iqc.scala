package llmverify

import chisel3._
import chisel3.util._
import chisel3.ltl._
import chisel3.ltl.Sequence._

class iqc extends Module {
  val io = IO(new Bundle {
    val iqLoads = Input(UInt(2.W))
    val exeReady = Input(UInt(2.W))
    val opsReady = Input(UInt(3.W))
    val flush = Input(UInt(3.W))
    val load0 = Output(UInt(2.W))
    val load1 = Output(UInt(2.W))
    val load2 = Output(UInt(2.W))
    val issue0 = Output(UInt(3.W))
    val issue1 = Output(UInt(3.W))
    val valid = Output(UInt(3.W))
  })

  // Internal registers
  val valid = RegInit(0.U(3.W))
  val qAge = RegInit(0.U(3.W))

  // Connect valid output
  io.valid := valid

  // Loading instructions to queue slots:
  // The lowest-indexed free entries are preferred.
  // Dispatch port 0 has precedence over dispatch port 1.
  val load0_0 = ~valid(0) & io.iqLoads(0)
  val load0_1 = ~valid(0) & ~io.iqLoads(0) & io.iqLoads(1)
  io.load0 := Cat(load0_1, load0_0)

  val load1_0 = ~valid(1) & valid(0) & io.iqLoads(0)
  val load1_1 = ~valid(1) & io.iqLoads(1) & ~(load0_1 | load1_0)
  io.load1 := Cat(load1_1, load1_0)

  val load2_0 = ~valid(2) & valid(1) & valid(0) & io.iqLoads(0)
  val load2_1 = ~valid(2) & io.iqLoads(1) & ~(load2_0 | load0_1 | load1_1)
  io.load2 := Cat(load2_1, load2_0)

  // Issuing instructions to the execution units.
  // Execution unit 0 has precedence over execution unit 1.
  // Older instructions are issued first.
  val issue0_0 = io.exeReady(0) & io.opsReady(0) & valid(0) &
    (qAge(0) | ~io.opsReady(1)) & (qAge(1) | ~io.opsReady(2))
  val issue0_1 = io.exeReady(0) & io.opsReady(1) & valid(1) &
    (~qAge(0) | ~io.opsReady(0)) & (qAge(2) | ~io.opsReady(2))
  val issue0_2 = io.exeReady(0) & io.opsReady(2) & valid(2) &
    (~qAge(1) | ~io.opsReady(0)) & (~qAge(2) | ~io.opsReady(1))
  io.issue0 := Cat(issue0_2, issue0_1, issue0_0)

  val issue1_0 = io.exeReady(1) & io.opsReady(0) & valid(0) &
    (qAge(0) | ~io.opsReady(1) | issue0_1) &
    (qAge(1) | ~io.opsReady(2) | issue0_2) & ~issue0_0
  val issue1_1 = io.exeReady(1) & io.opsReady(1) & valid(1) &
    (~qAge(0) | ~io.opsReady(0) | issue0_0) &
    (qAge(2) | ~io.opsReady(2) | issue0_2) & ~issue0_1
  val issue1_2 = io.exeReady(1) & io.opsReady(2) & valid(2) &
    (~qAge(1) | ~io.opsReady(0) | issue0_0) &
    (~qAge(2) | ~io.opsReady(1) | issue0_1) & ~issue0_2
  io.issue1 := Cat(issue1_2, issue1_1, issue1_0)

  // Next values of the valid bits
  val nv0 = ~io.flush(0) & (valid(0) & ~(issue0_0 | issue1_0) | io.load0.orR)
  val nv1 = ~io.flush(1) & (valid(1) & ~(issue0_1 | issue1_1) | io.load1.orR)
  val nv2 = ~io.flush(2) & (valid(2) & ~(issue0_2 | issue1_2) | io.load2.orR)

  // Sequential logic - reconstruct entire register values
  valid := Cat(nv2, nv1, nv0)
  qAge := Cat(
    nv1 & (~nv2 | qAge(2) | ~valid(2)),  // qAge[2]
    nv0 & (~nv2 | qAge(1) | ~valid(2)),  // qAge[1]
    nv0 & (~nv1 | qAge(0) | ~valid(1))   // qAge[0]
  )

  // =====================================================================
  // FORMAL ASSERTIONS
  // =====================================================================

  // -----------------------------------------------------------------------
  // 1. MUTEX on issue0 — at most one slot issues to execution unit 0 / cycle
  // -----------------------------------------------------------------------
  AssertProperty(!(issue0_0 && issue0_1), "issue0_mutex_01")
  AssertProperty(!(issue0_0 && issue0_2), "issue0_mutex_02")
  AssertProperty(!(issue0_1 && issue0_2), "issue0_mutex_12")

  // -----------------------------------------------------------------------
  // 2. MUTEX on issue1 — at most one slot issues to execution unit 1 / cycle
  // -----------------------------------------------------------------------
  AssertProperty(!(issue1_0 && issue1_1), "issue1_mutex_01")
  AssertProperty(!(issue1_0 && issue1_2), "issue1_mutex_02")
  AssertProperty(!(issue1_1 && issue1_2), "issue1_mutex_12")

  // -----------------------------------------------------------------------
  // 3. No dual-issue — a slot cannot issue to both exec units in the same cycle
  // -----------------------------------------------------------------------
  AssertProperty(!(issue0_0 && issue1_0), "slot0_dual_issue")
  AssertProperty(!(issue0_1 && issue1_1), "slot1_dual_issue")
  AssertProperty(!(issue0_2 && issue1_2), "slot2_dual_issue")

  // -----------------------------------------------------------------------
  // 4. Issue requires valid — only occupied slots can issue
  // -----------------------------------------------------------------------
  AssertProperty(!issue0_0 || valid(0), "issue0_requires_valid0")
  AssertProperty(!issue0_1 || valid(1), "issue0_requires_valid1")
  AssertProperty(!issue0_2 || valid(2), "issue0_requires_valid2")
  AssertProperty(!issue1_0 || valid(0), "issue1_requires_valid0")
  AssertProperty(!issue1_1 || valid(1), "issue1_requires_valid1")
  AssertProperty(!issue1_2 || valid(2), "issue1_requires_valid2")

  // -----------------------------------------------------------------------
  // 5. Issue requires opsReady — instructions must have ready operands
  // -----------------------------------------------------------------------
  AssertProperty(!issue0_0 || io.opsReady(0), "issue0_requires_opsReady0")
  AssertProperty(!issue0_1 || io.opsReady(1), "issue0_requires_opsReady1")
  AssertProperty(!issue0_2 || io.opsReady(2), "issue0_requires_opsReady2")
  AssertProperty(!issue1_0 || io.opsReady(0), "issue1_requires_opsReady0")
  AssertProperty(!issue1_1 || io.opsReady(1), "issue1_requires_opsReady1")
  AssertProperty(!issue1_2 || io.opsReady(2), "issue1_requires_opsReady2")

  // -----------------------------------------------------------------------
  // 6. Issue requires exeReady — execution unit must be ready
  // -----------------------------------------------------------------------
  AssertProperty(!issue0_0 || io.exeReady(0), "issue0_requires_exeReady")
  AssertProperty(!issue0_1 || io.exeReady(0), "issue0_requires_exeReady")
  AssertProperty(!issue0_2 || io.exeReady(0), "issue0_requires_exeReady")
  AssertProperty(!issue1_0 || io.exeReady(1), "issue1_requires_exeReady")
  AssertProperty(!issue1_1 || io.exeReady(1), "issue1_requires_exeReady")
  AssertProperty(!issue1_2 || io.exeReady(1), "issue1_requires_exeReady")

  // -----------------------------------------------------------------------
  // 7. Load consistency — load signals imply proper slot/port conditions
  // -----------------------------------------------------------------------
  // load0_0: slot 0 is free AND disp port 0 is valid
  AssertProperty(!load0_0 || (!valid(0) && io.iqLoads(0)), "load0_0_consistent")
  // load0_1: slot 0 is free AND disp port 0 is NOT valid AND disp port 1 is valid
  AssertProperty(!load0_1 || (!valid(0) && !io.iqLoads(0) && io.iqLoads(1)), "load0_1_consistent")
  // load1_0: slot 1 is free AND slot 0 is occupied AND disp port 0 is valid
  AssertProperty(!load1_0 || (!valid(1) && valid(0) && io.iqLoads(0)), "load1_0_consistent")
  // load2_0: slot 2 is free AND slots 0,1 are occupied AND disp port 0 is valid
  AssertProperty(!load2_0 || (!valid(2) && valid(1) && valid(0) && io.iqLoads(0)), "load2_0_consistent")

  // -----------------------------------------------------------------------
  // 8. Flush correctness — flush(i) forces next valid bit to 0
  // -----------------------------------------------------------------------
  AssertProperty(!io.flush(0) || nv0 === 0.U, "flush_clears_valid0")
  AssertProperty(!io.flush(1) || nv1 === 0.U, "flush_clears_valid1")
  AssertProperty(!io.flush(2) || nv2 === 0.U, "flush_clears_valid2")

  // -----------------------------------------------------------------------
  // 9. Bounded liveness — a valid, ready instruction eventually issues
  // -----------------------------------------------------------------------
  // A slot can only make forward progress when at least one execution unit
  // is ready (exeReady is high). When both exeReady bits are 0, all issue
  // signals are blocked, so we do not count those cycles as stalled.
  val anyExeReady = io.exeReady(0) || io.exeReady(1)

  // Slot 0: count consecutive cycles where ready but not issuing;
  // if it exceeds the bound, the age/priority logic has stalled.
  val issueTimer0 = RegInit(0.U(4.W))
  when(io.flush(0)) {
    issueTimer0 := 0.U
  } .elsewhen(valid(0) && io.opsReady(0) && anyExeReady && !(issue0_0 || issue1_0)) {
    issueTimer0 := issueTimer0 + 1.U
  } .otherwise {
    issueTimer0 := 0.U
  }
  AssertProperty(issueTimer0 < 10.U, "slot0_forward_progress")

  // Slot 1
  val issueTimer1 = RegInit(0.U(4.W))
  when(io.flush(1)) {
    issueTimer1 := 0.U
  } .elsewhen(valid(1) && io.opsReady(1) && anyExeReady && !(issue0_1 || issue1_1)) {
    issueTimer1 := issueTimer1 + 1.U
  } .otherwise {
    issueTimer1 := 0.U
  }
  AssertProperty(issueTimer1 < 10.U, "slot1_forward_progress")

  // Slot 2
  val issueTimer2 = RegInit(0.U(4.W))
  when(io.flush(2)) {
    issueTimer2 := 0.U
  } .elsewhen(valid(2) && io.opsReady(2) && anyExeReady && !(issue0_2 || issue1_2)) {
    issueTimer2 := issueTimer2 + 1.U
  } .otherwise {
    issueTimer2 := 0.U
  }
  AssertProperty(issueTimer2 < 10.U, "slot2_forward_progress")

  // -----------------------------------------------------------------------
  // 10. Valid-load consistency — a slot that is loaded becomes valid next cycle
  // -----------------------------------------------------------------------
  // When load0.orR is true (slot 0 loaded) and no flush, nv0 must be 1
  AssertProperty(!(io.load0.orR && !io.flush(0)) || nv0 === 1.U, "load_makes_valid0")
  AssertProperty(!(io.load1.orR && !io.flush(1)) || nv1 === 1.U, "load_makes_valid1")
  AssertProperty(!(io.load2.orR && !io.flush(2)) || nv2 === 1.U, "load_makes_valid2")
}

object VerilogGenerator extends App {
  emitVerilog(new iqc(), args)
}
