package llmverify

import chisel3._
import chisel3.util._

// Enum for program counter locations
object Loc extends ChiselEnum {
  val L1, L2a, L2b, L2c, L3, L4, L5, L6, L7, L8, L9, L10a, L10b, L10c, L11 = Value
}

// Enum for B\"uchi automaton states
object States extends ChiselEnum {
  val Init, n3, n5, n6, n13, n16, n19, n20, Trap = Value
}

class BakeryIO extends Bundle {
  val select = Input(UInt(2.W))  // SELMSB+1 bits, SELMSB=1
  val pause = Input(Bool())
  val p = Output(Bool())
  val q = Output(Bool())
  val r1 = Output(Bool())
  val s1 = Output(Bool())
  val r0 = Output(Bool())
  // Add outputs to preserve internal state
  val ticket = Output(UInt(3.W))  // HIPROC+1 bits, HIPROC=2
  val choosing = Output(UInt(3.W))
  val pc0 = Output(Loc())
  val pc1 = Output(Loc())
  val pc2 = Output(Loc())
  val j0 = Output(UInt(2.W))
  val j1 = Output(UInt(2.W))
  val j2 = Output(UInt(2.W))
  val defer0 = Output(UInt(3.W))
  val defer1 = Output(UInt(3.W))
  val defer2 = Output(UInt(3.W))
}

class Bakery extends Module {
  val io = IO(new BakeryIO)
  
  val HIPROC = 2
  val SELMSB = 1
  
  // Internal registers
  val ticket = RegInit(VecInit(Seq.fill(HIPROC + 1)(false.B)))
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
  
  // Wire assignments
  io.p := io.select === 0.U
  io.q := io.select === 1.U && io.pause === false.B
  io.r1 := pc(0) === Loc.L4 && pc(1) === Loc.L9
  io.s1 := pc(0) === Loc.L9
  io.r0 := pc(0) === Loc.L6
  
  // Extract function - returns bit at index
  def extract(in: UInt, index: UInt): Bool = {
    val result = Wire(Bool())
    result := false.B  // Default value
    switch(index) {
      is(0.U) { result := in(0) }
      is(1.U) { result := in(1) }
      is(2.U) { result := in(2) }
    }
    result
  }
  
  // ClearBit function - clears bit at index
  def clearBit(in: UInt, index: UInt): UInt = {
    val result = Wire(UInt(in.getWidth.W))
    result := in
    switch(index) {
      is(0.U) { result := in & ~"b001".U }
      is(1.U) { result := in & ~"b010".U }
      is(2.U) { result := in & ~"b100".U }
    }
    result
  }
  
  // Process logic for selected process
  when(io.select > HIPROC.U) {
    selReg := 0.U
  }.otherwise {
    selReg := io.select
  }
  
  val sel = selReg
  
  // Main process state machine
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
      defSel := defer(sel)
      defer(sel) := Cat(ticket(k), defSel(HIPROC, 1))
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
      defSel := defer(sel)
      defK := defer(k)
      defSelK := extract(defSel, k)
      defKSel := extract(defK, sel)
      when(ticket(k) && (defSelK || (!defKSel && k < sel))) {
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
      defK := defer(k)
      defer(k) := clearBit(defK, sel)
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
  
  // Output internal state for preservation
  io.ticket := Cat(ticket(2), ticket(1), ticket(0))
  io.choosing := Cat(choosing(2), choosing(1), choosing(0))
  io.pc0 := pc(0)
  io.pc1 := pc(1)
  io.pc2 := pc(2)
  io.j0 := j(0)
  io.j1 := j(1)
  io.j2 := j(2)
  io.defer0 := defer(0)
  io.defer1 := defer(1)
  io.defer2 := defer(2)
}

class BuechiIO extends Bundle {
  val r0 = Input(Bool())
  val s1 = Input(Bool())
  val r1 = Input(Bool())
  val q = Input(Bool())
  val p = Input(Bool())
  val fair0 = Output(Bool())
  val fair1 = Output(Bool())
  val scc = Output(Bool())
  // Add output to preserve state
  val state = Output(States())
}

class Buechi extends Module {
  val io = IO(new BuechiIO)
  
  val state = RegInit(States.Init)
  
  // Non-deterministic choices - in Chisel we use Mux for simplicity
  // In real verification, these would be handled by the model checker
  val ND_n19_n5 = Wire(States())
  val ND_n3_n6 = Wire(States())
  val ND_n16_n3 = Wire(States())
  val ND_n13_n19_n20 = Wire(States())
  val ND_n16_n3_n6 = Wire(States())
  val ND_n13_n19 = Wire(States())
  val ND_n13_n20 = Wire(States())
  val ND_n13_n19_n5 = Wire(States())
  
  // Simple implementation of non-determinism using Mux
  // Using a simple pattern to make choices deterministic for synthesis
  ND_n19_n5 := Mux(state === States.n19, States.n5, States.n19)
  ND_n3_n6 := Mux(state === States.n3, States.n6, States.n3)
  ND_n16_n3 := Mux(state === States.n16, States.n3, States.n16)
  ND_n13_n19_n20 := Mux(state === States.n13, States.n20, Mux(state === States.n19, States.n19, States.n13))
  ND_n16_n3_n6 := Mux(state === States.n16, States.n6, Mux(state === States.n3, States.n3, States.n16))
  ND_n13_n19 := Mux(state === States.n13, States.n19, States.n13)
  ND_n13_n20 := Mux(state === States.n13, States.n20, States.n13)
  ND_n13_n19_n5 := Mux(state === States.n13, States.n5, Mux(state === States.n19, States.n19, States.n13))
  
