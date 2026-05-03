package llmverify

import chisel3._
import chisel3.util._

// Enum for program counter locations
object Loc extends ChiselEnum {
  val L1, L2, L3, L4, L5, L6, L7, L8, L9, L10, L11 = Value
}

class Bakery(SELMSB: Int = 1, HIPROC: Int = 1) extends Module {
  val io = IO(new Bundle {
    val select = Input(UInt((SELMSB + 1).W))
    val pause = Input(Bool())
    // Add outputs to preserve internal state for verification
    val ticket = Output(Vec(HIPROC + 1, UInt(1.W)))
    val choosing = Output(Vec(HIPROC + 1, Bool()))
    val pc = Output(Vec(HIPROC + 1, Loc()))
    val j = Output(Vec(HIPROC + 1, UInt((SELMSB + 1).W)))
    val selReg = Output(UInt((SELMSB + 1).W))
    val k = Output(UInt((SELMSB + 1).W))
    val defer = Output(Vec(HIPROC + 1, UInt((HIPROC + 1).W)))
    val defSel = Output(UInt((HIPROC + 1).W))
    val defK = Output(UInt((HIPROC + 1).W))
    val defSelK = Output(Bool())
    val defKSel = Output(Bool())
  })
  
  // Internal state registers
  val ticket = RegInit(VecInit(Seq.fill(HIPROC + 1)(0.U(1.W))))
  val choosing = RegInit(VecInit(Seq.fill(HIPROC + 1)(false.B)))
  val pc = RegInit(VecInit(Seq.fill(HIPROC + 1)(Loc.L1)))
  val j = RegInit(VecInit(Seq.fill(HIPROC + 1)(0.U((SELMSB + 1).W))))
  val selReg = RegInit(0.U((SELMSB + 1).W))
  val k = RegInit(0.U((SELMSB + 1).W))
  val defer = RegInit(VecInit(Seq.fill(HIPROC + 1)(0.U((HIPROC + 1).W))))
  val defSel = RegInit(0.U((HIPROC + 1).W))
  val defK = RegInit(0.U((HIPROC + 1).W))
  val defSelK = RegInit(false.B)
  val defKSel = RegInit(false.B)
  
  // Extract function equivalent - using Mux instead of switch
  def extract(in: UInt, index: UInt): Bool = {
    Mux(index === 0.U, in(0),
    Mux(index === 1.U, in(1),
        false.B))
  }
  
  // Process function for each selected process
  def process(sel: UInt): Unit = {
    when(pc(sel) === Loc.L1) {
      choosing(sel) := true.B
      pc(sel) := Loc.L2
    }.elsewhen(pc(sel) === Loc.L2) {
      defSel := 0.U
      for (i <- 0 to HIPROC) {
        defSel := Cat(ticket(i), defSel(HIPROC, 1))
      }
      defer(sel) := defSel
      ticket(sel) := 1.U
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
      defSel := defer(sel)
      defK := defer(k)
      defSelK := extract(defSel, k)
      defKSel := extract(defK, sel)
      when(ticket(k) === 1.U && (defSelK || (!defKSel && k < sel))) {
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
  
  // Clock edge behavior
  when(io.select > HIPROC.U) {
    selReg := 0.U
  }.otherwise {
    selReg := io.select
  }
  
  process(selReg)
  
  // Connect outputs for verification
  io.ticket := ticket
  io.choosing := choosing
  io.pc := pc
  io.j := j
  io.selReg := selReg
  io.k := k
  io.defer := defer
  io.defSel := defSel
  io.defK := defK
  io.defSelK := defSelK
  io.defKSel := defKSel
}

object VerilogGenerator extends App {
  emitVerilog(new Bakery(1, 1), args)
}