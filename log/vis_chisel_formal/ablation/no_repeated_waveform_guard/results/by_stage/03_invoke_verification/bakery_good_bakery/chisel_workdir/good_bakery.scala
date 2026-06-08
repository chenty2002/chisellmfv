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
  // Formal Verification Assertions
  // ============================================================

  // --- Safety: Mutual Exclusion ---
  // No two processes may be in the critical section (L10) simultaneously.
  // The critical section is where the process clears its ticket and defer bits,
  // corresponding to the release phase of the bakery algorithm.
  assertMutex(
    Seq(pc(0) === Loc.L10, pc(1) === Loc.L10, pc(2) === Loc.L10),
    "mutual_exclusion_critical_section"
  )

  // --- Safety: Choosing signal consistency ---
  // The choosing(i) signal must only be asserted when process i is actively
  // choosing a ticket number, i.e. in states L2 or L3.
  // choosing is set true at L1→L2 and cleared false at L3→L4.
  for (i <- 0 to HIPROC) {
    fvAssert(
      !choosing(i) || pc(i) === Loc.L2 || pc(i) === Loc.L3,
      s"choosing_consistent_p$i"
    )
  }

  // --- Safety: Ticket consistency ---
  // If ticket(i) is true, process i must be past the choosing phase (past L3)
  // and still waiting for or in the critical section (before L11).
  // ticket(i) is set true at L2→L3 and cleared false at L10→L11.
  for (i <- 0 to HIPROC) {
    fvAssert(
      !ticket(i) || pc(i).isOneOf(Loc.L3, Loc.L4, Loc.L5, Loc.L6, Loc.L7, Loc.L8, Loc.L9, Loc.L10),
      s"ticket_consistent_p$i"
    )
  }

  // --- Safety: No process in CS while another is choosing ---
  // A process in the critical section (L10) must not coexist with another
  // process that is still choosing (choosing true). This mirrors the
  // bakery algorithm's guarantee that choosing processes are waited on.
  for (i <- 0 to HIPROC) {
    for (j <- 0 to HIPROC) {
      if (i != j) {
        fvAssert(
          !(pc(i) === Loc.L10 && choosing(j)),
          s"no_cs_during_choose_p${i}_p${j}"
        )
      }
    }
  }

  // --- Liveness: Progress into critical section ---
  // If a process is at the initial state L1 and is selected (selReg matches),
  // it should eventually reach the critical section (L10) within a generous
  // bounded number of cycles. The bound accounts for the full traversal of the
  // state machine: inner loops over all processes (0..HIPROC) and waiting at
  // L6/L7 for other processes' choosing/ticket conditions.
  for (i <- 0 to HIPROC) {
    astRelaxedLiveness(
      selReg === i.U && pc(i) === Loc.L1,
      pc(i) === Loc.L10,
      200,
      s"progress_into_cs_p$i"
    )
  }
}

object VerilogGenerator extends App {
  emitVerilog(new bakery(), args)
}
