package llmverify
import chisel3._
import chisel3.util._
import chiselFv._

// Define enums for locations and states
object Loc extends ChiselEnum {
  val L0, L1, L2, L3, L4, L5, L6, L7 = Value
}

object States extends ChiselEnum {
  val n2, n8, n12, n16, n18, n20, n21, n22, n23, n26, n27, n32, n33, n34, n36, n42, n45, Trap = Value
}

class Buechi extends Module {
  val io = IO(new Bundle {
    val pc2L6 = Input(Bool())
    val pc1L6 = Input(Bool())
    val pc1L0 = Input(Bool())
    val pc0L6 = Input(Bool())
    val interested0is1 = Input(Bool())
    val pc2L0 = Input(Bool())
    val fair0 = Output(Bool())
    val fair1 = Output(Bool())
    val fair2 = Output(Bool())
    val fair3 = Output(Bool())
    val scc = Output(Bool())
  })
  
  val state = RegInit(States.n32)
  
  // Helper function for nondeterministic choice - simplified for Chisel
  // In real verification, this would need proper nondeterministic handling
  def ND(states: States.Type*): States.Type = {
    // Simplified: just return the first state
    // Real nondeterminism would require more complex implementation
    states.head
  }
  
  // Wire assignments for nondeterministic choices
  val ND_n21_n22 = Wire(States())
  val ND_n18_n20_n22_n23_n27_n33_n34_n36 = Wire(States())
  val ND_n21_n22_n36_n45 = Wire(States())
  val ND_n2_n21_n22_n26_n27_n34_n36_n45 = Wire(States())
  val ND_n22_n23 = Wire(States())
  val ND_n20_n22_n23_n34 = Wire(States())
  val ND_n18_n22_n23_n36 = Wire(States())
  val ND_n22_n34 = Wire(States())
  val ND_n22_n27_n34_n36 = Wire(States())
  val ND_n12_n16_n20_n21_n22_n23_n26_n34 = Wire(States())
  val ND_n32_n8 = Wire(States())
  val ND_n22_n36 = Wire(States())
  val ND_n12_n18_n21_n22_n23_n36_n42_n45 = Wire(States())
  val ND_n21_n22_n26_n34 = Wire(States())
  val ND_n12_n21_n22_n23 = Wire(States())
  val ND_n12_n16_n18_n2_n20_n21_n22_n23_n26_n27_n33_n34_n36_n42_n45 = Wire(States())
  
  // Assign nondeterministic choices
  ND_n21_n22 := ND(States.n21, States.n22)
  ND_n18_n20_n22_n23_n27_n33_n34_n36 := ND(States.n18, States.n20, States.n22, States.n23, States.n27, States.n33, States.n34, States.n36)
  ND_n21_n22_n36_n45 := ND(States.n21, States.n22, States.n36, States.n45)
  ND_n2_n21_n22_n26_n27_n34_n36_n45 := ND(States.n2, States.n21, States.n22, States.n26, States.n27, States.n34, States.n36, States.n45)
  ND_n22_n23 := ND(States.n22, States.n23)
  ND_n20_n22_n23_n34 := ND(States.n20, States.n22, States.n23, States.n34)
  ND_n18_n22_n23_n36 := ND(States.n18, States.n22, States.n23, States.n36)
  ND_n22_n34 := ND(States.n22, States.n34)
  ND_n22_n27_n34_n36 := ND(States.n22, States.n27, States.n34, States.n36)
  ND_n12_n16_n20_n21_n22_n23_n26_n34 := ND(States.n12, States.n16, States.n20, States.n21, States.n22, States.n23, States.n26, States.n34)
  ND_n32_n8 := ND(States.n32, States.n8)
  ND_n22_n36 := ND(States.n22, States.n36)
  ND_n12_n18_n21_n22_n23_n36_n42_n45 := ND(States.n12, States.n18, States.n21, States.n22, States.n23, States.n36, States.n42, States.n45)
  ND_n21_n22_n26_n34 := ND(States.n21, States.n22, States.n26, States.n34)
  ND_n12_n21_n22_n23 := ND(States.n12, States.n21, States.n22, States.n23)
  ND_n12_n16_n18_n2_n20_n21_n22_n23_n26_n27_n33_n34_n36_n42_n45 := ND(States.n12, States.n16, States.n18, States.n2, States.n20, States.n21, States.n22, States.n23, States.n26, States.n27, States.n33, States.n34, States.n36, States.n42, States.n45)
  
