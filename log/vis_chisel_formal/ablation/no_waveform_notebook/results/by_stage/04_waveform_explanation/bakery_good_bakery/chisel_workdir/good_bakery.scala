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

  // ================================================
  // FORMAL ASSERTIONS
  // ================================================

  // --- SAFETY: Mutual Exclusion ---
  // At most one process can be in the critical section (L10) at any time.
  // This is the fundamental safety property of the Bakery algorithm and
  // the most important assertion to verify.
  val in_cs = (0 to HIPROC).map(i => pc(i) === Loc.L10)
  assertMutex(in_cs, "MutualExclusion_at_most_one_process_in_CS")

  // --- SAFETY: Deterministic State Transitions ---
  // When a process is selected (selReg === i.U), the following states must
  // transition to their unique next state on the next cycle.
  // These verify the state machine logic is correctly implemented.
  for (sel <- 0 to HIPROC) {
    val selected = selReg === sel.U

    // L1 -> L2: Start choosing protocol
    assertImpliesDelay(selected && (pc(sel) === Loc.L1),
      pc(sel) === Loc.L2, 1, s"DetTransition_L1_to_L2_p${sel}")

    // L2 -> L3: Snapshot ticket state, grab ticket
    assertImpliesDelay(selected && (pc(sel) === Loc.L2),
      pc(sel) === Loc.L3, 1, s"DetTransition_L2_to_L3_p${sel}")

    // L3 -> L4: Finish choosing
    assertImpliesDelay(selected && (pc(sel) === Loc.L3),
      pc(sel) === Loc.L4, 1, s"DetTransition_L3_to_L4_p${sel}")

    // L4 -> L5: Initialize loop counter
    assertImpliesDelay(selected && (pc(sel) === Loc.L4),
      pc(sel) === Loc.L5, 1, s"DetTransition_L4_to_L5_p${sel}")

    // L8 -> L5: Increment loop counter and continue
    assertImpliesDelay(selected && (pc(sel) === Loc.L8),
      pc(sel) === Loc.L5, 1, s"DetTransition_L8_to_L5_p${sel}")

    // L10 -> L11: Exit critical section, release ticket
    assertImpliesDelay(selected && (pc(sel) === Loc.L10),
      pc(sel) === Loc.L11, 1, s"DetTransition_L10_to_L11_p${sel}")
  }

  // --- BOUNDED LIVENESS: Progress through waiting states ---
  // When a process is waiting at the critical-section gate (L9) and pause is
  // deasserted and the process is selected, it must enter CS (L10) on the next
  // cycle.  A bound of 5 cycles is more than sufficient for a 1-cycle transition.
  for (sel <- 0 to HIPROC) {
    val req_enter = (pc(sel) === Loc.L9) && !io.pause && (selReg === sel.U)
    val resp_enter = (pc(sel) === Loc.L10)
    astRelaxedLiveness(req_enter, resp_enter, 5, s"BoundedLiveness_L9_to_L10_p${sel}")

    // Similarly, when a process is at the exit gate (L11) and pause is deasserted
    // and the process is selected, it must return to L1.
    val req_exit = (pc(sel) === Loc.L11) && !io.pause && (selReg === sel.U)
    val resp_exit = (pc(sel) === Loc.L1)
    astRelaxedLiveness(req_exit, resp_exit, 5, s"BoundedLiveness_L11_to_L1_p${sel}")
  }

  // --- INVARIANT: Ticket-Choosing Consistency ---
  // The bakery algorithm guarantees that once a process finishes choosing
  // (choosing = false) and holds a ticket (ticket = true), it must be past
  // the choosing phase (past L3 → at L4 through L9 or L10 before ticket cleared).
  // If ticket(i) is set and choosing(i) is false, the process must be in a
  // state after the choosing phase (not L1, L2, or L3).
  for (sel <- 0 to HIPROC) {
    val past_choosing = (pc(sel) =/= Loc.L1) && (pc(sel) =/= Loc.L2) && (pc(sel) =/= Loc.L3)
    fvAssert(
      !(ticket(sel) && !choosing(sel)) || past_choosing,
      s"TicketChoosingInvariant_p${sel}_ticket_set_and_not_choosing_implies_past_choosing_phase"
    )
  }
}

object VerilogGenerator extends App {
  emitVerilog(new bakery(), args)
}
