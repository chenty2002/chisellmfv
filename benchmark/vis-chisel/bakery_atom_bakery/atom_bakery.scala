package llmverify
import chisel3._
import chisel3.util._

object VerilogGenerator extends App {
  emitVerilog(new Bakery(SELMSB = 1, HIPROC = 2), args)
}

class Bakery(val SELMSB: Int = 1, val HIPROC: Int = 2) extends Module {
  val io = IO(new Bundle {
    val select = Input(UInt((SELMSB + 1).W))
    val pause = Input(Bool())
    // Add outputs to preserve internal state for verification
    val ticket_out = Output(Vec(HIPROC + 1, Bool()))
    val choosing_out = Output(Vec(HIPROC + 1, Bool()))
    val pc_out = Output(Vec(HIPROC + 1, UInt(4.W))) // 4 bits for 15 enum values
    val j_out = Output(Vec(HIPROC + 1, UInt((SELMSB + 1).W)))
    val defer_out = Output(Vec(HIPROC + 1, UInt((HIPROC + 1).W)))
    val pri_out = Output(Vec(HIPROC + 1, UInt((SELMSB + 1).W)))
    val selReg_out = Output(UInt((SELMSB + 1).W))
  })
  
  // Enum for program counter locations
  object Loc extends ChiselEnum {
    val L1 = Value(0.U)
    val L2a = Value(1.U)
    val L2b = Value(2.U)
    val L2c = Value(3.U)
    val L3 = Value(4.U)
    val L4 = Value(5.U)
    val L5 = Value(6.U)
    val L6 = Value(7.U)
    val L7 = Value(8.U)
    val L8 = Value(9.U)
    val L9 = Value(10.U)
    val L10a = Value(11.U)
    val L10b = Value(12.U)
    val L10c = Value(13.U)
    val L11 = Value(14.U)
  }
  
  // The ticket-holding flags of the processes.
  val ticket = RegInit(VecInit(Seq.fill(HIPROC + 1)(false.B)))
  // More than one process may be choosing a ticket.
  val choosing = RegInit(VecInit(Seq.fill(HIPROC + 1)(false.B)))
  // The program counters of the processes.
  val pc = RegInit(VecInit(Seq.fill(HIPROC + 1)(Loc.L1)))
  // The loop indices of the processors.
  val j = RegInit(VecInit(Seq.fill(HIPROC + 1)(0.U((SELMSB + 1).W))))
  // The latched value of the process selection variable.
  val selReg = RegInit(0.U((SELMSB + 1).W))
  // Register used to hold j[sel].
  val k = RegInit(0.U((SELMSB + 1).W))
  val defer = RegInit(VecInit(Seq.fill(HIPROC + 1)(0.U((HIPROC + 1).W))))
  val priInit = (0 to HIPROC).map(i => i.U((SELMSB + 1).W))
  val pri = RegInit(VecInit(priInit))
  val defSelK = WireInit(false.B)
  val defKSel = WireInit(false.B)
  
  // Extract one bit from a vector.
  def extract(in: UInt, index: UInt): Bool = {
    val result = WireInit(false.B)
    switch(index) {
      is(0.U) { result := in(0) }
      is(1.U) { result := in(1) }
      is(2.U) { result := in(2) }
    }
    result
  }
  
  // Returns the input with the bit selected by the second input
  // set to the value of the third input using bitwise operations.
  def setBit(in: UInt, index: UInt, value: Bool): UInt = {
    val result = Wire(UInt(in.getWidth.W))
    when(value) {
      result := in | (1.U << index)
    }.otherwise {
      result := in & ~(1.U << index)
    }
    result
  }
  
  // Process task implementation
  def process(sel: UInt): Unit = {
    switch(pc(sel)) {
      is(Loc.L1) {
        choosing(sel) := true.B
        pc(sel) := Loc.L2a
      }
      is(Loc.L2a) {
        j(sel) := 0.U
        pc(sel) := Loc.L2b
      }
      is(Loc.L2b) {
        when(j(sel) <= HIPROC.U) {
          pc(sel) := Loc.L2c
        }.otherwise {
          pc(sel) := Loc.L3
        }
      }
      is(Loc.L2c) {
        k := j(sel)
        defer(sel) := setBit(defer(sel), k, ticket(k))
        j(sel) := k + 1.U
        pc(sel) := Loc.L2b
      }
      is(Loc.L3) {
        ticket(sel) := true.B
        choosing(sel) := false.B
        pc(sel) := Loc.L4
      }
      is(Loc.L4) {
        j(sel) := 0.U
        pri(sel) := sel
        pc(sel) := Loc.L5
      }
      is(Loc.L5) {
        when(j(sel) <= HIPROC.U) {
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
        defSelK := extract(defer(sel), k)
        defKSel := extract(defer(k), sel)
        
        when(ticket(k) && defSelK && !defKSel && (pri(sel) < pri(k))) {
          pri(k) := pri(sel)
        }
        
        when(ticket(k) && (defSelK || (!defKSel && (pri(k) < pri(sel))))) {
          pc(sel) := Loc.L7
        }.otherwise {
          pc(sel) := Loc.L8
        }
      }
      is(Loc.L8) {
        j(sel) := j(sel) + 1.U
        pri(sel) := sel
        pc(sel) := Loc.L5
      }
      is(Loc.L9) {
        when(io.pause) {
          pc(sel) := Loc.L9
        }.otherwise {
          pc(sel) := Loc.L10a
        }
      }
      is(Loc.L10a) {
        ticket(sel) := false.B
        j(sel) := 0.U
        pc(sel) := Loc.L10b
      }
      is(Loc.L10b) {
        when(j(sel) <= HIPROC.U) {
          pc(sel) := Loc.L10c
        }.otherwise {
          pc(sel) := Loc.L11
        }
      }
      is(Loc.L10c) {
        k := j(sel)
        defer(k) := setBit(defer(k), sel, false.B)
        when(pri(k) === sel) {
          pri(k) := k
        }
        j(sel) := k + 1.U
        pc(sel) := Loc.L10b
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
  
  // Clock edge logic
  when(io.select > HIPROC.U) {
    selReg := 0.U
  }.otherwise {
    selReg := io.select
  }
  
  process(selReg)
  
  // Connect outputs to preserve internal state
  io.ticket_out := ticket
  io.choosing_out := choosing
  io.pc_out := pc.map(_.asUInt)
  io.j_out := j
  io.defer_out := defer
  io.pri_out := pri
  io.selReg_out := selReg
}