package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

class iqc extends Module with Formal {
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

  // ========== FORMAL ASSERTIONS ==========

  // --- Fairness constraint: each execution unit eventually becomes ready ---
  // A single exeReady.orR fairness is insufficient because when exec unit 0
  // is ready but consumed by an older entry, the younger entry can starve
  // indefinitely. Each execution unit must be ready independently at least
  // once every 7 cycles.
  val exeReady0Timer = RegInit(0.U(3.W))
  when(io.exeReady(0)) {
    exeReady0Timer := 0.U
  } .otherwise {
    exeReady0Timer := exeReady0Timer + 1.U
  }
  assume(exeReady0Timer < 7.U, "exeReady0_fairness")

  val exeReady1Timer = RegInit(0.U(3.W))
  when(io.exeReady(1)) {
    exeReady1Timer := 0.U
  } .otherwise {
    exeReady1Timer := exeReady1Timer + 1.U
  }
  assume(exeReady1Timer < 7.U, "exeReady1_fairness")

  // --- Safety: Issue mutex ---
  // At most one entry can issue to execution unit 0 per cycle
  assertMutex(Seq(issue0_0, issue0_1, issue0_2), "issue0_mutex")

  // At most one entry can issue to execution unit 1 per cycle
  assertMutex(Seq(issue1_0, issue1_1, issue1_2), "issue1_mutex")

  // --- Safety: No double-issue of the same entry ---
  // An instruction cannot issue to both execution units in the same cycle
  assertMutex(Seq(issue0_0, issue1_0), "entry0_no_double_issue")
  assertMutex(Seq(issue0_1, issue1_1), "entry1_no_double_issue")
  assertMutex(Seq(issue0_2, issue1_2), "entry2_no_double_issue")

  // --- Safety: Only valid entries can issue ---
  // An instruction that issues must have its valid bit set
  assertImplies(issue0_0 || issue1_0, valid(0), "entry0_issue_only_when_valid")
  assertImplies(issue0_1 || issue1_1, valid(1), "entry1_issue_only_when_valid")
  assertImplies(issue0_2 || issue1_2, valid(2), "entry2_issue_only_when_valid")

  // --- Safety: Load signals are one-hot0 (at most one dispatch port per entry) ---
  assertOneHot0(io.load0, "load0_one_hot0")
  assertOneHot0(io.load1, "load1_one_hot0")
  assertOneHot0(io.load2, "load2_one_hot0")

  // --- Safety: Load only targets invalid entries ---
  // A load should never target an entry that is already valid
  fvAssert(!io.load0.orR || !valid(0), "load0_only_when_invalid")
  fvAssert(!io.load1.orR || !valid(1), "load1_only_when_invalid")
  fvAssert(!io.load2.orR || !valid(2), "load2_only_when_invalid")

  // --- Liveness/Progress: Ready entries eventually issue ---
  // If a valid entry has its operands ready and an execution unit is ready,
  // then the entry should issue within a bounded number of cycles.
  // The bound of 8 cycles accommodates worst-case arbitration among 3 entries
  // and pipeline delays.
  astRelaxedLiveness(
    valid(0) && io.opsReady(0),
    issue0_0 || issue1_0 || !valid(0) || !io.opsReady(0),
    8,
    "entry0_progress"
  )
  astRelaxedLiveness(
    valid(1) && io.opsReady(1),
    issue0_1 || issue1_1 || !valid(1) || !io.opsReady(1),
    8,
    "entry1_progress"
  )
  astRelaxedLiveness(
    valid(2) && io.opsReady(2),
    issue0_2 || issue1_2 || !valid(2) || !io.opsReady(2),
    8,
    "entry2_progress"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new iqc(), args)
}
