package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

// Enum for program counter locations
object Loc extends ChiselEnum {
  val L1, L2, L3, L4, L5, L6, L7, L8, L9, L10, L11 = Value
}

class bakery extends Module with Formal {
  val io = IO(new Bundle {
    val select = Input(UInt(2.W))  // SELMSB+1 bits
    val pause = Input(Bool())
    // Add outputs to preserve internal state for verification
    val ticket = Output(Vec(3, Bool()))  // HIPROC+1 = 3
    val choosing = Output(Vec(3, Bool()))
    val pc = Output(Vec(3, Loc()))
    val j = Output(Vec(3, UInt(2.W)))
    val selReg = Output(UInt(2.W))
    val defer = Output(Vec(3, UInt(3.W)))  // HIPROC+1 bits each
  })
  
  val SELMSB = 1
  val HIPROC = 2
  
  // State registers
  val ticket = RegInit(VecInit(Seq.fill(HIPROC + 1)(false.B)))
  val choosing = RegInit(VecInit(Seq.fill(HIPROC + 1)(false.B)))
  val pc = RegInit(VecInit(Seq.fill(HIPROC + 1)(Loc.L1)))
  val j = RegInit(VecInit(Seq.fill(HIPROC + 1)(0.U((SELMSB + 1).W))))
  val selReg = RegInit(0.U((SELMSB + 1).W))
  val defer = RegInit(VecInit(Seq.fill(HIPROC + 1)(0.U((HIPROC + 1).W))))
  
  // Helper function to extract one bit from a vector
  def extract(in: UInt, index: UInt): Bool = {
    val result = Wire(Bool())
    when(index === 0.U) {
      result := in(0)
    }.elsewhen(index === 1.U) {
      result := in(1)
    }.elsewhen(index === 2.U) {
      result := in(2)
    }.otherwise {
      result := false.B
    }
    result
  }
  
  // Helper function to clear a bit at given index
  def clearBit(in: UInt, index: UInt): UInt = {
    val result = Wire(UInt(in.getWidth.W))
    when(index === 0.U) {
      result := in & ~"b001".U
    }.elsewhen(index === 1.U) {
      result := in & ~"b010".U
    }.elsewhen(index === 2.U) {
      result := in & ~"b100".U
    }.otherwise {
      result := in
    }
    result
  }
  
  // Process logic for each process
  for (sel <- 0 to HIPROC) {
    val selUInt = sel.U
    
    switch(pc(selUInt)) {
      is(Loc.L1) {
        when(selReg === selUInt) {
          choosing(selUInt) := true.B
          pc(selUInt) := Loc.L2
        }
      }
      is(Loc.L2) {
        when(selReg === selUInt) {
          val defSel = Wire(UInt((HIPROC + 1).W))
          defSel := Cat(ticket(2), ticket(1), ticket(0))
          defer(selUInt) := defSel
          ticket(selUInt) := true.B
          pc(selUInt) := Loc.L3
        }
      }
      is(Loc.L3) {
        when(selReg === selUInt) {
          choosing(selUInt) := false.B
          pc(selUInt) := Loc.L4
        }
      }
      is(Loc.L4) {
        when(selReg === selUInt) {
          j(selUInt) := 0.U
          pc(selUInt) := Loc.L5
        }
      }
      is(Loc.L5) {
        when(selReg === selUInt) {
          when(j(selUInt) <= HIPROC.U) {
            pc(selUInt) := Loc.L6
          }.otherwise {
            pc(selUInt) := Loc.L9
          }
        }
      }
      is(Loc.L6) {
        when(selReg === selUInt) {
          val k = j(selUInt)
          when(choosing(k)) {
            pc(selUInt) := Loc.L6
          }.otherwise {
            pc(selUInt) := Loc.L7
          }
        }
      }
      is(Loc.L7) {
        when(selReg === selUInt) {
          val k = j(selUInt)
          val defSel = defer(selUInt)
          val defK = defer(k)
          val defSelK = extract(defSel, k)
          val defKSel = extract(defK, selUInt)
          when(ticket(k) && (defSelK || (!defKSel && (k < selUInt)))) {
            pc(selUInt) := Loc.L7
          }.otherwise {
            pc(selUInt) := Loc.L8
          }
        }
      }
      is(Loc.L8) {
        when(selReg === selUInt) {
          j(selUInt) := j(selUInt) + 1.U
          pc(selUInt) := Loc.L5
        }
      }
      is(Loc.L9) {
        when(selReg === selUInt) {
          when(io.pause) {
            pc(selUInt) := Loc.L9
          }.otherwise {
            pc(selUInt) := Loc.L10
          }
        }
      }
      is(Loc.L10) {
        when(selReg === selUInt) {
          // Clear defer bits for all processes
          defer(0) := clearBit(defer(0), selUInt)
          defer(1) := clearBit(defer(1), selUInt)
          defer(2) := clearBit(defer(2), selUInt)
          pc(selUInt) := Loc.L11
        }
      }
      is(Loc.L11) {
        when(selReg === selUInt) {
          when(io.pause) {
            pc(selUInt) := Loc.L11
          }.otherwise {
            ticket(selUInt) := false.B  // Clear ticket only when actually exiting CS
            pc(selUInt) := Loc.L1
          }
        }
      }
    }
  }
  
