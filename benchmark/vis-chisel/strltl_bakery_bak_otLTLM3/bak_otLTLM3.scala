package llmverify

import chisel3._
import chisel3.util._

// Enum for program counter locations
object Loc extends ChiselEnum {
  val L1, L2a, L2b, L2c, L3, L4, L5, L6, L7, L8, L9, L10a, L10b, L10c, L11 = Value
}

// Enum for Buechi automaton states
object States extends ChiselEnum {
  val Init, n2, n6, n8, n9, n12, n15, n16, n17, n20, n21, n23, n24, n25, n27, n29, n31, n32, n35, Trap = Value
}

class Buechi extends Module {
  val io = IO(new Bundle {
    val r0 = Input(Bool())
    val q = Input(Bool())
    val s1 = Input(Bool())
    val p = Input(Bool())
    val s0 = Input(Bool())
    val r1 = Input(Bool())
    val fair0 = Output(Bool())
    val fair1 = Output(Bool())
    val fair2 = Output(Bool())
    val scc = Output(Bool())
    val scc_entries = Output(Bool())
  })
  
  val state = RegInit(States.Init)
  
  // Nondeterministic selections (simplified for Chisel)
  // In Chisel, we'll use a simple priority selector for nondeterminism
  // This is a simplification of the original $ND macro
  def ND(states: States.Type*): States.Type = {
    MuxCase(states.head, states.tail.map(s => (true.B, s)))
  }
  
  val ND_n6_n9 = Wire(States())
  val ND_n27_n6 = Wire(States())
  val ND_n15_n31_n6_n9 = Wire(States())
  val ND_n17_n20_n6_n9 = Wire(States())
  val ND_n29_n32 = Wire(States())
  val ND_n12_n16_n21_n23_n29_n32 = Wire(States())
  val ND_n2_n27_n6_n9 = Wire(States())
  val ND_n15_n24_n27_n6 = Wire(States())
  val ND_n17_n2_n20_n27_n6_n9 = Wire(States())
  val ND_n15_n20_n24_n27_n6_n8 = Wire(States())
  val ND_n16_n23_n29_n32 = Wire(States())
  val ND_n20_n27_n6 = Wire(States())
  val ND_n12_n21 = Wire(States())
  val ND_n12_n29_n32 = Wire(States())
  val ND_n15_n17_n20_n25_n31_n6_n8_n9 = Wire(States())
  val ND_n15_n2_n24_n27_n31_n35_n6_n9 = Wire(States())
  val ND_n12_n32 = Wire(States())
  val ND_n20_n6 = Wire(States())
  val ND_n12_n16_n21_n32 = Wire(States())
  val ND_n16_n32 = Wire(States())
  val ND_n15_n6 = Wire(States())
  val ND_n15_n20_n6_n8 = Wire(States())
  val ND_n15_n17_n2_n20_n24_n25_n27_n31_n35_n6_n8_n9 = Wire(States())
  
  ND_n6_n9 := ND(States.n6, States.n9)
  ND_n27_n6 := ND(States.n27, States.n6)
  ND_n15_n31_n6_n9 := ND(States.n15, States.n31, States.n6, States.n9)
  ND_n17_n20_n6_n9 := ND(States.n17, States.n20, States.n6, States.n9)
  ND_n29_n32 := ND(States.n29, States.n32)
  ND_n12_n16_n21_n23_n29_n32 := ND(States.n12, States.n16, States.n21, States.n23, States.n29, States.n32)
  ND_n2_n27_n6_n9 := ND(States.n2, States.n27, States.n6, States.n9)
  ND_n15_n24_n27_n6 := ND(States.n15, States.n24, States.n27, States.n6)
  ND_n17_n2_n20_n27_n6_n9 := ND(States.n17, States.n2, States.n20, States.n27, States.n6, States.n9)
  ND_n15_n20_n24_n27_n6_n8 := ND(States.n15, States.n20, States.n24, States.n27, States.n6, States.n8)
  ND_n16_n23_n29_n32 := ND(States.n16, States.n23, States.n29, States.n32)
  ND_n20_n27_n6 := ND(States.n20, States.n27, States.n6)
  ND_n12_n21 := ND(States.n12, States.n21)
  ND_n12_n29_n32 := ND(States.n12, States.n29, States.n32)
  ND_n15_n17_n20_n25_n31_n6_n8_n9 := ND(States.n15, States.n17, States.n20, States.n25, States.n31, States.n6, States.n8, States.n9)
  ND_n15_n2_n24_n27_n31_n35_n6_n9 := ND(States.n15, States.n2, States.n24, States.n27, States.n31, States.n35, States.n6, States.n9)
  ND_n12_n32 := ND(States.n12, States.n32)
  ND_n20_n6 := ND(States.n20, States.n6)
  ND_n12_n16_n21_n32 := ND(States.n12, States.n16, States.n21, States.n32)
  ND_n16_n32 := ND(States.n16, States.n32)
  ND_n15_n6 := ND(States.n15, States.n6)
  ND_n15_n20_n6_n8 := ND(States.n15, States.n20, States.n6, States.n8)
  ND_n15_n17_n2_n20_n24_n25_n27_n31_n35_n6_n8_n9 := ND(States.n15, States.n17, States.n2, States.n20, States.n24, States.n25, States.n27, States.n31, States.n35, States.n6, States.n8, States.n9)
  
