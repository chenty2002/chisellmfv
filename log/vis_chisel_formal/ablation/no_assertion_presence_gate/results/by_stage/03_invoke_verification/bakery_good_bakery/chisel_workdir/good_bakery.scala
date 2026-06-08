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

  // ========== FORMAL ASSERTIONS ==========

  // -----------------------------------------------------------------------
  // 1. Mutual Exclusion (Safety): At most one process in critical section
  //    The critical section is at Loc.L9. The bakery algorithm guarantees
  //    mutual exclusion, which is the fundamental safety property.
  // -----------------------------------------------------------------------
  val in_cs = Wire(UInt((HIPROC + 1).W))
  in_cs := Cat(
    pc(2) === Loc.L9,
    pc(1) === Loc.L9,
    pc(0) === Loc.L9
  )
  assertOneHot0(in_cs, "MutualExclusion_at_most_one_process_in_CS")

  // -----------------------------------------------------------------------
  // 2. Ticket Consistency: A process in the critical section must have
  //    its ticket flag set (ticket is set at L2, cleared at L10).
  // -----------------------------------------------------------------------
  for (i <- 0 to HIPROC) {
    val sUInt = i.U
    fvAssert(
      !(pc(sUInt) === Loc.L9) || ticket(sUInt),
      s"TicketSet_InCS_${i}"
    )
  }

  // -----------------------------------------------------------------------
  // 3. Choosing Consistency: choosing flag must be true only during the
  //    ticket-acquisition phase (L2-L3). At L2 choosing is set, at L3 it
  //    is cleared. It must be false in the critical section and idle state.
  // -----------------------------------------------------------------------
  for (i <- 0 to HIPROC) {
    val sUInt = i.U
    fvAssert(
      !choosing(sUInt) || (pc(sUInt) === Loc.L2) || (pc(sUInt) === Loc.L3),
      s"ChoosingOnlyInL2L3_${i}"
    )
  }

  // -----------------------------------------------------------------------
  // 4. Bounded Liveness / Progress: When a process is selected and in the
  //    trying region (L4-L8), it must reach the critical section (L9) within
  //    a bounded number of cycles during which it is selected.
  //
  //    The trying region consists of:
  //      L4: initialize j=0
  //      L5: check if j <= HIPROC
  //      L6: wait until process j is not choosing
  //      L7: wait until priority condition passes
  //      L8: increment j
  //
  //    With 3 processes (HIPROC=2), the worst case is 3 iterations of the
  //    L5→L6→L7→L8 loop, taking at most ~4 cycles per iteration = 12 cycles,
  //    plus L4→L5 and the final L5→L9. Bound of 30 is generous.
  // -----------------------------------------------------------------------
  for (i <- 0 to HIPROC) {
    val sUInt = i.U
    val trying = (pc(sUInt) === Loc.L4) ||
                 (pc(sUInt) === Loc.L5) ||
                 (pc(sUInt) === Loc.L6) ||
                 (pc(sUInt) === Loc.L7) ||
                 (pc(sUInt) === Loc.L8)
    val selected_and_trying = (selReg === sUInt) && trying
    val in_critical = pc(sUInt) === Loc.L9
    assertLivenessTimer(
      selected_and_trying,
      in_critical,
      30,
      s"BoundedLiveness_Process_${i}_reaches_CS_within_30_selected_cycles"
    )
  }

}

object VerilogGenerator extends App {
  emitVerilog(new bakery(), args)
}
