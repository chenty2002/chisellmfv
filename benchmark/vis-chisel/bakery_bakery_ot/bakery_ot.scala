package llmverify
import chisel3._
import chisel3.util._

// Enum for program counter locations
object Loc extends ChiselEnum {
  val L1, L2, L3, L4, L5, L6, L7, L8, L9, L10, L11 = Value
}

// Enum for Buechi states
object States extends ChiselEnum {
  val n1, n2, n3, n5, n6, Trap = Value
}

class Buechi extends Module {
  val io = IO(new Bundle {
    val c2 = Input(Bool())
    val w1 = Input(Bool())
    val fair = Output(Bool())
  })
  
  val state = RegInit(States.n5)
  
  // Nondeterministic choice between n2 and n5
  val ND_n2_n5 = Wire(Bool())
  ND_n2_n5 := false.B // Can be set to true for nondeterministic choice
  
  io.fair := (state === States.n6)
  
  switch(state) {
    is(States.n1, States.n6) {
      state := States.n6
    }
    is(States.n2) {
      when(!io.c2 && !io.w1) {
        state := States.Trap
      }.elsewhen(!io.c2 && io.w1) {
        state := States.n3
      }.elsewhen(io.c2) {
        state := States.Trap
      }
    }
    is(States.Trap) {
      state := States.Trap
    }
    is(States.n3) {
      when(!io.w1) {
        state := States.Trap
      }.elsewhen(!io.c2 && io.w1) {
        state := States.n3
      }.elsewhen(io.c2 && io.w1) {
        state := States.n1
      }
    }
    is(States.n5) {
      when(!io.c2) {
        state := States.n5
      }.elsewhen(io.c2 && !io.w1) {
        state := States.n5
      }.elsewhen(io.c2 && io.w1) {
        when(ND_n2_n5) {
          state := States.n2
        }.otherwise {
          state := States.n5
        }
      }
    }
  }
}

class bakery_ot extends Module {
  val io = IO(new Bundle {
    val select = Input(UInt(2.W))
    val pause = Input(Bool())
    val doubleOvertaking = Output(Bool())
    // Additional outputs to preserve internal state
    val pc0 = Output(Loc())
    val pc1 = Output(Loc())
    val pc2 = Output(Loc())
    val ticket0 = Output(Bool())
    val ticket1 = Output(Bool())
    val ticket2 = Output(Bool())
    val choosing0 = Output(Bool())
    val choosing1 = Output(Bool())
    val choosing2 = Output(Bool())
    val defer0 = Output(UInt(3.W))
    val defer1 = Output(UInt(3.W))
    val defer2 = Output(UInt(3.W))
  })
  
  val SELMSB = 1
  val HIPROC = 2
  
  // The ticket-holding flags of the processes
  val ticket = RegInit(VecInit(Seq.fill(HIPROC + 1)(false.B)))
  
  // More than one process may be choosing a ticket
  val choosing = RegInit(VecInit(Seq.fill(HIPROC + 1)(false.B)))
  
  // The program counters of the processes
  val pc = RegInit(VecInit(Seq.fill(HIPROC + 1)(Loc.L1)))
  
  // The loop indices of the processors
  val j = RegInit(VecInit(Seq.fill(HIPROC + 1)(0.U((SELMSB + 1).W))))
  
  // The latched value of the process selection variable
  val selReg = RegInit(0.U((SELMSB + 1).W))
  
  // Register used to hold j[sel]
  val k = RegInit(0.U((SELMSB + 1).W))
  
  // The defer matrix
  val defer = RegInit(VecInit(Seq.fill(HIPROC + 1)(0.U((HIPROC + 1).W))))
  
  // Temporary registers - make them all registers to avoid combinational cycles
  val defSel = RegInit(0.U((HIPROC + 1).W))
  val defK = RegInit(0.U((HIPROC + 1).W))
  val defSelK = RegInit(false.B)
  val defKSel = RegInit(false.B)
  
  // Extract one bit from a vector
  def extract(in: UInt, index: UInt): Bool = {
    val result = Wire(Bool())
    result := false.B
    switch(index) {
      is(0.U) { result := in(0) }
      is(1.U) { result := in(1) }
      is(2.U) { result := in(2) }
    }
    result
  }
  