  io.fair0 := (state === States.n27) || (state === States.n2) || (state === States.n8) || (state === States.n17) || (state === States.n20) || (state === States.n35) || (state === States.n25) || (state === States.n24)
  io.fair1 := (state === States.n2) || (state === States.n17) || (state === States.n9) || (state === States.n35) || (state === States.n31) || (state === States.n25)
  io.fair2 := (state === States.n15) || (state === States.n8) || (state === States.n35) || (state === States.n31) || (state === States.n25) || (state === States.n24)
  
  io.scc := (state === States.n20) || (state === States.n2) || (state === States.n31) || (state === States.n24) || (state === States.n6) || (state === States.n15) || (state === States.n25) || (state === States.n35) || (state === States.n8) || (state === States.n17) || (state === States.n9) || (state === States.n27)
  io.scc_entries := (state === States.n27) || (state === States.n20) || (state === States.n6)
  
  when(state === States.Init) {
    switch(Cat(io.r0, io.r1, io.s0, io.s1)) {
      is("b0000".U) { state := ND_n12_n32 }
      is("b0001".U) { state := ND_n12_n32 }
      is("b0010".U) { state := ND_n12_n29_n32 }
      is("b0011".U) { state := ND_n12_n29_n32 }
      is("b0100".U) { state := ND_n12_n16_n21_n32 }
      is("b0101".U) { state := ND_n12_n32 }
      is("b0110".U) { state := ND_n12_n16_n21_n23_n29_n32 }
      is("b0111".U) { state := ND_n12_n29_n32 }
      is("b1000".U) { state := States.n32 }
      is("b1001".U) { state := States.n32 }
      is("b1010".U) { state := ND_n29_n32 }
      is("b1011".U) { state := ND_n29_n32 }
      is("b1100".U) { state := ND_n16_n32 }
      is("b1101".U) { state := States.n32 }
      is("b1110".U) { state := ND_n16_n23_n29_n32 }
      is("b1111".U) { state := ND_n29_n32 }
    }
  }.elsewhen(state === States.n21 || state === States.n23) {
    switch(Cat(io.r0, io.s1)) {
      is("b00".U) { state := States.n27 }
      is("b01".U) { state := States.Trap }
      is("b10".U) { state := States.Trap }
      is("b11".U) { state := States.Trap }
    }
  }.elsewhen(state === States.Trap) {
    state := States.Trap
  }.elsewhen(state === States.n32) {
    switch(Cat(io.r1, io.s0, io.s1)) {
      is("b000".U) { state := States.n32 }
      is("b001".U) { state := States.n32 }
      is("b010".U) { state := ND_n29_n32 }
      is("b011".U) { state := ND_n29_n32 }
      is("b100".U) { state := ND_n16_n32 }
      is("b101".U) { state := States.n32 }
      is("b110".U) { state := ND_n16_n23_n29_n32 }
      is("b111".U) { state := ND_n29_n32 }
    }
  }.elsewhen(state === States.n12 || state === States.n29) {
    switch(Cat(io.r0, io.r1, io.s1)) {
      is("b000".U) { state := States.n12 }
      is("b001".U) { state := States.n12 }
      is("b010".U) { state := ND_n12_n21 }
      is("b011".U) { state := States.n12 }
      is("b100".U) { state := States.Trap }
      is("b101".U) { state := States.Trap }
      is("b110".U) { state := States.Trap }
      is("b111".U) { state := States.Trap }
    }
  }.elsewhen(state === States.n16) {
    switch(Cat(io.s0, io.s1)) {
      is("b00".U) { state := States.n6 }
      is("b01".U) { state := States.Trap }
      is("b10".U) { state := ND_n20_n6 }
      is("b11".U) { state := States.Trap }
    }
  }.elsewhen(state === States.n2 || state === States.n8 || state === States.n17 || state === States.n20 || state === States.n24 || state === States.n25 || state === States.n27 || state === States.n35) {
    switch(Cat(io.p, io.q, io.r0, io.s0, io.s1)) {
      is("b00000".U) { state := ND_n27_n6 }
      is("b00001".U) { state := States.Trap }
      is("b00010".U) { state := ND_n20_n27_n6 }
      is("b00011".U) { state := States.Trap }
      is("b00100".U) { state := States.n6 }
      is("b00101".U) { state := States.Trap }
      is("b00110".U) { state := ND_n20_n6 }
      is("b00111".U) { state := States.Trap }
      is("b01000".U) { state := ND_n2_n27_n6_n9 }
      is("b01001".U) { state := States.Trap }
      is("b01010".U) { state := ND_n17_n2_n20_n27_n6_n9 }
      is("b01011".U) { state := States.Trap }
      is("b01100".U) { state := ND_n6_n9 }
      is("b01101".U) { state := States.Trap }
      is("b01110".U) { state := ND_n17_n20_n6_n9 }
      is("b01111".U) { state := States.Trap }
      is("b10000".U) { state := ND_n15_n24_n27_n6 }
      is("b10001".U) { state := States.Trap }
      is("b10010".U) { state := ND_n15_n20_n24_n27_n6_n8 }
      is("b10011".U) { state := States.Trap }
      is("b10100".U) { state := ND_n15_n6 }
      is("b10101".U) { state := States.Trap }
      is("b10110".U) { state := ND_n15_n20_n6_n8 }
      is("b10111".U) { state := States.Trap }
      is("b11000".U) { state := ND_n15_n2_n24_n27_n31_n35_n6_n9 }
      is("b11001".U) { state := States.Trap }
      is("b11010".U) { state := ND_n15_n17_n2_n20_n24_n25_n27_n31_n35_n6_n8_n9 }
      is("b11011".U) { state := States.Trap }
      is("b11100".U) { state := ND_n15_n31_n6_n9 }
      is("b11101".U) { state := States.Trap }
      is("b11110".U) { state := ND_n15_n17_n20_n25_n31_n6_n8_n9 }
      is("b11111".U) { state := States.Trap }
    }
  }.elsewhen(state === States.n6 || state === States.n9 || state === States.n15 || state === States.n31) {
    switch(Cat(io.p, io.q, io.s0, io.s1)) {
      is("b0000".U) { state := States.n6 }
      is("b0001".U) { state := States.Trap }
      is("b0010".U) { state := ND_n20_n6 }
      is("b0011".U) { state := States.Trap }
      is("b0100".U) { state := ND_n6_n9 }
      is("b0101".U) { state := States.Trap }
      is("b0110".U) { state := ND_n17_n20_n6_n9 }
      is("b0111".U) { state := States.Trap }
      is("b1000".U) { state := ND_n15_n6 }
      is("b1001".U) { state := States.Trap }
      is("b1010".U) { state := ND_n15_n20_n6_n8 }
      is("b1011".U) { state := States.Trap }
      is("b1100".U) { state := ND_n15_n31_n6_n9 }
      is("b1101".U) { state := States.Trap }
      is("b1110".U) { state := ND_n15_n17_n20_n25_n31_n6_n8_n9 }
      is("b1111".U) { state := States.Trap }
    }
  }
}

