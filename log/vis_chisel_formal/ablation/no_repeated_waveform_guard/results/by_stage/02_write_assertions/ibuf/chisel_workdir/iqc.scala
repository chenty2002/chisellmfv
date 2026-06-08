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
    (~qAge(1) | ~io.opsReady(0)) & (qAge(2) | ~io.opsReady(1))
  io.issue0 := Cat(issue0_2, issue0_1, issue0_0)

  val issue1_0 = io.exeReady(1) & io.opsReady(0) & valid(0) &
    (qAge(0) | ~io.opsReady(1) | issue0_1) &
    (qAge(1) | ~io.opsReady(2) | issue0_2) & ~issue0_0
  val issue1_1 = io.exeReady(1) & io.opsReady(1) & valid(1) &
    (~qAge(0) | ~io.opsReady(0) | issue0_0) &
    (qAge(2) | ~io.opsReady(2) | issue0_2) & ~issue0_1
  val issue1_2 = io.exeReady(1) & io.opsReady(2) & valid(2) &
    (~qAge(1) | ~io.opsReady(0) | issue0_0) &
    (qAge(2) | ~io.opsReady(1) | issue0_1) & ~issue0_2
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

  // --- SAFETY: Mutual exclusion on issue ports ---
  // At most one instruction per execution unit per cycle
  assertOneHot0(io.issue0, "issue0_mutex_exactly_one_per_cycle")
  assertOneHot0(io.issue1, "issue1_mutex_exactly_one_per_cycle")

  // --- SAFETY: Mutual exclusion on load ports ---
  // At most one slot loaded per dispatch port per cycle
  assertOneHot0(io.load0, "load0_mutex_exactly_one_per_cycle")
  assertOneHot0(io.load1, "load1_mutex_exactly_one_per_cycle")

  // --- SAFETY: Issue only when valid ---
  // An instruction can only be issued from a slot that holds a valid instruction
  fvAssert(~(io.issue0(0) | io.issue1(0)) | valid(0), "issue_only_when_valid_slot0")
  fvAssert(~(io.issue0(1) | io.issue1(1)) | valid(1), "issue_only_when_valid_slot1")
  fvAssert(~(io.issue0(2) | io.issue1(2)) | valid(2), "issue_only_when_valid_slot2")

  // --- SAFETY: No double-issue (same slot from both execution units) ---
  fvAssert(~(io.issue0(0) & io.issue1(0)), "no_double_issue_slot0")
  fvAssert(~(io.issue0(1) & io.issue1(1)), "no_double_issue_slot1")
  fvAssert(~(io.issue0(2) & io.issue1(2)), "no_double_issue_slot2")

  // --- BOUNDED LIVENESS: Progress for each slot ---
  // If a slot is valid and its operands are ready, then within 16 cycles
  // it must be issued or the slot becomes invalid (flushed).
  astRelaxedLiveness(
    valid(0) & io.opsReady(0),
    io.issue0(0) | io.issue1(0) | !valid(0),
    16,
    "slot0_progress_valid_ready_issued_within_16"
  )
  astRelaxedLiveness(
    valid(1) & io.opsReady(1),
    io.issue0(1) | io.issue1(1) | !valid(1),
    16,
    "slot1_progress_valid_ready_issued_within_16"
  )
  astRelaxedLiveness(
    valid(2) & io.opsReady(2),
    io.issue0(2) | io.issue1(2) | !valid(2),
    16,
    "slot2_progress_valid_ready_issued_within_16"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new iqc(), args)
}
