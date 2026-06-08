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

  // ============================================================
  // Formal Verification Assertions
  // ============================================================

  // ----- Safety: Issue Mutex -----
  // At most one instruction issued per execution unit per cycle
  assertOneHot0(io.issue0, "issue0_one_hot0")
  assertOneHot0(io.issue1, "issue1_one_hot0")

  // ----- Safety: No Dual Issue -----
  // Same slot should never be issued to both execution units simultaneously
  fvAssert(!(issue0_0 && issue1_0), "slot0_not_dual_issued")
  fvAssert(!(issue0_1 && issue1_1), "slot1_not_dual_issued")
  fvAssert(!(issue0_2 && issue1_2), "slot2_not_dual_issued")

  // ----- Safety: Issue Implies Valid -----
  // An instruction can only be issued from a slot that holds a valid instruction
  fvAssert(!issue0_0 || valid(0), "issue0_0_requires_valid")
  fvAssert(!issue0_1 || valid(1), "issue0_1_requires_valid")
  fvAssert(!issue0_2 || valid(2), "issue0_2_requires_valid")
  fvAssert(!issue1_0 || valid(0), "issue1_0_requires_valid")
  fvAssert(!issue1_1 || valid(1), "issue1_1_requires_valid")
  fvAssert(!issue1_2 || valid(2), "issue1_2_requires_valid")

  // ----- Safety: Load Targets Invalid Slots -----
  // A load to a slot should only happen when that slot is not already valid
  fvAssert(!io.load0.orR || !valid(0), "load0_targets_invalid_slot")
  fvAssert(!io.load1.orR || !valid(1), "load1_targets_invalid_slot")
  fvAssert(!io.load2.orR || !valid(2), "load2_targets_invalid_slot")

  // ----- Safety: At Most One Load Per Slot Per Cycle -----
  // A slot cannot be loaded from both dispatch ports simultaneously
  assertOneHot0(io.load0, "load0_one_hot0")
  assertOneHot0(io.load1, "load1_one_hot0")
  assertOneHot0(io.load2, "load2_one_hot0")

  // ----- Safety: Flush Clears Valid -----
  // When a flush signal is asserted for a slot, the slot's valid bit must clear next cycle
  fvAssert(!io.flush(0) || !nv0, "flush0_clears_valid")
  fvAssert(!io.flush(1) || !nv1, "flush1_clears_valid")
  fvAssert(!io.flush(2) || !nv2, "flush2_clears_valid")

  // ----- Safety: Age Consistency -----
  // If an age bit indicates one slot is older than another, both slots must be valid
  fvAssert(!qAge(0) || (valid(0) && valid(1)), "age01_implies_both_valid")
  fvAssert(!qAge(1) || (valid(0) && valid(2)), "age02_implies_both_valid")
  fvAssert(!qAge(2) || (valid(1) && valid(2)), "age12_implies_both_valid")

  // ----- Liveness: Ready Instructions Eventually Issue -----
  // If a slot is valid, its operands are ready, and at least one execution unit
  // is ready, the instruction should issue within a bounded number of cycles.
  // Bound of 10 is chosen conservatively for a 3-slot, 2-execution-unit design.
  // Note: exeReady is 2 bits wide (indices 0,1); any slot can issue to either
  // execution unit.
  val anyExeReady = io.exeReady(0) || io.exeReady(1)

  astRelaxedLiveness(
    valid(0) && io.opsReady(0) && anyExeReady,
    issue0_0 || issue1_0 || !valid(0) || io.flush(0),
    10, "slot0_ready_eventually_issues"
  )
  astRelaxedLiveness(
    valid(1) && io.opsReady(1) && anyExeReady,
    issue0_1 || issue1_1 || !valid(1) || io.flush(1),
    10, "slot1_ready_eventually_issues"
  )
  astRelaxedLiveness(
    valid(2) && io.opsReady(2) && anyExeReady,
    issue0_2 || issue1_2 || !valid(2) || io.flush(2),
    10, "slot2_ready_eventually_issues"
  )

  // ----- Liveness: Queue Progress -----
  // A valid slot should not remain valid indefinitely; it must eventually issue or be flushed.
  // This catches deadlock scenarios where the queue stops making forward progress.
  astRelaxedLiveness(
    valid(0),
    !valid(0) || issue0_0 || issue1_0 || io.flush(0),
    20, "slot0_forward_progress"
  )
  astRelaxedLiveness(
    valid(1),
    !valid(1) || issue0_1 || issue1_1 || io.flush(1),
    20, "slot1_forward_progress"
  )
  astRelaxedLiveness(
    valid(2),
    !valid(2) || issue0_2 || issue1_2 || io.flush(2),
    20, "slot2_forward_progress"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new iqc(), args)
}