class Bakery extends Module {
  val io = IO(new Bundle {
    val select = Input(UInt(2.W))
    val pause = Input(Bool())
    // Outputs for debugging and to preserve signals
    val ticket_out = Output(Vec(3, Bool()))
    val choosing_out = Output(Vec(3, Bool()))
    val pc_out = Output(Vec(3, Loc()))
    val j_out = Output(Vec(3, UInt(2.W)))
    val defer_out = Output(Vec(3, UInt(3.W)))
    val pri_out = Output(Vec(3, UInt(2.W)))
    // Buechi outputs
    val fair0 = Output(Bool())
    val fair1 = Output(Bool())
    val fair2 = Output(Bool())
    val scc = Output(Bool())
    val scc_entries = Output(Bool())
  })
  
  val SELMSB = 1
  val HIPROC = 2
  
  // Process state arrays
  val ticket = RegInit(VecInit(Seq.fill(HIPROC + 1)(false.B)))
  val choosing = RegInit(VecInit(Seq.fill(HIPROC + 1)(false.B)))
  val pc = RegInit(VecInit(Seq.fill(HIPROC + 1)(Loc.L1)))
  val j = RegInit(VecInit(Seq.fill(HIPROC + 1)(0.U(2.W))))
  val defer = RegInit(VecInit(Seq.fill(HIPROC + 1)(0.U(3.W))))
  val pri = RegInit(VecInit(Seq.fill(HIPROC + 1)(0.U(2.W))))
  
  // Initialize priority to process indices
  for (i <- 0 to HIPROC) {
    pri(i) := i.U
  }
  
  val selReg = RegInit(0.U(2.W))
  val k = RegInit(0.U(2.W))
  val defSelK = RegInit(false.B)
  val defKSel = RegInit(false.B)
  
  // Helper functions
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
  