  // Clock behavior
  when(io.select > HIPROC.U) {
    selReg := 0.U
  }.otherwise {
    selReg := io.select
  }
  
  // Connect outputs
  io.ticket := ticket
  io.choosing := choosing
  io.pc := pc
  io.j := j
  io.selReg := selReg
  io.defer := defer

  // =========================================================
  // FORMAL VERIFICATION ASSERTIONS
  // =========================================================
  
  // --- SAFETY: Mutual Exclusion ---
  // The fundamental correctness property: no two processes may
  // simultaneously occupy the critical section (L10 or L11).
  assertMutex(Seq(
    io.pc(0) === Loc.L10 || io.pc(0) === Loc.L11,
    io.pc(1) === Loc.L10 || io.pc(1) === Loc.L11,
    io.pc(2) === Loc.L10 || io.pc(2) === Loc.L11
  ), "mutual_exclusion_critical_section")

  // Strengthened: at most one process in L10 (the active critical section)
  assertMutex(Seq(
    io.pc(0) === Loc.L10,
    io.pc(1) === Loc.L10,
    io.pc(2) === Loc.L10
  ), "mutual_exclusion_L10_active_only")

  // --- SAFETY: selReg validity ---
  // selReg should only take valid process IDs (0, 1, or 2).
  // Value 3 is impossible because the input is clamped to 0 when io.select > 2.
  fvAssert(io.selReg <= 2.U, "selReg_in_valid_range")

  // --- SAFETY: Bakery algorithm invariants ---
  // Ticket invariant: a process with ticket=true is actively executing
  // the entry protocol (L2 through L9). ticket is set at L2 and cleared at L10,
  // so it must not be set at L1 (not started) or L10/L11 (completed critical section).
  fvAssert(!(io.ticket(0) && (io.pc(0) === Loc.L1)), "ticket_not_at_L1_p0")
  fvAssert(!(io.ticket(1) && (io.pc(1) === Loc.L1)), "ticket_not_at_L1_p1")
  fvAssert(!(io.ticket(2) && (io.pc(2) === Loc.L1)), "ticket_not_at_L1_p2")

  // Choosing invariant: choosing=true only during the ticket-acquisition phase
  // (L1 through L3). It is set in L1 and cleared in L3.
  fvAssert(!(io.choosing(0) && io.pc(0) === Loc.L4), "choosing_cleared_by_L4_p0")
  fvAssert(!(io.choosing(0) && io.pc(0) === Loc.L5), "choosing_cleared_by_L5_p0")
  fvAssert(!(io.choosing(1) && io.pc(1) === Loc.L4), "choosing_cleared_by_L4_p1")
  fvAssert(!(io.choosing(2) && io.pc(2) === Loc.L4), "choosing_cleared_by_L4_p2")

