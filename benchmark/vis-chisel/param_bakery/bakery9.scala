package llmverify

import chisel3._
import chisel3.util._

// Enum for program counter locations
object Loc extends ChiselEnum {
  val L1, L2, L3, L4, L5, L6, L7, L8, L9, L10, L11 = Value
}

class bakery9 extends Module {
  // Parameters
  val TKMSB = 9
  val HIPROC = 1
  val SELMSB = 1
  
  val io = IO(new Bundle {
    val select = Input(UInt((SELMSB + 1).W))
    val pause = Input(Bool())
    // Add outputs to preserve internal state for verification
    val ticket_out = Output(Vec(HIPROC + 1, UInt((TKMSB + 1).W)))
    val choosing_out = Output(Vec(HIPROC + 1, Bool()))
    val pc_out = Output(Vec(HIPROC + 1, Loc()))
    val j_out = Output(Vec(HIPROC + 1, UInt((SELMSB + 1).W)))
    val selReg_out = Output(UInt((SELMSB + 1).W))
    val k_out = Output(UInt((SELMSB + 1).W))
  })
  
  // Internal state
  val ticket = RegInit(VecInit(Seq.fill(HIPROC + 1)(0.U((TKMSB + 1).W))))
  val choosing = RegInit(VecInit(Seq.fill(HIPROC + 1)(false.B)))
  val pc = RegInit(VecInit(Seq.fill(HIPROC + 1)(Loc.L1)))
  val j = RegInit(VecInit(Seq.fill(HIPROC + 1)(0.U((SELMSB + 1).W))))
  val selReg = RegInit(0.U((SELMSB + 1).W))
  val k = RegInit(0.U((SELMSB + 1).W))
  
  // Process logic implementation
  def process(sel: UInt): Unit = {
    when(pc(sel) === Loc.L1) {
      choosing(sel) := true.B
      pc(sel) := Loc.L2
    }.elsewhen(pc(sel) === Loc.L2) {
      // Find maximum ticket and increment
      val maxTicket = ticket.fold(0.U((TKMSB + 1).W)) { (acc, t) =>
        Mux(t > acc, t, acc)
      }
      ticket(sel) := maxTicket + 1.U
      pc(sel) := Loc.L3
    }.elsewhen(pc(sel) === Loc.L3) {
      choosing(sel) := false.B
      pc(sel) := Loc.L4
    }.elsewhen(pc(sel) === Loc.L4) {
      j(sel) := 0.U
      pc(sel) := Loc.L5
    }.elsewhen(pc(sel) === Loc.L5) {
      when(j(sel) <= HIPROC.U) {
        pc(sel) := Loc.L6
      }.otherwise {
        pc(sel) := Loc.L9
      }
    }.elsewhen(pc(sel) === Loc.L6) {
      k := j(sel)
      when(choosing(k)) {
        pc(sel) := Loc.L6
      }.otherwise {
        pc(sel) := Loc.L7
      }
    }.elsewhen(pc(sel) === Loc.L7) {
      k := j(sel)
      when((ticket(k) =/= 0.U) && 
           ((ticket(k) < ticket(sel)) || 
            ((ticket(k) === ticket(sel)) && (k < sel)))) {
        pc(sel) := Loc.L7
      }.otherwise {
        pc(sel) := Loc.L8
      }
    }.elsewhen(pc(sel) === Loc.L8) {
      j(sel) := j(sel) + 1.U
      pc(sel) := Loc.L5
    }.elsewhen(pc(sel) === Loc.L9) {
      when(io.pause) {
        pc(sel) := Loc.L9
      }.otherwise {
        pc(sel) := Loc.L10
      }
    }.elsewhen(pc(sel) === Loc.L10) {
      ticket(sel) := 0.U
      pc(sel) := Loc.L11
    }.elsewhen(pc(sel) === Loc.L11) {
      when(io.pause) {
        pc(sel) := Loc.L11
      }.otherwise {
        pc(sel) := Loc.L1
      }
    }
  }
  
  // Clock behavior
  when(io.select > HIPROC.U) {
    selReg := 0.U
  }.otherwise {
    selReg := io.select
  }
  
  process(selReg)
  
  // Connect outputs for verification
  io.ticket_out := ticket
  io.choosing_out := choosing
  io.pc_out := pc
  io.j_out := j
  io.selReg_out := selReg
  io.k_out := k
}

object VerilogGenerator extends App {
  emitVerilog(new bakery9(), args)
}