  def setBit(in: UInt, index: UInt, value: Bool): UInt = {
    val result = Wire(UInt(in.getWidth.W))
    when(index === 0.U) {
      result := Cat(in(2, 1), value)
    }.elsewhen(index === 1.U) {
      result := Cat(in(2), value, in(0))
    }.elsewhen(index === 2.U) {
      result := Cat(value, in(1, 0))
    }.otherwise {
      result := in
    }
    result
  }
  
  // Process selection and execution
  when(io.select > HIPROC.U) {
    selReg := 0.U
  }.otherwise {
    selReg := io.select
  }
  
  // Process state machine
  switch(pc(selReg)) {
    is(Loc.L1) {
      choosing(selReg) := true.B
      pc(selReg) := Loc.L2a
    }
    is(Loc.L2a) {
      j(selReg) := 0.U
      pc(selReg) := Loc.L2b
    }
    is(Loc.L2b) {
      when(j(selReg) <= HIPROC.U) {
        pc(selReg) := Loc.L2c
      }.otherwise {
        pc(selReg) := Loc.L3
      }
    }
    is(Loc.L2c) {
      k := j(selReg)
      defer(selReg) := setBit(defer(selReg), k, ticket(k))
      j(selReg) := k + 1.U
      pc(selReg) := Loc.L2b
    }
    is(Loc.L3) {
      ticket(selReg) := true.B
      choosing(selReg) := false.B
      pc(selReg) := Loc.L4
    }
    is(Loc.L4) {
      j(selReg) := 0.U
      pri(selReg) := selReg
      pc(selReg) := Loc.L5
    }
    is(Loc.L5) {
      when(j(selReg) <= HIPROC.U) {
        pc(selReg) := Loc.L6
      }.otherwise {
        pc(selReg) := Loc.L9
      }
    }
    is(Loc.L6) {
      k := j(selReg)
      when(choosing(k)) {
        pc(selReg) := Loc.L6
      }.otherwise {
        pc(selReg) := Loc.L7
      }
    }
    is(Loc.L7) {
      k := j(selReg)
      defSelK := extract(defer(selReg), k)
      defKSel := extract(defer(k), selReg)
      
      when(ticket(k) && defSelK && !defKSel && (pri(selReg) < pri(k))) {
        pri(k) := pri(selReg)
      }
      
      when(ticket(k) && (defSelK || (!defKSel && (pri(k) < pri(selReg))))) {
        pc(selReg) := Loc.L7
      }.otherwise {
        pc(selReg) := Loc.L8
      }
    }
    is(Loc.L8) {
      j(selReg) := j(selReg) + 1.U
      pri(selReg) := selReg
      pc(selReg) := Loc.L5
    }
    is(Loc.L9) {
      when(io.pause) {
        pc(selReg) := Loc.L9
      }.otherwise {
        pc(selReg) := Loc.L10a
      }
    }
    is(Loc.L10a) {
      ticket(selReg) := false.B
      j(selReg) := 0.U
      pc(selReg) := Loc.L10b
    }
    is(Loc.L10b) {
      when(j(selReg) <= HIPROC.U) {
        pc(selReg) := Loc.L10c
      }.otherwise {
        pc(selReg) := Loc.L11
      }
    }
    is(Loc.L10c) {
      k := j(selReg)
      defer(k) := setBit(defer(k), selReg, false.B)
      when(pri(k) === selReg) {
        pri(k) := k
      }
      j(selReg) := k + 1.U
      pc(selReg) := Loc.L10b
    }
    is(Loc.L11) {
      when(io.pause) {
        pc(selReg) := Loc.L11
      }.otherwise {
        pc(selReg) := Loc.L1
      }
    }
  }
  
  // Buechi monitor instantiation
  val buechi = Module(new Buechi())
  
  // Connect Buechi inputs
  val p = io.select === 0.U
  val q = (io.select === 1.U) && !io.pause
  val r1 = pc(0) === Loc.L4 && pc(1) === Loc.L9
  val s1 = pc(0) === Loc.L9
  val r0 = pc(0) === Loc.L6
  val s0 = pc(0) === Loc.L5
  
  buechi.io.r0 := r0
  buechi.io.q := q
  buechi.io.s1 := s1
  buechi.io.p := p
  buechi.io.s0 := s0
  buechi.io.r1 := r1
  
  // Connect outputs
  io.ticket_out := ticket
  io.choosing_out := choosing
  io.pc_out := pc
  io.j_out := j
  io.defer_out := defer
  io.pri_out := pri
  io.fair0 := buechi.io.fair0
  io.fair1 := buechi.io.fair1
  io.fair2 := buechi.io.fair2
  io.scc := buechi.io.scc
  io.scc_entries := buechi.io.scc_entries
}

object VerilogGenerator extends App {
  emitVerilog(new Bakery(), args)
}