  // Returns the input with the bit selected by index set to 0
  def clearBit(in: UInt, index: UInt): UInt = {
    val result = Wire(UInt((HIPROC + 1).W))
    result := in
    switch(index) {
      is(0.U) { result := in & ~"b001".U }
      is(1.U) { result := in & ~"b010".U }
      is(2.U) { result := in & ~"b100".U }
    }
    result
  }
  
  // Latch the selection
  when(io.select > HIPROC.U) {
    selReg := 0.U
  }.otherwise {
    selReg := io.select
  }
  
  // Process each process independently
  for (sel <- 0 to HIPROC) {
    val selUInt = sel.U
    
    // Only update the selected process
    when(selReg === selUInt) {
      switch(pc(selUInt)) {
        is(Loc.L1) {
          choosing(selUInt) := true.B
          pc(selUInt) := Loc.L2
        }
        is(Loc.L2) {
          // Build defSel value sequentially
          defSel := 0.U
          for (i <- 0 to HIPROC) {
            defSel := Cat(ticket(i), defSel(HIPROC, 1))
          }
          defer(selUInt) := defSel
          ticket(selUInt) := true.B
          pc(selUInt) := Loc.L3
        }
        is(Loc.L3) {
          choosing(selUInt) := false.B
          pc(selUInt) := Loc.L4
        }
        is(Loc.L4) {
          j(selUInt) := 0.U
          pc(selUInt) := Loc.L5
        }
        is(Loc.L5) {
          when(j(selUInt) <= HIPROC.U) {
            pc(selUInt) := Loc.L6
          }.otherwise {
            pc(selUInt) := Loc.L9
          }
        }
        is(Loc.L6) {
          k := j(selUInt)
          when(choosing(k)) {
            pc(selUInt) := Loc.L6
          }.otherwise {
            pc(selUInt) := Loc.L7
          }
        }
        is(Loc.L7) {
          k := j(selUInt)
          defSel := defer(selUInt)
          defK := defer(k)
          defSelK := extract(defSel, k)
          defKSel := extract(defK, selUInt)
          when(ticket(k) && (defSelK || (!defKSel && k < selUInt))) {
            pc(selUInt) := Loc.L7
          }.otherwise {
            pc(selUInt) := Loc.L8
          }
        }
        is(Loc.L8) {
          j(selUInt) := j(selUInt) + 1.U
          pc(selUInt) := Loc.L5
        }
        is(Loc.L9) {
          when(io.pause) {
            pc(selUInt) := Loc.L9
          }.otherwise {
            pc(selUInt) := Loc.L10
          }
        }
        is(Loc.L10) {
          ticket(selUInt) := false.B
          // Clear defer bits for all processes
          for (i <- 0 to HIPROC) {
            defer(i) := clearBit(defer(i), selUInt)
          }
          pc(selUInt) := Loc.L11
        }
        is(Loc.L11) {
          when(io.pause) {
            pc(selUInt) := Loc.L11
          }.otherwise {
            pc(selUInt) := Loc.L1
          }
        }
      }
    }
  }
  
  // Signals used to communicate with the verilog monitor
  val wait0 = Wire(Bool())
  val critical1 = Wire(Bool())
  
  wait0 := (pc(0) === Loc.L4 || pc(0) === Loc.L5 || pc(0) === Loc.L6 ||
            pc(0) === Loc.L7 || pc(0) === Loc.L8)
  critical1 := (pc(1) === Loc.L9)
  
  // Instantiate the Buechi automaton
  val buechi = Module(new Buechi())
  buechi.io.c2 := critical1
  buechi.io.w1 := wait0
  io.doubleOvertaking := buechi.io.fair
  
  // Connect additional outputs to preserve state
  io.pc0 := pc(0)
  io.pc1 := pc(1)
  io.pc2 := pc(2)
  io.ticket0 := ticket(0)
  io.ticket1 := ticket(1)
  io.ticket2 := ticket(2)
  io.choosing0 := choosing(0)
  io.choosing1 := choosing(1)
  io.choosing2 := choosing(2)
  io.defer0 := defer(0)
  io.defer1 := defer(1)
  io.defer2 := defer(2)
}

object VerilogGenerator extends App {
  emitVerilog(new bakery_ot(), args)
}