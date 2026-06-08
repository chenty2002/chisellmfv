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
          ticket(selUInt) := false.B
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

  // ============================================================
  // FORMAL ASSERTIONS
  // ============================================================

  // --- Safety: Mutual Exclusion ---
  // Property 1: At most one process can be in the critical section (L9) at any time
  {
    val inCS = VecInit((0 to HIPROC).map(i => pc(i) === Loc.L9))
    assertMutex(inCS.toSeq, "mutual_exclusion_critical_section_L9")
  }

  // Property 2: At most one process can be in the critical section or clean-up (L9 or L10) at any time
  {
    val inCSRegion = VecInit((0 to HIPROC).map(i => pc(i) === Loc.L9 || pc(i) === Loc.L10))
    assertMutex(inCSRegion.toSeq, "mutual_exclusion_critical_or_cleanup_L9_L10")
  }

  // Property 3: At most one process can be in the entire critical-exit region (L9, L10, or L11) at any time
  {
    val inCSExitRegion = VecInit((0 to HIPROC).map(i =>
      pc(i) === Loc.L9 || pc(i) === Loc.L10 || pc(i) === Loc.L11
    ))
    assertMutex(inCSExitRegion.toSeq, "mutual_exclusion_critical_or_exit_region")
  }

  // --- Safety: Choosing Protocol ---
  // Property 4: At most one process should have choosing=true at any time
  assertMutex(choosing.toSeq, "mutex_choosing_flag")

  // --- Safety: Ticket consistency ---
  // Property 5: If a process has ticket=true, it must not be at L1 (idle)
  for (i <- 0 to HIPROC) {
    fvAssert(!ticket(i) || pc(i) =/= Loc.L1, s"ticket_implies_not_idle_p${i}")
  }

  // Property 6: If a process has choosing=true, it should be in L2 or L3
  for (i <- 0 to HIPROC) {
    fvAssert(!choosing(i) || pc(i) === Loc.L2 || pc(i) === Loc.L3, s"choosing_implies_L2_or_L3_p${i}")
  }

  // Property 7: If a process has ticket=true, it must not be in the choosing phase (L1-L3)
  for (i <- 0 to HIPROC) {
    fvAssert(!ticket(i) || (pc(i) =/= Loc.L1 && pc(i) =/= Loc.L2 && pc(i) =/= Loc.L3),
      s"ticket_not_during_choosing_p${i}")
  }

  // --- Bounded Liveness / Progress ---
  // Property 8: Once a process starts the entry protocol (has ticket=true),
  // it should eventually clear its ticket (exit critical section).
  // Bound of 50 cycles is generous enough for 3 processes each needing at most ~15 steps.
  for (i <- 0 to HIPROC) {
    astRelaxedLiveness(ticket(i), !ticket(i), 50, s"ticket_liveness_p${i}")
  }

  // Property 9: Once a process enters the main waiting loop (L5-L8),
  // it should eventually reach the critical section (L9) or restart (L1).
  // Bound of 60 cycles accounts for worst-case waiting on 2 other processes.
  for (i <- 0 to HIPROC) {
    val inEntryLoop = pc(i) === Loc.L5 || pc(i) === Loc.L6 || pc(i) === Loc.L7 || pc(i) === Loc.L8
    val reachedTarget = pc(i) === Loc.L9 || pc(i) === Loc.L10 || pc(i) === Loc.L11 || pc(i) === Loc.L1
    astRelaxedLiveness(inEntryLoop, reachedTarget, 60, s"entry_loop_liveness_p${i}")
  }
}

object VerilogGenerator extends App {
  emitVerilog(new bakery(), args)
}