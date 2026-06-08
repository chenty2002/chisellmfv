package llmverify

import chisel3._
import chisel3.util._
import chisel3.ltl._
import chisel3.ltl.Sequence._
import chisel3.ltl.Property._

// Model of Dekker's algorithm for mutual exclusion of two processes.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

class dekker extends Module {
  val io = IO(new Bundle {
    val select = Input(Bool())
    val pause = Input(Bool())
    // Add outputs to preserve internal state for verification
    val c0 = Output(Bool())
    val c1 = Output(Bool())
    val turn = Output(UInt(1.W))
    val self = Output(UInt(1.W))
    val pc0 = Output(UInt(3.W))
    val pc1 = Output(UInt(3.W))
  })
  
  // Enum for locations
  val L0 = 0.U(3.W)
  val L1 = 1.U(3.W)
  val L2 = 2.U(3.W)
  val L3 = 3.U(3.W)
  val L4 = 4.U(3.W)
  val L5 = 5.U(3.W)
  val L6 = 6.U(3.W)
  
  // Registers
  val c = RegInit(VecInit(Seq(true.B, true.B)))
  val turn = RegInit(0.U(1.W))
  val self = RegInit(io.select)
  val pc = RegInit(VecInit(Seq(L0, L0)))
  
  // Combinational logic for next state
  val selfNext = Wire(UInt(1.W))
  selfNext := io.select
  
  // State machine logic
  switch(pc(self)) {
    is(L0) {
      when(!io.pause) {
        pc(self) := L1
      }
    }
    is(L1) {
      c(self) := false.B
      pc(self) := L2
    }
    is(L2) {
      when(c(~self) === true.B) {
        pc(self) := L5
      }.otherwise {
        pc(self) := L3
      }
    }
    is(L3) {
      when(turn === self) {
        pc(self) := L2
      }.otherwise {
        c(self) := true.B
        pc(self) := L4
      }
    }
    is(L4) {
      when(turn === self) {
        c(self) := false.B
        pc(self) := L2
      }
    }
    is(L5) {
      when(!io.pause) {
        pc(self) := L6
      }
    }
    is(L6) {
      c(self) := true.B
      turn := ~self
      pc(self) := L0
    }
  }
  
  // Update self register
  self := selfNext
  
  // Connect outputs
  io.c0 := c(0)
  io.c1 := c(1)
  io.turn := turn
  io.self := self
  io.pc0 := pc(0)
  io.pc1 := pc(1)

  // ============================================================
  // Formal Verification Assertions
  // ============================================================

  // Property 1: Mutual Exclusion — both processes must never be
  // in the critical section (L5) at the same time.
  // This is the primary safety property of Dekker's algorithm.
  AssertProperty(!(pc(0) === L5 && pc(1) === L5), None, None, Some("mutual_exclusion"))

  // Property 2: Flag consistency — when a process is in the critical
  // section (L5), its flag must be low (false), indicating it is
  // actively contending for / residing in the CS.
  AssertProperty(!(pc(0) === L5) || (c(0) === false.B), None, None, Some("flag_low_in_cs_p0"))
  AssertProperty(!(pc(1) === L5) || (c(1) === false.B), None, None, Some("flag_low_in_cs_p1"))

  // Property 3: When a process exits the critical section and enters
  // L6, the turn should be flipped to the other process on the
  // following cycle (turn := ~self is a register assignment that
  // takes effect at the next clock edge). Sample the L6 condition
  // from the previous cycle using RegNext. Also snapshot self from
  // the previous cycle to avoid a race condition when self changes
  // simultaneously with the turn update.
  val was_in_l6 = RegNext(pc(self) === L6, false.B)
  val self_prev = RegNext(self)
  AssertProperty(!was_in_l6 || (turn === ~self_prev), None, None, Some("turn_flip_on_exit"))

  // Property 4: Program counters must always stay within the valid range 0–6.
  AssertProperty(pc(0) <= L6 && pc(1) <= L6, None, None, Some("pc_in_range"))

  // Property 5: Bounded liveness — if a process is at L2 (checking entry)
  // and the other process's flag is true (not interested), then that process
  // must go to critical section (L5) within 1 cycle (since pc(self) := L5
  // is a register assignment that takes effect at the next clock edge).
  val p0_ready_at_l2 = (pc(0) === L2) && (c(1) === true.B) && (self === 0.U)
  val p0_in_cs_seq = Sequence(pc(0) === L5).delayRange(1, 1)
  AssertProperty(p0_ready_at_l2 |-> p0_in_cs_seq, None, None, Some("liveness_p0_enter_cs"))

  val p1_ready_at_l2 = (pc(1) === L2) && (c(0) === true.B) && (self === 1.U)
  val p1_in_cs_seq = Sequence(pc(1) === L5).delayRange(1, 1)
  AssertProperty(p1_ready_at_l2 |-> p1_in_cs_seq, None, None, Some("liveness_p1_enter_cs"))
}

object VerilogGenerator extends App {
  emitVerilog(new dekker(), args)
}