  // Output assignments
  io.fair0 := (state === States.n33) || (state === States.n12) || (state === States.n16) || (state === States.n18) || (state === States.n42) || (state === States.n20) || (state === States.n23)
  io.fair1 := (state === States.n2) || (state === States.n33) || (state === States.n36) || (state === States.n18) || (state === States.n42) || (state === States.n45) || (state === States.n27)
  io.fair2 := (state === States.n2) || (state === States.n33) || (state === States.n34) || (state === States.n16) || (state === States.n20) || (state === States.n26) || (state === States.n27)
  io.fair3 := (state === States.n2) || (state === States.n12) || (state === States.n16) || (state === States.n42) || (state === States.n21) || (state === States.n45) || (state === States.n26)
  io.scc := (state === States.n20) || (state === States.n2) || (state === States.n21) || (state === States.n12) || (state === States.n22) || (state === States.n23) || (state === States.n33) || (state === States.n42) || (state === States.n34) || (state === States.n16) || (state === States.n26) || (state === States.n27) || (state === States.n45) || (state === States.n36) || (state === States.n18)
  
  // State machine logic
  when(state.isOneOf(States.n2, States.n12, States.n16, States.n18, States.n20, States.n21, States.n22, States.n23, States.n26, States.n27, States.n33, States.n34, States.n36, States.n42, States.n45)) {
    val inputVec = Cat(io.pc0L6, io.pc1L0, io.pc1L6, io.pc2L0, io.pc2L6)
    
    // Check for wildcard pattern 1???? first (MSB is 1)
    when(inputVec(4)) {
      state := States.Trap
    }.otherwise {
      switch(inputVec) {
        is("b00000".U) { state := ND_n12_n16_n18_n2_n20_n21_n22_n23_n26_n27_n33_n34_n36_n42_n45 }
        is("b00001".U) { state := ND_n12_n18_n21_n22_n23_n36_n42_n45 }
        is("b00010".U) { state := ND_n2_n21_n22_n26_n27_n34_n36_n45 }
        is("b00011".U) { state := ND_n21_n22_n36_n45 }
        is("b00100".U) { state := ND_n12_n16_n20_n21_n22_n23_n26_n34 }
        is("b00101".U) { state := ND_n12_n21_n22_n23 }
        is("b00110".U) { state := ND_n21_n22_n26_n34 }
        is("b00111".U) { state := ND_n21_n22 }
        is("b01000".U) { state := ND_n18_n20_n22_n23_n27_n33_n34_n36 }
        is("b01001".U) { state := ND_n18_n22_n23_n36 }
        is("b01010".U) { state := ND_n22_n27_n34_n36 }
        is("b01011".U) { state := ND_n22_n36 }
        is("b01100".U) { state := ND_n20_n22_n23_n34 }
        is("b01101".U) { state := ND_n22_n23 }
        is("b01110".U) { state := ND_n22_n34 }
        is("b01111".U) { state := States.n22 }
      }
    }
  }.elsewhen(state === States.Trap) {
    state := States.Trap
  }.elsewhen(state === States.n8) {
    when(io.pc0L6) {
      state := States.Trap
    }.otherwise {
      state := States.n22
    }
  }.elsewhen(state === States.n32) {
    val inputVec = Cat(io.interested0is1, io.pc0L6)
    switch(inputVec) {
      is("b00".U) { state := States.n32 }
      is("b01".U) { state := States.n32 }
      is("b10".U) { state := ND_n32_n8 }
      is("b11".U) { state := States.n32 }
    }
  }
}