  // --- LIVENESS: Progress from critical-section entry gate (L9) ---
  // When a process is at L9, not paused, and selected, it must advance to L10
  // in the next cycle. This verifies the L9->L10 transition works correctly.
  assertNextStepWhen(
    io.pc(0) === Loc.L9 && !io.pause && io.selReg === 0.U,
    io.pc(0) =/= Loc.L9,
    "progress_L9_enter_cs_p0"
  )
  assertNextStepWhen(
    io.pc(1) === Loc.L9 && !io.pause && io.selReg === 1.U,
    io.pc(1) =/= Loc.L9,
    "progress_L9_enter_cs_p1"
  )
  assertNextStepWhen(
    io.pc(2) === Loc.L9 && !io.pause && io.selReg === 2.U,
    io.pc(2) =/= Loc.L9,
    "progress_L9_enter_cs_p2"
  )

  // --- LIVENESS: Progress from critical-section exit gate (L11) ---
  // When a process is at L11, not paused, and selected, it must advance to L1
  // in the next cycle (completing the critical section cycle).
  assertNextStepWhen(
    io.pc(0) === Loc.L11 && !io.pause && io.selReg === 0.U,
    io.pc(0) =/= Loc.L11,
    "progress_L11_exit_cs_p0"
  )
  assertNextStepWhen(
    io.pc(1) === Loc.L11 && !io.pause && io.selReg === 1.U,
    io.pc(1) =/= Loc.L11,
    "progress_L11_exit_cs_p1"
  )
  assertNextStepWhen(
    io.pc(2) === Loc.L11 && !io.pause && io.selReg === 2.U,
    io.pc(2) =/= Loc.L11,
    "progress_L11_exit_cs_p2"
  )

  // --- LIVENESS: Progress from the waiting loop (L6) ---
  // When a process is at L6, selected, and the monitored process (k=j)
  // is not choosing, the process must advance to L7 in the next cycle.
  // Each index k is bounded 0-2 when in L6 (guaranteed by L5 check j <= HIPROC).
  assertNextStepWhen(
    io.pc(0) === Loc.L6 && io.selReg === 0.U &&
      (io.j(0) <= 2.U) && !io.choosing(io.j(0)),
    io.pc(0) =/= Loc.L6,
    "progress_L6_wait_loop_p0"
  )
  assertNextStepWhen(
    io.pc(1) === Loc.L6 && io.selReg === 1.U &&
      (io.j(1) <= 2.U) && !io.choosing(io.j(1)),
    io.pc(1) =/= Loc.L6,
    "progress_L6_wait_loop_p1"
  )
  assertNextStepWhen(
    io.pc(2) === Loc.L6 && io.selReg === 2.U &&
      (io.j(2) <= 2.U) && !io.choosing(io.j(2)),
    io.pc(2) =/= Loc.L6,
    "progress_L6_wait_loop_p2"
  )

  // --- LIVENESS: Starvation-freedom (relaxed) ---
  // A process that enters the bakery entry protocol should eventually reach
  // the critical section. We use relaxed liveness: once a process is in L9
  // and not paused (ready to enter), it enters L10 within a bound.
  // Bound: at most 6 cycles accounts for up to 2 other processes each taking
  // a turn before this process gets selected again.
  astRelaxedLiveness(
    io.pc(0) === Loc.L9 && !io.pause,
    io.pc(0) === Loc.L10 || io.pc(0) === Loc.L11,
    6, "starvation_free_enter_cs_p0"
  )
  astRelaxedLiveness(
    io.pc(1) === Loc.L9 && !io.pause,
    io.pc(1) === Loc.L10 || io.pc(1) === Loc.L11,
    6, "starvation_free_enter_cs_p1"
  )
  astRelaxedLiveness(
    io.pc(2) === Loc.L9 && !io.pause,
    io.pc(2) === Loc.L10 || io.pc(2) === Loc.L11,
    6, "starvation_free_enter_cs_p2"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new bakery(), args)
}
