package llmverify

import chisel3._
import chisel3.util._

// Enum for program counter locations
object Loc extends ChiselEnum {
  val L1, L2, L3, L4, L5, L6, L7, L8, L9, L10, L11 = Value
}

class Bakery(tkMsb: Int = 1, hiProc: Int = 1, selMsb: Int = 1) extends Module {
  val io = IO(new Bundle {
    val select = Input(UInt((selMsb + 1).W))
    val pause = Input(Bool())
    // Add outputs to preserve internal state for verification
    val ticket_out = Output(Vec(hiProc + 1, UInt((tkMsb + 1).W)))
    val choosing_out = Output(Vec(hiProc + 1, Bool()))
    val pc_out = Output(Vec(hiProc + 1, Loc()))
    val j_out = Output(Vec(hiProc + 1, UInt((selMsb + 1).W)))
    val selReg_out = Output(UInt((selMsb + 1).W))
    val k_out = Output(UInt((selMsb + 1).W))
  })
  
  // The ticket numbers of the processes
  val ticket = RegInit(VecInit(Seq.fill(hiProc + 1)(0.U((tkMsb + 1).W))))
  
  // More than one process may be choosing a ticket
  val choosing = RegInit(VecInit(Seq.fill(hiProc + 1)(false.B)))
  
  // The program counters of the processes
  val pc = RegInit(VecInit(Seq.fill(hiProc + 1)(Loc.L1)))
  
  // The loop indices of the processors
  val j = RegInit(VecInit(Seq.fill(hiProc + 1)(0.U((selMsb + 1).W))))
  
  // The latched values of the process variables
  val selReg = RegInit(0.U((selMsb + 1).W))
  
  // Register used to hold j[sel]
  val k = RegInit(0.U((selMsb + 1).W))
  
  // Connect outputs to preserve internal state
  io.ticket_out := ticket
  io.choosing_out := choosing
  io.pc_out := pc
  io.j_out := j
  io.selReg_out := selReg
  io.k_out := k
  
  // Process implementation for selected process
  when(io.select > hiProc.U) {
    selReg := 0.U
  }.otherwise {
    selReg := io.select
  }
  
  val sel = selReg
  
  // Implement the process task as combinational logic with register updates
  switch(pc(sel)) {
    is(Loc.L1) {
      choosing(sel) := true.B
      pc(sel) := Loc.L2
    }
    is(Loc.L2) {
      // Find maximum ticket and add 1
      val maxTicket = ticket.fold(0.U((tkMsb + 1).W)) { (max, t) =>
        Mux(t > max, t, max)
      }
      ticket(sel) := maxTicket + 1.U
      pc(sel) := Loc.L3
    }
    is(Loc.L3) {
      choosing(sel) := false.B
      pc(sel) := Loc.L4
    }
    is(Loc.L4) {
      j(sel) := 0.U
      pc(sel) := Loc.L5
    }
    is(Loc.L5) {
      when(j(sel) <= hiProc.U) {
        pc(sel) := Loc.L6
      }.otherwise {
        pc(sel) := Loc.L9
      }
    }
    is(Loc.L6) {
      k := j(sel)
      when(choosing(k)) {
        pc(sel) := Loc.L6
      }.otherwise {
        pc(sel) := Loc.L7
      }
    }
    is(Loc.L7) {
      k := j(sel)
      when((ticket(k) =/= 0.U) &&
           (ticket(k) < ticket(sel) ||
            (ticket(k) === ticket(sel) && k < sel))) {
        pc(sel) := Loc.L7
      }.otherwise {
        pc(sel) := Loc.L8
      }
    }
    is(Loc.L8) {
      j(sel) := j(sel) + 1.U
      pc(sel) := Loc.L5
    }
    is(Loc.L9) {
      when(io.pause) {
        pc(sel) := Loc.L9
      }.otherwise {
        pc(sel) := Loc.L10
      }
    }
    is(Loc.L10) {
      ticket(sel) := 0.U
      pc(sel) := Loc.L11
    }
    is(Loc.L11) {
      when(io.pause) {
        pc(sel) := Loc.L11
      }.otherwise {
        pc(sel) := Loc.L1
      }
    }
  }
}

object VerilogGenerator extends App {
  emitVerilog(new Bakery(), Array("--target-dir", "generated"))
}