class Peterson extends Module with Formal {
  val SELMSB = 2
  val HIPROC = 7
  
  val io = IO(new Bundle {
    val select = Input(UInt((SELMSB + 1).W))
    val pause = Input(Bool())
    // Expose internal signals for verification
    val pc0 = Output(Loc())
    val pc1 = Output(Loc())
    val pc2 = Output(Loc())
    val interested0 = Output(Bool())
    val turn = Output(UInt((SELMSB + 1).W))
    val fair0 = Output(Bool())
    val fair1 = Output(Bool())
    val fair2 = Output(Bool())
    val fair3 = Output(Bool())
    val scc = Output(Bool())
  })
  
  // Internal registers
  val interested = RegInit(VecInit(Seq.fill(HIPROC + 1)(false.B)))
  val turn = RegInit(0.U((SELMSB + 1).W))
  val self = RegInit(0.U((SELMSB + 1).W))
  val k = RegInit(0.U((SELMSB + 1).W))
  val pc = RegInit(VecInit(Seq.fill(HIPROC + 1)(Loc.L0)))
  val j = RegInit(VecInit(Seq.fill(HIPROC + 1)(0.U((SELMSB + 1).W))))
  
  // Wire assignments
  val pc2L6 = Wire(Bool())
  val pc1L6 = Wire(Bool())
  val pc1L0 = Wire(Bool())
  val pc0L6 = Wire(Bool())
  val interested0is1 = Wire(Bool())
  val pc2L0 = Wire(Bool())
  
  pc2L6 := pc(2) === Loc.L6
  pc1L6 := pc(1) === Loc.L6
  pc1L0 := pc(1) === Loc.L0
  pc0L6 := pc(0) === Loc.L6
  interested0is1 := interested(0) === true.B
  pc2L0 := pc(2) === Loc.L0
  
  // Instantiate Buechi module
  val buechi = Module(new Buechi())
  buechi.io.pc2L6 := pc2L6
  buechi.io.pc1L6 := pc1L6
  buechi.io.pc1L0 := pc1L0
  buechi.io.pc0L6 := pc0L6
  buechi.io.interested0is1 := interested0is1
  buechi.io.pc2L0 := pc2L0
  
  // Output assignments
  io.pc0 := pc(0)
  io.pc1 := pc(1)
  io.pc2 := pc(2)
  io.interested0 := interested(0)
  io.turn := turn
  io.fair0 := buechi.io.fair0
  io.fair1 := buechi.io.fair1
  io.fair2 := buechi.io.fair2
  io.fair3 := buechi.io.fair3
  io.scc := buechi.io.scc
  
  // Update self based on select
  when(io.select > HIPROC.U) {
    self := 0.U
  }.otherwise {
    self := io.select
  }
  
  // State machine logic for current self
  switch(pc(self)) {
    is(Loc.L0) {
      when(io.pause) {
        pc(self) := Loc.L0
      }.otherwise {
        pc(self) := Loc.L1
      }
    }
    is(Loc.L1) {
      interested(self) := true.B
      pc(self) := Loc.L2
    }
    is(Loc.L2) {
      when(self === 0.U) {
        turn := HIPROC.U
      }.otherwise {
        turn := self - 1.U
      }
      pc(self) := Loc.L3
    }
    is(Loc.L3) {
      when(self === HIPROC.U) {
        j(self) := 0.U
      }.otherwise {
        j(self) := self + 1.U
      }
      pc(self) := Loc.L4
    }
    is(Loc.L4) {
      when(j(self) === self) {
        pc(self) := Loc.L6
      }.otherwise {
        pc(self) := Loc.L5
      }
    }
    is(Loc.L5) {
      k := j(self)
      when(interested(k) && (turn === k)) {
        pc(self) := Loc.L5
      }.otherwise {
        pc(self) := Loc.L4
      }
    }
    is(Loc.L6) {
      when(io.pause && (self === 0.U || self === 1.U || self === 2.U)) {
        pc(self) := Loc.L6
      }.otherwise {
        pc(self) := Loc.L7
      }
    }
    is(Loc.L7) {
      interested(self) := false.B
      pc(self) := Loc.L0
    }
  }

