package llmverify

import chisel3._
import chisel3.util._

// Enum for program counter locations
object Loc extends ChiselEnum {
  val L1, L2a, L2b, L2c, L3, L4, L5, L6, L7, L8, L9,
      L10a, L10b, L10c, L11 = Value
}

// Enum for Buechi automaton states
object States extends ChiselEnum {
  val n2, n3, n7, n9, n10, Trap = Value
}

class Bakery extends Module {
  val io = IO(new Bundle {
    val select = Input(UInt(3.W))  // SELMSB+1 bits
    val pause = Input(Bool())
    
    // Outputs for debugging/verification
    val ticket = Output(Vec(3, Bool()))
    val choosing = Output(Vec(3, Bool()))
    val pc = Output(Vec(3, Loc()))
    val j = Output(Vec(3, UInt(3.W)))
    val defer = Output(Vec(3, UInt(3.W)))
    val pri = Output(Vec(3, UInt(3.W)))
    val selReg = Output(UInt(3.W))
    val k = Output(UInt(3.W))
    val defSelK = Output(Bool())
    val defKSel = Output(Bool())
    
    // Buechi outputs
    val fair0 = Output(Bool())
    val fair1 = Output(Bool())
    val scc = Output(Bool())
    val scc_entries = Output(Bool())
  })
  
  val SELMSB = 2
  val HIPROC = 2
  
  // State registers
  val ticket = RegInit(VecInit(Seq.fill(HIPROC+1)(false.B)))
  val choosing = RegInit(VecInit(Seq.fill(HIPROC+1)(false.B)))
  val pc = RegInit(VecInit(Seq.fill(HIPROC+1)(Loc.L1)))
  val j = RegInit(VecInit(Seq.fill(HIPROC+1)(0.U(3.W))))
  val selReg = RegInit(0.U(3.W))
  val k = RegInit(0.U(3.W))
  val defer = RegInit(VecInit(Seq.fill(HIPROC+1)(0.U(3.W))))
  val pri = RegInit(VecInit(Seq.fill(HIPROC+1)(0.U(3.W))))
  val defSelK = RegInit(false.B)
  val defKSel = RegInit(false.B)
  
  // Helper functions
  def extract(in: UInt, index: UInt): Bool = {
    val result = Wire(Bool())
    result := MuxLookup(index, false.B)(Seq(
      0.U -> in(0),
      1.U -> in(1),
      2.U -> in(2)
    ))
    result
  }
  
  def setBit(in: UInt, index: UInt, valBit: Bool): UInt = {
    val result = Wire(UInt(3.W))
    result := MuxLookup(index, in)(Seq(
      0.U -> Cat(in(2,1), valBit),
      1.U -> Cat(in(2), valBit, in(0)),
      2.U -> Cat(valBit, in(1,0))
    ))
    result
  }
  
  // Process logic for each selected process
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
        
        when(ticket(k) && defSelK && !defKSel && pri(sel) < pri(k)) {
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
  
  // Initialize priority values
  for (i <- 0 to HIPROC) {
    when(reset.asBool) {
      pri(i) := i.U
    }
  }
  
  // Main logic
  when(io.select > HIPROC.U) {
    selReg := 0.U
  }.otherwise {
    selReg := io.select
  }
  
  process(selReg)
  
  // Buechi automaton
  val state = RegInit(States.n3)
  val p = io.select === 0.U
  val q = io.select === 1.U && io.pause === false.B
  val r1 = pc(0) === Loc.L4 && pc(1) === Loc.L9
  val s1 = pc(0) === Loc.L9
  
  // Nondeterministic transitions (simplified for Chisel)
  val ND_n2_n9 = Wire(States())
  val ND_n10_n3 = Wire(States())
  val ND_n7_n9 = Wire(States())
  val ND_n2_n7_n9 = Wire(States())
  
  // Simplified nondeterministic choices
  ND_n2_n9 := Mux(state === States.n2, States.n9, States.n2)
  ND_n10_n3 := Mux(state === States.n10, States.n3, States.n10)
  ND_n7_n9 := Mux(state === States.n7, States.n9, States.n7)
  ND_n2_n7_n9 := Mux(state === States.n2, Mux(state === States.n7, States.n9, States.n7), States.n2)
  
  switch(state) {
    is(States.n3) {
      switch(Cat(r1, s1)) {
        is("b00".U) { state := States.n3 }
        is("b10".U) { state := ND_n10_n3 }
        is("b11".U) { state := States.n3 }
      }
    }
    is(States.n10) {
      when(s1) {
        state := States.Trap
      }.otherwise {
        state := States.n9
      }
    }
    is(States.Trap) {
      state := States.Trap
    }
    is(States.n2, States.n7, States.n9) {
      switch(Cat(p, q, s1)) {
        is("b000".U) { state := States.n9 }
        is("b001".U) { state := States.Trap }
        is("b010".U) { state := ND_n2_n9 }
        is("b100".U) { state := ND_n7_n9 }
        is("b110".U) { state := ND_n2_n7_n9 }
      }
    }
  }
  
  // Handle default case for the inner switch
  when(state === States.n2 || state === States.n7 || state === States.n9) {
    val pqS1 = Cat(p, q, s1)
    when(pqS1 =/= "b000".U && pqS1 =/= "b001".U && pqS1 =/= "b010".U && 
           pqS1 =/= "b100".U && pqS1 =/= "b110".U) {
      state := States.Trap
    }
  }
  
  // Output assignments
  io.ticket := ticket
  io.choosing := choosing
  io.pc := pc
  io.j := j
  io.defer := defer
  io.pri := pri
  io.selReg := selReg
  io.k := k
  io.defSelK := defSelK
  io.defKSel := defKSel
  io.fair0 := state === States.n2
  io.fair1 := state === States.n7
  io.scc := state === States.n2 || state === States.n7 || state === States.n9
  io.scc_entries := state === States.n9
}

object VerilogGenerator extends App {
  emitVerilog(new Bakery(), args)
}