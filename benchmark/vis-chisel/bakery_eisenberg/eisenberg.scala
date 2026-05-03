package llmverify

import chisel3._
import chisel3.util._

// Type of program counter locations.
object Loc extends ChiselEnum {
  val L1, L2, L3, L4, L5, L6, L7, L8, L9, L10, L11, L12, L13, L14, L15, L16 = Value
}

// Type of process activity.
object Activity extends ChiselEnum {
  val idle, waiting, active = Value
}

class Eisenberg(val HIPROC: Int = 2, val SELMSB: Int = 1) extends Module {
  val io = IO(new Bundle {
    val select = Input(UInt((SELMSB + 1).W))
    val pause = Input(Bool())
    // Add outputs to preserve the design
    val flag_out = Output(Vec(HIPROC + 1, Activity()))
    val turn_out = Output(UInt((SELMSB + 1).W))
    val pc_out = Output(Vec(HIPROC + 1, Loc()))
    val j_out = Output(Vec(HIPROC + 1, UInt((SELMSB + 1).W)))
    val selReg_out = Output(UInt((SELMSB + 1).W))
    val k_out = Output(UInt((SELMSB + 1).W))
  })

  // The activity flags of the processes.
  val flag = RegInit(VecInit(Seq.fill(HIPROC + 1)(Activity.idle)))
  // Whose turn it is to enter the CS.
  val turn = RegInit(0.U((SELMSB + 1).W))
  // The program counters of the processes.
  val pc = RegInit(VecInit(Seq.fill(HIPROC + 1)(Loc.L1)))
  // The loop indices of the processors.
  val j = RegInit(VecInit(Seq.fill(HIPROC + 1)(0.U((SELMSB + 1).W))))
  // The latched values of the process variables.
  val selReg = RegInit(0.U((SELMSB + 1).W))
  // Register used to hold j[sel].
  val k = RegInit(0.U((SELMSB + 1).W))

  // Process logic for a given selected process
  def process(sel: UInt): Unit = {
    when(pc(sel) === Loc.L1) {
      flag(sel) := Activity.waiting
      pc(sel) := Loc.L2
    }.elsewhen(pc(sel) === Loc.L2) {
      j(sel) := turn
      pc(sel) := Loc.L3
    }.elsewhen(pc(sel) === Loc.L3) {
      when(j(sel) =/= sel) {
        pc(sel) := Loc.L4
      }.otherwise {
        pc(sel) := Loc.L7
      }
    }.elsewhen(pc(sel) === Loc.L4) {
      k := j(sel)
      when(flag(k) =/= Activity.idle) {
        pc(sel) := Loc.L5
      }.otherwise {
        pc(sel) := Loc.L6
      }
    }.elsewhen(pc(sel) === Loc.L5) {
      j(sel) := turn
      pc(sel) := Loc.L3
    }.elsewhen(pc(sel) === Loc.L6) {
      when(j(sel) === HIPROC.U) {
        j(sel) := 0.U
      }.otherwise {
        j(sel) := j(sel) + 1.U
      }
      pc(sel) := Loc.L3
    }.elsewhen(pc(sel) === Loc.L7) {
      flag(sel) := Activity.active
      pc(sel) := Loc.L8
    }.elsewhen(pc(sel) === Loc.L8) {
      j(sel) := 0.U
      pc(sel) := Loc.L9
    }.elsewhen(pc(sel) === Loc.L9) {
      k := j(sel)
      when(j(sel) <= HIPROC.U && (k === sel || flag(k) =/= Activity.active)) {
        j(sel) := k + 1.U
        pc(sel) := Loc.L9
      }.otherwise {
        pc(sel) := Loc.L10
      }
    }.elsewhen(pc(sel) === Loc.L10) {
      when(j(sel) > HIPROC.U && (turn === sel || flag(turn) === Activity.idle)) {
        pc(sel) := Loc.L11
      }.otherwise {
        pc(sel) := Loc.L1
      }
    }.elsewhen(pc(sel) === Loc.L11) {
      turn := sel
      pc(sel) := Loc.L12
    }.elsewhen(pc(sel) === Loc.L12) {
      when(io.pause) {
        pc(sel) := Loc.L12
      }.otherwise {
        pc(sel) := Loc.L13
      }
    }.elsewhen(pc(sel) === Loc.L13) {
      when(turn === HIPROC.U) {
        j(sel) := 0.U
      }.otherwise {
        j(sel) := turn + 1.U
      }
      pc(sel) := Loc.L14
    }.elsewhen(pc(sel) === Loc.L14) {
      k := j(sel)
      when(flag(k) === Activity.idle) {
        when(k === HIPROC.U) {
          j(sel) := 0.U
        }.otherwise {
          j(sel) := k + 1.U
        }
        pc(sel) := Loc.L14
      }.otherwise {
        pc(sel) := Loc.L15
      }
    }.elsewhen(pc(sel) === Loc.L15) {
      turn := j(sel)
      pc(sel) := Loc.L16
    }.elsewhen(pc(sel) === Loc.L16) {
      flag(sel) := Activity.idle
      when(io.pause) {
        pc(sel) := Loc.L16
      }.otherwise {
        pc(sel) := Loc.L1
      }
    }
  }

  // Clock logic
  when(io.select > HIPROC.U) {
    selReg := 0.U
  }.otherwise {
    selReg := io.select
  }
  
  process(selReg)

  // Connect outputs to preserve the design
  io.flag_out := flag
  io.turn_out := turn
  io.pc_out := pc
  io.j_out := j
  io.selReg_out := selReg
  io.k_out := k
}

object VerilogGenerator extends App {
  emitVerilog(new Eisenberg(), args)
}