  // ========== FORMAL VERIFICATION ASSERTIONS ==========
  // All assertions are placed directly in the Peterson class emitted by VerilogGenerator

  // SAFETY 1: Mutual Exclusion - at most one process in critical section (L6)
  fvAssert(PopCount(Seq(pc(0) === Loc.L6, pc(1) === Loc.L6, pc(2) === Loc.L6)) <= 1.U,
    "mutual_exclusion_at_most_one_process_in_CS")

  // SAFETY 2: Program counters always in valid location range
  fvAssert(pc(0).isOneOf(Loc.L0, Loc.L1, Loc.L2, Loc.L3, Loc.L4, Loc.L5, Loc.L6, Loc.L7),
    "pc0_valid_state")
  fvAssert(pc(1).isOneOf(Loc.L0, Loc.L1, Loc.L2, Loc.L3, Loc.L4, Loc.L5, Loc.L6, Loc.L7),
    "pc1_valid_state")
  fvAssert(pc(2).isOneOf(Loc.L0, Loc.L1, Loc.L2, Loc.L3, Loc.L4, Loc.L5, Loc.L6, Loc.L7),
    "pc2_valid_state")

  // CONSISTENCY: In critical section (L6), the process must be interested
  fvAssert(!(pc(0) === Loc.L6) || interested(0), "p0_in_CS_must_be_interested")
  fvAssert(!(pc(1) === Loc.L6) || interested(1), "p1_in_CS_must_be_interested")
  fvAssert(!(pc(2) === Loc.L6) || interested(2), "p2_in_CS_must_be_interested")

  // CONSISTENCY: In idle (L0), the process must not be interested
  fvAssert(!(pc(0) === Loc.L0) || !interested(0), "p0_idle_must_not_be_interested")
  fvAssert(!(pc(1) === Loc.L0) || !interested(1), "p1_idle_must_not_be_interested")
  fvAssert(!(pc(2) === Loc.L0) || !interested(2), "p2_idle_must_not_be_interested")

  // INTEREST LIFECYCLE: When the scheduled process enters L1, interested(self) must be set next cycle
  // Use explicit RegNext + fvAssert implication to avoid FIRRTL dropping the when-block antecedent
  val p0_L1_to_set = RegNext(pc(self) === Loc.L1 && self === 0.U, false.B)
  fvAssert(!p0_L1_to_set || interested(0), "p0_interested_set_after_L1")

  val p1_L1_to_set = RegNext(pc(self) === Loc.L1 && self === 1.U, false.B)
  fvAssert(!p1_L1_to_set || interested(1), "p1_interested_set_after_L1")

  val p2_L1_to_set = RegNext(pc(self) === Loc.L1 && self === 2.U, false.B)
  fvAssert(!p2_L1_to_set || interested(2), "p2_interested_set_after_L1")

  // INTEREST LIFECYCLE: When the scheduled process enters L7, interested(self) must be cleared next cycle
  // Use explicit RegNext + fvAssert implication to avoid FIRRTL dropping the when-block antecedent
  val p0_L7_to_clear = RegNext(pc(self) === Loc.L7 && self === 0.U, false.B)
  fvAssert(!p0_L7_to_clear || !interested(0), "p0_interested_cleared_after_L7")

  val p1_L7_to_clear = RegNext(pc(self) === Loc.L7 && self === 1.U, false.B)
  fvAssert(!p1_L7_to_clear || !interested(1), "p1_interested_cleared_after_L7")

  val p2_L7_to_clear = RegNext(pc(self) === Loc.L7 && self === 2.U, false.B)
  fvAssert(!p2_L7_to_clear || !interested(2), "p2_interested_cleared_after_L7")
}

object VerilogGenerator extends App {
  emitVerilog(new Peterson(), args)
}