  io.fair0 := state === States.n16
  io.fair1 := state === States.n6
  io.scc := state === States.n6 || state === States.n16 || state === States.n3
  
  // State transition logic
  switch(state) {
    is(States.n19) {
      switch(Cat(io.r0, io.r1, io.s1)) {
        is("b000".U) { state := States.n19 }
        is("b010".U) { state := ND_n19_n5 }
        is("b011".U) { state := States.n19 }
        is("b100".U) { state := States.Trap }
        is("b101".U) { state := States.Trap }
        is("b110".U) { state := States.Trap }
        is("b111".U) { state := States.Trap }
      }
    }
    is(States.n20) {
      switch(Cat(io.r0, io.r1, io.s1)) {
        is("b000".U) { state := States.n19 }
        is("b010".U) { state := ND_n19_n5 }
        is("b011".U) { state := States.n19 }
        is("b100".U) { state := States.Trap }
        is("b101".U) { state := States.Trap }
        is("b110".U) { state := States.Trap }
        is("b111".U) { state := States.Trap }
      }
    }
    is(States.Trap) {
      state := States.Trap
    }
    is(States.n5) {
      switch(Cat(io.r0, io.s1)) {
        is("b00".U) { state := States.n3 }
        is("b01".U) { state := States.Trap }
        is("b10".U) { state := States.Trap }
        is("b11".U) { state := States.Trap }
      }
    }
    is(States.Init) {
      switch(Cat(io.r0, io.r1, io.s1)) {
        is("b000".U) { state := ND_n13_n19 }
        is("b001".U) { state := ND_n13_n19_n20 }
        is("b011".U) { state := ND_n13_n19_n20 }
        is("b010".U) { state := ND_n13_n19_n5 }
        is("b100".U) { state := States.n13 }
        is("b101".U) { state := ND_n13_n20 }
        is("b110".U) { state := States.n13 }
        is("b111".U) { state := ND_n13_n20 }
      }
    }
    is(States.n13) {
      when(io.s1) {
        state := ND_n13_n20
      }.otherwise {
        state := States.n13
      }
    }
    is(States.n3) {
      switch(Cat(io.p, io.q, io.r0, io.s1)) {
        is("b0000".U) { state := States.n3 }
        is("b0001".U) { state := States.Trap }
        is("b0011".U) { state := States.Trap }
        is("b0100".U) { state := ND_n3_n6 }
        is("b0101".U) { state := States.Trap }
        is("b0111".U) { state := States.Trap }
        is("b1000".U) { state := ND_n16_n3 }
        is("b1001".U) { state := States.Trap }
        is("b1011".U) { state := States.Trap }
        is("b1100".U) { state := ND_n16_n3_n6 }
        is("b1101".U) { state := States.Trap }
        is("b1111".U) { state := States.Trap }
      }
    }
    is(States.n6) {
      switch(Cat(io.p, io.q, io.r0, io.s1)) {
        is("b0000".U) { state := States.n3 }
        is("b0001".U) { state := States.Trap }
        is("b0011".U) { state := States.Trap }
        is("b0100".U) { state := ND_n3_n6 }
        is("b0101".U) { state := States.Trap }
        is("b0111".U) { state := States.Trap }
        is("b1000".U) { state := ND_n16_n3 }
        is("b1001".U) { state := States.Trap }
        is("b1011".U) { state := States.Trap }
        is("b1100".U) { state := ND_n16_n3_n6 }
        is("b1101".U) { state := States.Trap }
        is("b1111".U) { state := States.Trap }
      }
    }
    is(States.n16) {
      switch(Cat(io.p, io.q, io.r0, io.s1)) {
        is("b0000".U) { state := States.n3 }
        is("b0001".U) { state := States.Trap }
        is("b0011".U) { state := States.Trap }
        is("b0100".U) { state := ND_n3_n6 }
        is("b0101".U) { state := States.Trap }
        is("b0111".U) { state := States.Trap }
        is("b1000".U) { state := ND_n16_n3 }
        is("b1001".U) { state := States.Trap }
        is("b1011".U) { state := States.Trap }
        is("b1100".U) { state := ND_n16_n3_n6 }
        is("b1101".U) { state := States.Trap }
        is("b1111".U) { state := States.Trap }
      }
    }
  }
  
  io.state := state
}

// Top-level module that combines both
class BakeryTop extends Module {
  val io = IO(new Bundle {
    val select = Input(UInt(2.W))
    val pause = Input(Bool())
    val fair0 = Output(Bool())
    val fair1 = Output(Bool())
    val scc = Output(Bool())
    // Additional outputs for debugging
    val p = Output(Bool())
    val q = Output(Bool())
    val r1 = Output(Bool())
    val s1 = Output(Bool())
    val r0 = Output(Bool())
  })
  
  val bakery = Module(new Bakery)
  val buechi = Module(new Buechi)
  
  bakery.io.select := io.select
  bakery.io.pause := io.pause
  
  buechi.io.r0 := bakery.io.r0
  buechi.io.s1 := bakery.io.s1
  buechi.io.r1 := bakery.io.r1
  buechi.io.q := bakery.io.q
  buechi.io.p := bakery.io.p
  
  io.fair0 := buechi.io.fair0
  io.fair1 := buechi.io.fair1
  io.scc := buechi.io.scc
  io.p := bakery.io.p
  io.q := bakery.io.q
  io.r1 := bakery.io.r1
  io.s1 := bakery.io.s1
  io.r0 := bakery.io.r0
}

object VerilogGenerator extends App {
  emitVerilog(new BakeryTop(), args)
}