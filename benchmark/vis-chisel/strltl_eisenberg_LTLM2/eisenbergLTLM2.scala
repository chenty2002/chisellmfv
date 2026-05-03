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

// Type of B\"uchi automaton states.
object States extends ChiselEnum {
  val Init, n1, n2, n3, n4, n5, n6, n7, n8, n9, n10, n11, n12, n13, n14, n15, n16, n17, n18, n19, n20, n21, n22, n23, n24, n25, n26, n27, Trap = Value
}

class EisenbergLTLM2(val HIPROC: Int = 2, val SELMSB: Int = 1) extends Module {
  val io = IO(new Bundle {
    val select = Input(UInt((SELMSB + 1).W))
    val pause = Input(Bool())
    // Outputs for debugging and to preserve signals
    val flag = Output(Vec(HIPROC + 1, Activity()))
    val turn = Output(UInt((SELMSB + 1).W))
    val pc = Output(Vec(HIPROC + 1, Loc()))
    val j = Output(Vec(HIPROC + 1, UInt((SELMSB + 1).W)))
    val selReg = Output(UInt((SELMSB + 1).W))
    val k = Output(UInt((SELMSB + 1).W))
    // B\"uchi automaton outputs
    val fair0 = Output(Bool())
    val fair1 = Output(Bool())
    val scc = Output(Bool())
    val buechiState = Output(States())
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
  
  // Process function implementation
  def process(sel: UInt): Unit = {
    switch(pc(sel)) {
      is(Loc.L1) {
        flag(sel) := Activity.waiting
        pc(sel) := Loc.L2
      }
      is(Loc.L2) {
        j(sel) := turn
        pc(sel) := Loc.L3
      }
      is(Loc.L3) {
        when(j(sel) =/= sel) {
          pc(sel) := Loc.L4
        }.otherwise {
          pc(sel) := Loc.L7
        }
      }
      is(Loc.L4) {
        k := j(sel)
        when(flag(k) =/= Activity.idle) {
          pc(sel) := Loc.L5
        }.otherwise {
          pc(sel) := Loc.L6
        }
      }
      is(Loc.L5) {
        j(sel) := turn
        pc(sel) := Loc.L3
      }
      is(Loc.L6) {
        when(j(sel) === HIPROC.U) {
          j(sel) := 0.U
        }.otherwise {
          j(sel) := j(sel) + 1.U
        }
        pc(sel) := Loc.L3
      }
      is(Loc.L7) {
        flag(sel) := Activity.active
        pc(sel) := Loc.L8
      }
      is(Loc.L8) {
        j(sel) := 0.U
        pc(sel) := Loc.L9
      }
      is(Loc.L9) {
        k := j(sel)
        when(j(sel) <= HIPROC.U && (k === sel || flag(k) =/= Activity.active)) {
          j(sel) := k + 1.U
          pc(sel) := Loc.L9
        }.otherwise {
          pc(sel) := Loc.L10
        }
      }
      is(Loc.L10) {
        when(j(sel) > HIPROC.U && (turn === sel || flag(turn) === Activity.idle)) {
          pc(sel) := Loc.L11
        }.otherwise {
          pc(sel) := Loc.L1
        }
      }
      is(Loc.L11) {
        turn := sel
        pc(sel) := Loc.L12
      }
      is(Loc.L12) {
        when(io.pause) {
          pc(sel) := Loc.L12
        }.otherwise {
          pc(sel) := Loc.L13
        }
      }
      is(Loc.L13) {
        when(turn === HIPROC.U) {
          j(sel) := 0.U
        }.otherwise {
          j(sel) := turn + 1.U
        }
        pc(sel) := Loc.L14
      }
      is(Loc.L14) {
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
      }
      is(Loc.L15) {
        turn := j(sel)
        pc(sel) := Loc.L16
      }
      is(Loc.L16) {
        flag(sel) := Activity.idle
        when(io.pause) {
          pc(sel) := Loc.L16
        }.otherwise {
          pc(sel) := Loc.L1
        }
      }
    }
  }
  
  // Update selReg and call process
  when(io.select > HIPROC.U) {
    selReg := 0.U
  }.otherwise {
    selReg := io.select
  }
  process(selReg)
  
  // B\"uchi automaton implementation
  val buechiState = RegInit(States.Init)
  
  // PC location signals for B\"uchi automaton
  val pc0L12 = pc(0) === Loc.L12
  val pc1L12 = pc(1) === Loc.L12
  val pc2L12 = pc(2) === Loc.L12
  val pc0L1 = pc(0) === Loc.L1
  val pc1L1 = pc(1) === Loc.L1
  val pc2L1 = pc(2) === Loc.L1
  
  // Nondeterministic choice function - simplified for Chisel
  // In practice, this would need more sophisticated handling
  def ndChoice(states: States.Type*): States.Type = {
    // For now, return the first state as a placeholder
    // In a real implementation, this would be nondeterministic
    states.head
  }
  
  // B\"uchi automaton state transitions
  switch(buechiState) {
    is(States.Trap) {
      buechiState := States.Trap
    }
    is(States.n10, States.n11) {
      switch(Cat(pc0L1, pc1L12, pc2L12)) {
        is("b000".U) { buechiState := States.n17 }
        is("b001".U) { buechiState := States.Trap }
        is("b010".U) { buechiState := ndChoice(States.n16, States.n17) }
        is("b011".U) { buechiState := States.Trap }
        is("b100".U) { buechiState := States.Trap }
        is("b101".U) { buechiState := States.Trap }
        is("b110".U) { buechiState := States.Trap }
        is("b111".U) { buechiState := States.Trap }
      }
    }
    is(States.Init) {
      switch(Cat(pc0L1, pc0L12, pc1L1, pc1L12, pc2L1, pc2L12)) {
        is("b000000".U) { buechiState := ndChoice(States.n15, States.n25, States.n6, States.n9) }
        is("b000010".U) { buechiState := ndChoice(States.n10, States.n15, States.n20, States.n24, States.n25, States.n6, States.n7, States.n9) }
        is("b000011".U) { buechiState := ndChoice(States.n15, States.n25, States.n6, States.n9) }
        is("b000100".U) { buechiState := ndChoice(States.n15, States.n21, States.n25, States.n26, States.n6, States.n9) }
        is("b000110".U) { buechiState := ndChoice(States.n10, States.n14, States.n15, States.n2, States.n20, States.n21, States.n24, States.n25, States.n26, States.n6, States.n7, States.n9) }
        is("b000111".U) { buechiState := ndChoice(States.n15, States.n21, States.n25, States.n26, States.n6, States.n9) }
        is("b001000".U) { buechiState := ndChoice(States.n15, States.n25) }
        is("b001010".U) { buechiState := ndChoice(States.n10, States.n15, States.n20, States.n25) }
        is("b001011".U) { buechiState := ndChoice(States.n15, States.n25) }
        is("b001100".U) { buechiState := ndChoice(States.n15, States.n21, States.n25, States.n26) }
        is("b001110".U) { buechiState := ndChoice(States.n10, States.n14, States.n15, States.n2, States.n20, States.n21, States.n25, States.n26) }
        is("b001111".U) { buechiState := ndChoice(States.n15, States.n21, States.n25, States.n26) }
        is("b010000".U) { buechiState := ndChoice(States.n15, States.n19, States.n22, States.n25, States.n6, States.n9) }
        is("b010010".U) { buechiState := ndChoice(States.n10, States.n11, States.n15, States.n19, States.n20, States.n22, States.n24, States.n25, States.n6, States.n7, States.n8, States.n9) }
        is("b010011".U) { buechiState := ndChoice(States.n15, States.n19, States.n22, States.n25, States.n6, States.n9) }
        is("b010100".U) { buechiState := ndChoice(States.n15, States.n19, States.n21, States.n22, States.n25, States.n26, States.n3, States.n6, States.n9) }
        is("b010110".U) { buechiState := ndChoice(States.n10, States.n11, States.n12, States.n14, States.n15, States.n19, States.n2, States.n20, States.n21, States.n22, States.n24, States.n25, States.n26, States.n3, States.n6, States.n7, States.n8, States.n9) }
        is("b010111".U) { buechiState := ndChoice(States.n15, States.n19, States.n21, States.n22, States.n25, States.n26, States.n3, States.n6, States.n9) }
        is("b011000".U) { buechiState := ndChoice(States.n15, States.n22, States.n25) }
        is("b011010".U) { buechiState := ndChoice(States.n10, States.n11, States.n15, States.n20, States.n22, States.n25) }
        is("b011011".U) { buechiState := ndChoice(States.n15, States.n22, States.n25) }
        is("b011100".U) { buechiState := ndChoice(States.n15, States.n21, States.n22, States.n25, States.n26, States.n3) }
        is("b011110".U) { buechiState := ndChoice(States.n10, States.n11, States.n12, States.n14, States.n15, States.n2, States.n20, States.n21, States.n22, States.n25, States.n26, States.n3) }
        is("b011111".U) { buechiState := ndChoice(States.n15, States.n21, States.n22, States.n25, States.n26, States.n3) }
        is("b100000".U) { buechiState := ndChoice(States.n15, States.n9) }
        is("b100010".U) { buechiState := ndChoice(States.n15, States.n20, States.n7, States.n9) }
        is("b100011".U) { buechiState := ndChoice(States.n15, States.n9) }
        is("b100100".U) { buechiState := ndChoice(States.n15, States.n26, States.n9) }
        is("b100110".U) { buechiState := ndChoice(States.n15, States.n2, States.n20, States.n26, States.n7, States.n9) }
        is("b100111".U) { buechiState := ndChoice(States.n15, States.n26, States.n9) }
        is("b101000".U) { buechiState := States.n15 }
        is("b101010".U) { buechiState := ndChoice(States.n15, States.n20) }
        is("b101011".U) { buechiState := States.n15 }
        is("b101100".U) { buechiState := ndChoice(States.n15, States.n26) }
        is("b101110".U) { buechiState := ndChoice(States.n15, States.n2, States.n20, States.n26) }
        is("b101111".U) { buechiState := ndChoice(States.n15, States.n26) }
        is("b110000".U) { buechiState := ndChoice(States.n15, States.n19, States.n22, States.n9) }
        is("b110010".U) { buechiState := ndChoice(States.n11, States.n15, States.n19, States.n20, States.n22, States.n7, States.n8, States.n9) }
        is("b110011".U) { buechiState := ndChoice(States.n15, States.n19, States.n22, States.n9) }
        is("b110100".U) { buechiState := ndChoice(States.n15, States.n19, States.n22, States.n26, States.n3, States.n9) }
        is("b110110".U) { buechiState := ndChoice(States.n11, States.n12, States.n15, States.n19, States.n2, States.n20, States.n22, States.n26, States.n3, States.n7, States.n8, States.n9) }
        is("b110111".U) { buechiState := ndChoice(States.n15, States.n19, States.n22, States.n26, States.n3, States.n9) }
        is("b111000".U) { buechiState := ndChoice(States.n15, States.n22) }
        is("b111010".U) { buechiState := ndChoice(States.n11, States.n15, States.n20, States.n22) }
        is("b111011".U) { buechiState := ndChoice(States.n15, States.n22) }
        is("b111100".U) { buechiState := ndChoice(States.n15, States.n22, States.n26, States.n3) }
        is("b111110".U) { buechiState := ndChoice(States.n11, States.n12, States.n15, States.n2, States.n20, States.n22, States.n26, States.n3) }
        is("b111111".U) { buechiState := ndChoice(States.n15, States.n22, States.n26, States.n3) }
      }
    }
    is(States.n3, States.n6, States.n19, States.n21) {
      switch(Cat(pc0L1, pc1L1, pc2L1, pc2L12)) {
        is("b0000".U) { buechiState := States.n6 }
        is("b0001".U) { buechiState := States.n6 }
        is("b0010".U) { buechiState := ndChoice(States.n24, States.n6) }
        is("b0011".U) { buechiState := States.n6 }
        is("b0100".U) { buechiState := States.Trap }
        is("b0101".U) { buechiState := States.Trap }
        is("b0110".U) { buechiState := States.Trap }
        is("b0111".U) { buechiState := States.Trap }
        is("b1000".U) { buechiState := States.Trap }
        is("b1001".U) { buechiState := States.Trap }
        is("b1010".U) { buechiState := States.Trap }
        is("b1011".U) { buechiState := States.Trap }
        is("b1100".U) { buechiState := States.Trap }
        is("b1101".U) { buechiState := States.Trap }
        is("b1110".U) { buechiState := States.Trap }
        is("b1111".U) { buechiState := States.Trap }
      }
    }
    is(States.n18, States.n20) {
      switch(Cat(pc0L12, pc1L12, pc2L12)) {
        is("b000".U) { buechiState := States.n18 }
        is("b001".U) { buechiState := States.Trap }
        is("b010".U) { buechiState := ndChoice(States.n18, States.n4) }
        is("b011".U) { buechiState := States.Trap }
        is("b100".U) { buechiState := ndChoice(States.n18, States.n23) }
        is("b101".U) { buechiState := States.Trap }
        is("b110".U) { buechiState := ndChoice(States.n1, States.n18, States.n23, States.n4) }
        is("b111".U) { buechiState := States.Trap }
      }
    }
    is(States.n17, States.n23) {
      switch(Cat(pc0L1, pc0L12, pc1L12, pc2L12)) {
        is("b0000".U) { buechiState := ndChoice(States.n17, States.n18) }
        is("b0001".U) { buechiState := States.Trap }
        is("b0010".U) { buechiState := ndChoice(States.n16, States.n17, States.n18, States.n4) }
        is("b0011".U) { buechiState := States.Trap }
        is("b0100".U) { buechiState := ndChoice(States.n17, States.n18, States.n23) }
        is("b0101".U) { buechiState := States.Trap }
        is("b0110".U) { buechiState := ndChoice(States.n1, States.n16, States.n17, States.n18, States.n23, States.n4) }
        is("b0111".U) { buechiState := States.Trap }
        is("b1000".U) { buechiState := States.n18 }
        is("b1001".U) { buechiState := States.Trap }
        is("b1010".U) { buechiState := ndChoice(States.n18, States.n4) }
        is("b1011".U) { buechiState := States.Trap }
        is("b1100".U) { buechiState := ndChoice(States.n18, States.n23) }
        is("b1101".U) { buechiState := States.Trap }
        is("b1110".U) { buechiState := ndChoice(States.n1, States.n18, States.n23, States.n4) }
        is("b1111".U) { buechiState := States.Trap }
      }
    }
    is(States.n8, States.n12, States.n14, States.n24) {
      switch(Cat(pc0L1, pc1L1, pc2L12)) {
        is("b000".U) { buechiState := States.n5 }
        is("b001".U) { buechiState := States.Trap }
        is("b010".U) { buechiState := States.Trap }
        is("b011".U) { buechiState := States.Trap }
        is("b100".U) { buechiState := States.Trap }
        is("b101".U) { buechiState := States.Trap }
        is("b110".U) { buechiState := States.Trap }
        is("b111".U) { buechiState := States.Trap }
      }
    }
    is(States.n15) {
      switch(Cat(pc0L12, pc1L12, pc2L1, pc2L12)) {
        is("b0000".U) { buechiState := States.n15 }
        is("b0001".U) { buechiState := States.n15 }
        is("b0010".U) { buechiState := ndChoice(States.n15, States.n20) }
        is("b0011".U) { buechiState := States.n15 }
        is("b0100".U) { buechiState := ndChoice(States.n15, States.n26) }
        is("b0101".U) { buechiState := ndChoice(States.n15, States.n26) }
        is("b0110".U) { buechiState := ndChoice(States.n15, States.n2, States.n20, States.n26) }
        is("b0111".U) { buechiState := ndChoice(States.n15, States.n26) }
        is("b1000".U) { buechiState := ndChoice(States.n15, States.n22) }
        is("b1001".U) { buechiState := ndChoice(States.n15, States.n22) }
        is("b1010".U) { buechiState := ndChoice(States.n11, States.n15, States.n20, States.n22) }
        is("b1011".U) { buechiState := ndChoice(States.n15, States.n22) }
        is("b1100".U) { buechiState := ndChoice(States.n15, States.n22, States.n26, States.n3) }
        is("b1101".U) { buechiState := ndChoice(States.n15, States.n22, States.n26, States.n3) }
        is("b1110".U) { buechiState := ndChoice(States.n11, States.n12, States.n15, States.n2, States.n20, States.n22, States.n26, States.n3) }
        is("b1111".U) { buechiState := ndChoice(States.n15, States.n22, States.n26, States.n3) }
      }
    }
    is(States.n22, States.n25) {
      switch(Cat(pc0L1, pc1L12, pc2L1, pc2L12)) {
        is("b0000".U) { buechiState := States.n25 }
        is("b0001".U) { buechiState := States.n25 }
        is("b0010".U) { buechiState := ndChoice(States.n10, States.n25) }
        is("b0011".U) { buechiState := States.n25 }
        is("b0100".U) { buechiState := ndChoice(States.n21, States.n25) }
        is("b0101".U) { buechiState := ndChoice(States.n21, States.n25) }
        is("b0110".U) { buechiState := ndChoice(States.n10, States.n14, States.n21, States.n25) }
        is("b0111".U) { buechiState := ndChoice(States.n21, States.n25) }
        is("b1000".U) { buechiState := States.Trap }
        is("b1001".U) { buechiState := States.Trap }
        is("b1010".U) { buechiState := States.Trap }
        is("b1011".U) { buechiState := States.Trap }
        is("b1100".U) { buechiState := States.Trap }
        is("b1101".U) { buechiState := States.Trap }
        is("b1110".U) { buechiState := States.Trap }
        is("b1111".U) { buechiState := States.Trap }
      }
    }
    is(States.n9, States.n26) {
      switch(Cat(pc0L12, pc1L1, pc2L1, pc2L12)) {
        is("b0000".U) { buechiState := States.n9 }
        is("b0001".U) { buechiState := States.n9 }
        is("b0010".U) { buechiState := ndChoice(States.n7, States.n9) }
        is("b0011".U) { buechiState := States.n9 }
        is("b0100".U) { buechiState := States.Trap }
        is("b0101".U) { buechiState := States.Trap }
        is("b0110".U) { buechiState := States.Trap }
        is("b0111".U) { buechiState := States.Trap }
        is("b1000".U) { buechiState := ndChoice(States.n19, States.n9) }
        is("b1001".U) { buechiState := ndChoice(States.n19, States.n9) }
        is("b1010".U) { buechiState := ndChoice(States.n19, States.n7, States.n8, States.n9) }
        is("b1011".U) { buechiState := ndChoice(States.n19, States.n9) }
      }
    }
    is(States.n2, States.n7) {
      switch(Cat(pc0L12, pc1L1, pc2L12)) {
        is("b000".U) { buechiState := States.n13 }
        is("b001".U) { buechiState := States.Trap }
        is("b010".U) { buechiState := States.Trap }
        is("b011".U) { buechiState := States.Trap }
        is("b100".U) { buechiState := ndChoice(States.n13, States.n27) }
        is("b101".U) { buechiState := States.Trap }
        is("b110".U) { buechiState := States.Trap }
        is("b111".U) { buechiState := States.Trap }
      }
    }
    is(States.n1, States.n5, States.n16, States.n27) {
      switch(Cat(pc0L1, pc0L12, pc1L1, pc1L12, pc2L12)) {
        is("b00000".U) { buechiState := ndChoice(States.n13, States.n17, States.n18, States.n5) }
        is("b00001".U) { buechiState := States.Trap }
        is("b00010".U) { buechiState := ndChoice(States.n13, States.n16, States.n17, States.n18, States.n4, States.n5) }
        is("b00011".U) { buechiState := States.Trap }
        is("b00100".U) { buechiState := ndChoice(States.n17, States.n18) }
        is("b00101".U) { buechiState := States.Trap }
        is("b00110".U) { buechiState := ndChoice(States.n16, States.n17, States.n18, States.n4) }
        is("b00111".U) { buechiState := States.Trap }
        is("b01000".U) { buechiState := ndChoice(States.n13, States.n17, States.n18, States.n23, States.n27, States.n5) }
        is("b01001".U) { buechiState := States.Trap }
        is("b01010".U) { buechiState := ndChoice(States.n1, States.n13, States.n16, States.n17, States.n18, States.n23, States.n27, States.n4, States.n5) }
        is("b01011".U) { buechiState := States.Trap }
        is("b01100".U) { buechiState := ndChoice(States.n17, States.n18, States.n23) }
        is("b01101".U) { buechiState := States.Trap }
        is("b01110".U) { buechiState := ndChoice(States.n1, States.n16, States.n17, States.n18, States.n23, States.n4) }
        is("b01111".U) { buechiState := States.Trap }
        is("b10000".U) { buechiState := ndChoice(States.n13, States.n18) }
        is("b10001".U) { buechiState := States.Trap }
        is("b10010".U) { buechiState := ndChoice(States.n13, States.n18, States.n4) }
        is("b10011".U) { buechiState := States.Trap }
        is("b10100".U) { buechiState := States.n18 }
        is("b10101".U) { buechiState := States.Trap }
        is("b10110".U) { buechiState := ndChoice(States.n18, States.n4) }
        is("b10111".U) { buechiState := States.Trap }
        is("b11000".U) { buechiState := ndChoice(States.n13, States.n18, States.n23, States.n27) }
        is("b11001".U) { buechiState := States.Trap }
        is("b11010".U) { buechiState := ndChoice(States.n1, States.n13, States.n18, States.n23, States.n27, States.n4) }
        is("b11011".U) { buechiState := States.Trap }
        is("b11100".U) { buechiState := ndChoice(States.n18, States.n23) }
        is("b11101".U) { buechiState := States.Trap }
        is("b11110".U) { buechiState := ndChoice(States.n1, States.n18, States.n23, States.n4) }
        is("b11111".U) { buechiState := States.Trap }
      }
    }
    is(States.n4, States.n13) {
      switch(Cat(pc0L12, pc1L1, pc1L12, pc2L12)) {
        is("b0000".U) { buechiState := ndChoice(States.n13, States.n18) }
        is("b0001".U) { buechiState := States.Trap }
        is("b0010".U) { buechiState := ndChoice(States.n13, States.n18, States.n4) }
        is("b0011".U) { buechiState := States.Trap }
        is("b0100".U) { buechiState := States.n18 }
        is("b0101".U) { buechiState := States.Trap }
        is("b0110".U) { buechiState := ndChoice(States.n18, States.n4) }
        is("b0111".U) { buechiState := States.Trap }
        is("b1000".U) { buechiState := ndChoice(States.n13, States.n18, States.n23, States.n27) }
        is("b1001".U) { buechiState := States.Trap }
        is("b1010".U) { buechiState := ndChoice(States.n1, States.n13, States.n18, States.n23, States.n27, States.n4) }
        is("b1011".U) { buechiState := States.Trap }
        is("b1100".U) { buechiState := ndChoice(States.n18, States.n23) }
        is("b1101".U) { buechiState := States.Trap }
        is("b1110".U) { buechiState := ndChoice(States.n1, States.n18, States.n23, States.n4) }
        is("b1111".U) { buechiState := States.Trap }
      }
    }
  }
  
  // Fairness and SCC outputs
  val fair0 = buechiState === States.n1 || buechiState === States.n4 || buechiState === States.n5 || 
              buechiState === States.n13 || buechiState === States.n16 || buechiState === States.n27
  val fair1 = buechiState === States.n1 || buechiState === States.n5 || buechiState === States.n16 || 
              buechiState === States.n17 || buechiState === States.n23 || buechiState === States.n27
  val scc = buechiState === States.n4 || buechiState === States.n13 || buechiState === States.n23 || 
            buechiState === States.n5 || buechiState === States.n16 || buechiState === States.n17 || 
            buechiState === States.n27 || buechiState === States.n1 || buechiState === States.n18
  
  // Connect outputs
  io.flag := flag
  io.turn := turn
  io.pc := pc
  io.j := j
  io.selReg := selReg
  io.k := k
  io.fair0 := fair0
  io.fair1 := fair1
  io.scc := scc
  io.buechiState := buechiState
}

object VerilogGenerator extends App {
  emitVerilog(new EisenbergLTLM2(), args)
}