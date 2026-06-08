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

  // --- SAFETY: Issue invariants ---

  // At most one slot issues per execution unit per cycle
  assertOneHot0(io.issue0, "issue0_onehot0")
  assertOneHot0(io.issue1, "issue1_onehot0")

  // A slot must never issue to both execution units simultaneously
  fvAssert(!(issue0_0 && issue1_0), "slot0_no_dual_issue")
  fvAssert(!(issue0_1 && issue1_1), "slot1_no_dual_issue")
  fvAssert(!(issue0_2 && issue1_2), "slot2_no_dual_issue")

  // Issue qualification: only valid slots may be issued
  fvAssert(!issue0_0 || valid(0), "issue0_0_requires_valid")
  fvAssert(!issue0_1 || valid(1), "issue0_1_requires_valid")
  fvAssert(!issue0_2 || valid(2), "issue0_2_requires_valid")
  fvAssert(!issue1_0 || valid(0), "issue1_0_requires_valid")
  fvAssert(!issue1_1 || valid(1), "issue1_1_requires_valid")
  fvAssert(!issue1_2 || valid(2), "issue1_2_requires_valid")

  // --- SAFETY: Load invariants ---

  // At most one dispatch port loads into a given slot per cycle
  assertOneHot0(io.load0, "load0_onehot0")
  assertOneHot0(io.load1, "load1_onehot0")
  assertOneHot0(io.load2, "load2_onehot0")

  // Load to a slot requires the slot to be currently invalid
  fvAssert(!io.load0(0) || !valid(0), "load0_req_not_valid")
  fvAssert(!io.load0(1) || !valid(0), "load1_req_not_valid")
  fvAssert(!io.load1(0) || !valid(1), "load1_0_req_not_valid")
  fvAssert(!io.load1(1) || !valid(1), "load1_1_req_not_valid")
  fvAssert(!io.load2(0) || !valid(2), "load2_0_req_not_valid")
  fvAssert(!io.load2(1) || !valid(2), "load2_1_req_not_valid")

  // --- SAFETY: A slot should not be loaded and issued in the same cycle ---
  fvAssert(!(io.load0.orR && (issue0_0 || issue1_0)), "slot0_no_load_issue_conflict")
  fvAssert(!(io.load1.orR && (issue0_1 || issue1_1)), "slot1_no_load_issue_conflict")
  fvAssert(!(io.load2.orR && (issue0_2 || issue1_2)), "slot2_no_load_issue_conflict")

  // --- SAFETY: Flush must clear the valid bit ---
  fvAssert(!io.flush(0) || !nv0, "flush0_clears_valid")
  fvAssert(!io.flush(1) || !nv1, "flush1_clears_valid")
  fvAssert(!io.flush(2) || !nv2, "flush2_clears_valid")

  // --- STRUCTURAL: Valid bits should be at-most-one-hot ---
  // (entries fill from the bottom, so in practice at most 3 entries but never
  //  more than the load/dispatch bandwidth allows)
  assertOneHot0(valid, "valid_onehot0")

  // --- LIVENESS: Ready instructions eventually issue ---
  // If a slot is valid and its operands are ready and the execution unit is
  // ready, then the instruction must issue within a bounded number of cycles.
  astRelaxedLiveness(
    valid(0) && io.opsReady(0) && io.exeReady.orR,
    issue0_0 || issue1_0, 5,
    "slot0_issue_liveness"
  )
  astRelaxedLiveness(
    valid(1) && io.opsReady(1) && io.exeReady.orR,
    issue0_1 || issue1_1, 5,
    "slot1_issue_liveness"
  )
  astRelaxedLiveness(
    valid(2) && io.opsReady(2) && io.exeReady.orR,
    issue0_2 || issue1_2, 5,
    "slot2_issue_liveness"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new iqc(), args)
}
