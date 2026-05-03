package llmverify
import chisel3._
import chisel3.util._

// Enums for reader process locations
object LocR {
  def L0 = 0.U(3.W)
  def L1 = 1.U(3.W)
  def L2 = 2.U(3.W)
  def L3 = 3.U(3.W)
  def L4 = 4.U(3.W)
  def L5 = 5.U(3.W)
  def L6 = 6.U(3.W)
  def L7 = 7.U(3.W)
}

// Enums for update process locations
object LocU {
  def L0 = 0.U(4.W)
  def L1 = 1.U(4.W)
  def L2 = 2.U(4.W)
  def L3 = 3.U(4.W)
  def L4 = 4.U(4.W)
  def L5 = 5.U(4.W)
  def L6 = 6.U(4.W)
  def L7 = 7.U(4.W)
  def L8 = 8.U(4.W)
  def L9 = 9.U(4.W)
  def L10 = 10.U(4.W)
}

class rcu extends Module {
  val io = IO(new Bundle {
    val select = Input(UInt(3.W)) // SELMSB = 2, so 3 bits total
    
    // Add outputs to preserve internal state for verification
    val flip_out = Output(Bool())
    val passctr_out = Output(UInt(8.W))
    val ctr_out = Output(Vec(8, Bool()))
    val pc_out = Output(Vec(4, UInt(3.W)))
    val lclFlip_out = Output(Vec(4, Bool()))
    val both_out = Output(Vec(4, Bool()))
    val pcu_out = Output(UInt(4.W))
    val lclPassctr_out = Output(UInt(8.W))
    val cpunum_out = Output(UInt(3.W))
    val self_out = Output(UInt(3.W))
  })
  
  // Parameters
  val PASSES = 10
  val NRDR = 4
  val NRDR_ELEM = 8
  val SELMSB = 2
  
  // Registers
  val flip = RegInit(false.B)
  val ctr = RegInit(VecInit(Seq.fill(NRDR_ELEM)(false.B)))
  val passctr = RegInit(0.U(8.W))
  val self = RegInit(0.U(3.W))
  
  // Reader process registers
  val pc = RegInit(VecInit(Seq.fill(NRDR)(LocR.L0)))
  val lclFlip = RegInit(VecInit(Seq.fill(NRDR)(false.B)))
  val both = RegInit(VecInit(Seq.fill(NRDR)(false.B)))
  
  // Update process registers
  val pcu = RegInit(LocU.L0)
  val lclPassctr = RegInit(0.U(8.W))
  val cpunum = RegInit(0.U(3.W))
  
  // Main logic
  self := io.select
  
  when(self >= NRDR.U) {
    // Update process
    switch(pcu) {
      is(LocU.L0) {
        when(passctr < PASSES.U) {
          lclPassctr := passctr
          pcu := LocU.L1
        }
      }
      is(LocU.L1) {
        when(!lclPassctr(0)) {
          lclPassctr := 255.U
        }
        pcu := LocU.L2
      }
      is(LocU.L2) {
        cpunum := 0.U
        pcu := LocU.L3
      }
      is(LocU.L3) {
        when(cpunum < NRDR.U) {
          pcu := LocU.L4
        }.otherwise {
          pcu := LocU.L6
        }
      }
      is(LocU.L4) {
        when(!ctr(Cat(cpunum, !flip))) {
          pcu := LocU.L5
        }
      }
      is(LocU.L5) {
        cpunum := cpunum + 1.U
        pcu := LocU.L3
      }
      is(LocU.L6) {
        flip := !flip
        pcu := LocU.L7
      }
      is(LocU.L7) {
        cpunum := 0.U
        pcu := LocU.L8
      }
      is(LocU.L8) {
        when(cpunum < NRDR.U) {
          pcu := LocU.L9
        }.otherwise {
          pcu := LocU.L0
        }
      }
      is(LocU.L9) {
        when(!ctr(Cat(cpunum, !flip))) {
          pcu := LocU.L10
        }
      }
      is(LocU.L10) {
        cpunum := cpunum + 1.U
        pcu := LocU.L8
      }
    }
  }.otherwise {
    // Reader process
    val readerIdx = self
    switch(pc(readerIdx)) {
      is(LocR.L0) {
        when(passctr < PASSES.U) {
          lclFlip(readerIdx) := flip
          pc(readerIdx) := LocR.L1
        }
      }
      is(LocR.L1) {
        ctr(Cat(readerIdx, lclFlip(readerIdx))) := !ctr(Cat(readerIdx, lclFlip(readerIdx)))
        pc(readerIdx) := LocR.L2
      }
      is(LocR.L2) {
        when(lclFlip(readerIdx) === flip) {
          both(readerIdx) := false.B
          pc(readerIdx) := LocR.L4
        }.otherwise {
          ctr(Cat(readerIdx, !lclFlip(readerIdx))) := !ctr(Cat(readerIdx, !lclFlip(readerIdx)))
          pc(readerIdx) := LocR.L3
        }
      }
      is(LocR.L3) {
        both(readerIdx) := true.B
        pc(readerIdx) := LocR.L4
      }
      is(LocR.L4) {
        passctr := passctr + 1.U
        pc(readerIdx) := LocR.L5
      }
      is(LocR.L5) {
        passctr := passctr + 1.U
        pc(readerIdx) := LocR.L6
      }
      is(LocR.L6) {
        ctr(Cat(readerIdx, lclFlip(readerIdx))) := !ctr(Cat(readerIdx, lclFlip(readerIdx)))
        pc(readerIdx) := LocR.L7
      }
      is(LocR.L7) {
        when(both(readerIdx)) {
          ctr(Cat(readerIdx, !lclFlip(readerIdx))) := !ctr(Cat(readerIdx, !lclFlip(readerIdx)))
        }
        pc(readerIdx) := LocR.L0
      }
    }
  }
  
  // Connect outputs
  io.flip_out := flip
  io.passctr_out := passctr
  io.ctr_out := ctr
  io.pc_out := pc
  io.lclFlip_out := lclFlip
  io.both_out := both
  io.pcu_out := pcu
  io.lclPassctr_out := lclPassctr
  io.cpunum_out := cpunum
  io.self_out := self
}

object VerilogGenerator extends App {
  emitVerilog(new rcu(), args)
}