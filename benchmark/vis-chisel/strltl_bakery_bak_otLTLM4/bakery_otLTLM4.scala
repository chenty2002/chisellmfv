package llmverify

import chisel3._
import chisel3.util._
import scala.language.postfixOps

// Type of program counter locations.
object Loc extends ChiselEnum {
  val L1, L2a, L2b, L2c, L3, L4, L5, L6, L7, L8, L9,
      L10a, L10b, L10c, L11 = Value
}

// States for Buechi automaton
object States extends ChiselEnum {
  val Init, n1, n2, n3, n4, n5, n6, n7, n8, n9, n10, n11, n12, n13, n14, n15, n16, n17, n18, n19, n20, n21, n22, n23, n24, n25, n26, n27, Trap = Value
}

class bakery extends Module {
  val io = IO(new Bundle {
    val select = Input(UInt(3.W))  // SELMSB+1 = 2+1 = 3 bits
    val pause = Input(Bool())
    val pc0L4 = Output(Bool())
    val pc1L4 = Output(Bool())
    val pc2L9 = Output(Bool())
    val pc2L4 = Output(Bool())
    val pc1L9 = Output(Bool())
    val pc0L9 = Output(Bool())
    val fair0 = Output(Bool())
    val fair1 = Output(Bool())
    val scc = Output(Bool())
  })
  
  // Constants
  val SELMSB = 2
  val HIPROC = 2
  
  // The ticket-holding flags of processes.
  val ticket = RegInit(VecInit(Seq.fill(HIPROC + 1)(false.B)))
  
  // More than one process may be choosing a ticket.
  val choosing = RegInit(VecInit(Seq.fill(HIPROC + 1)(false.B)))
  
  // The program counters of processes.
  val pc = RegInit(VecInit(Seq.fill(HIPROC + 1)(Loc.L1)))
  
  // The loop indices of processors.
  val j = RegInit(VecInit(Seq.fill(HIPROC + 1)(0.U((SELMSB + 1).W))))
  
  // The latched value of the process selection variable.
  val selReg = RegInit(0.U((SELMSB + 1).W))
  
  // Defer matrix
  val defer = RegInit(VecInit(Seq.fill(HIPROC + 1)(0.U((HIPROC + 1).W))))
  
  // Priority array
  val pri = RegInit(VecInit(Seq.tabulate(HIPROC + 1)(i => i.U((SELMSB + 1).W))))
  
  // Process logic for each selected process
  val sel = selReg
  
  // Extract one bit from a vector.
  def extract(in: UInt, index: UInt): Bool = {
    val result = Wire(Bool())
    result := false.B
    switch(index) {
      is(0.U) { result := in(0) }
      is(1.U) { result := in(1) }
      is(2.U) { result := in(2) }
    }
    when(index > 2.U) {
      result := false.B
    }
    result
  }
  
  // Set one bit in a vector.
  def setBit(in: UInt, index: UInt, val_bit: Bool): UInt = {
    val result = Wire(UInt(in.getWidth.W))
    result := in
    switch(index) {
      is(0.U) { result := Cat(in(2, 1), val_bit) }
      is(1.U) { result := Cat(in(2), val_bit, in(0)) }
      is(2.U) { result := Cat(val_bit, in(1, 0)) }
    }
    result
  }
  
  // Process state machine
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
      val k = j(sel)
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
      val k = j(sel)
      when(choosing(k)) {
        pc(sel) := Loc.L6
      }.otherwise {
        pc(sel) := Loc.L7
      }
    }
    is(Loc.L7) {
      val k = j(sel)
      val defSelK = extract(defer(sel), k)
      val defKSel = extract(defer(k), sel)
      
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
      val k = j(sel)
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
  
  // Update selReg
  when(io.select > HIPROC.U) {
    selReg := 0.U
  }.otherwise {
    selReg := io.select
  }
  
  // Output signals for Buechi module
  io.pc0L4 := pc(0) === Loc.L4
  io.pc0L9 := pc(0) === Loc.L9
  io.pc1L4 := pc(1) === Loc.L4
  io.pc1L9 := pc(1) === Loc.L9
  io.pc2L4 := pc(2) === Loc.L4
  io.pc2L9 := pc(2) === Loc.L9
  
  // Instantiate Buechi module
  val buechi = Module(new Buechi)
  buechi.io.clock := this.clock
  buechi.io.pc0L4 := io.pc0L4
  buechi.io.pc1L4 := io.pc1L4
  buechi.io.pc2L9 := io.pc2L9
  buechi.io.pc2L4 := io.pc2L4
  buechi.io.pc1L9 := io.pc1L9
  buechi.io.pc0L9 := io.pc0L9
  
  io.fair0 := buechi.io.fair0
  io.fair1 := buechi.io.fair1
  io.scc := buechi.io.scc
}

class Buechi extends Module {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val pc0L4 = Input(Bool())
    val pc1L4 = Input(Bool())
    val pc2L9 = Input(Bool())
    val pc2L4 = Input(Bool())
    val pc1L9 = Input(Bool())
    val pc0L9 = Input(Bool())
    val fair0 = Output(Bool())
    val fair1 = Output(Bool())
    val scc = Output(Bool())
  })
  
  val state = RegInit(States.Init)
  
  // Nondeterministic choice functions - simplified for Chisel
  // In practice, these would need more sophisticated handling
  def ND(states: States.Type*): States.Type = {
    // For simplicity, return the first state
    // In a real implementation, this would be nondeterministic
    states.head
  }
  
  // State transitions - using when/elsewhen instead of switch for mutually exclusive conditions
  switch(state) {
    is(States.Trap) {
      state := States.Trap
    }
    
    is(States.n9, States.n20, States.n23, States.n27) {
      val input = Cat(io.pc0L4, io.pc1L4, io.pc2L9)
      when(input === "b000".U) {
        state := States.n1
      }.elsewhen(input === "b001".U) {
        state := States.Trap
      }.elsewhen(input === "b010".U || input === "b011".U) {
        state := States.Trap
      }.elsewhen(input(2) === 1.U) {
        state := States.Trap
      }.otherwise {
        state := States.n1
      }
    }
    
    is(States.n8, States.n24) {
      val input = Cat(io.pc0L9, io.pc1L4, io.pc2L4, io.pc2L9)
      when(input === "b0000".U || input === "b0001".U) {
        state := States.n24
      }.elsewhen(input === "b0010".U) {
        state := ND(States.n21, States.n24)
      }.elsewhen(input === "b0011".U) {
        state := States.n24
      }.elsewhen(input(3,2) === "b01".U) {
        state := States.Trap
      }.elsewhen(input === "b0100".U || input === "b0101".U) {
        state := ND(States.n22, States.n24)
      }.elsewhen(input === "b1010".U) {
        state := ND(States.n21, States.n22, States.n24, States.n27)
      }.elsewhen(input === "b1011".U) {
        state := ND(States.n22, States.n24)
      }.otherwise {
        state := States.n24
      }
    }
    
    is(States.n7, States.n19) {
      val input = Cat(io.pc0L4, io.pc0L9, io.pc1L9, io.pc2L9)
      when(input === "b0000".U) {
        state := ND(States.n12, States.n7)
      }.elsewhen(input(0) === 1.U) {
        state := States.Trap
      }.elsewhen(input === "b0010".U) {
        state := ND(States.n11, States.n12, States.n18, States.n7)
      }.elsewhen(input === "b0100".U) {
        state := ND(States.n12, States.n19, States.n7)
      }.elsewhen(input === "b0110".U) {
        state := ND(States.n11, States.n12, States.n18, States.n19, States.n26, States.n7)
      }.elsewhen(input === "b1000".U) {
        state := States.n12
      }.elsewhen(input === "b1010".U) {
        state := ND(States.n12, States.n18)
      }.elsewhen(input === "b1100".U) {
        state := ND(States.n12, States.n19)
      }.elsewhen(input === "b1110".U) {
        state := ND(States.n12, States.n18, States.n19, States.n26)
      }.otherwise {
        state := ND(States.n12, States.n7)
      }
    }
    
    is(States.n1, States.n11, States.n25, States.n26) {
      val input = Cat(io.pc0L4, io.pc0L9, io.pc1L4, io.pc1L9, io.pc2L9)
      when(input === "b00000".U) {
        state := ND(States.n1, States.n12, States.n5, States.n7)
      }.elsewhen(input(0) === 1.U) {
        state := States.Trap
      }.elsewhen(input === "b00010".U) {
        state := ND(States.n1, States.n11, States.n12, States.n18, States.n5, States.n7)
      }.elsewhen(input === "b00100".U) {
        state := ND(States.n12, States.n7)
      }.elsewhen(input === "b00110".U) {
        state := ND(States.n11, States.n12, States.n18, States.n7)
      }.elsewhen(input === "b01000".U) {
        state := ND(States.n1, States.n12, States.n19, States.n25, States.n5, States.n7)
      }.elsewhen(input === "b01010".U) {
        state := ND(States.n1, States.n11, States.n12, States.n18, States.n19, States.n25, States.n26, States.n5, States.n7)
      }.elsewhen(input === "b01100".U) {
        state := ND(States.n12, States.n19, States.n7)
      }.elsewhen(input === "b01110".U) {
        state := ND(States.n11, States.n12, States.n18, States.n19, States.n26, States.n7)
      }.elsewhen(input === "b10000".U) {
        state := ND(States.n12, States.n5)
      }.elsewhen(input === "b10010".U) {
        state := ND(States.n12, States.n18, States.n5)
      }.elsewhen(input === "b10100".U) {
        state := States.n12
      }.elsewhen(input === "b10110".U) {
        state := ND(States.n12, States.n18)
      }.elsewhen(input === "b11000".U) {
        state := ND(States.n12, States.n19, States.n25, States.n5)
      }.elsewhen(input === "b11010".U) {
        state := ND(States.n12, States.n18, States.n19, States.n25, States.n26, States.n5)
      }.elsewhen(input === "b11100".U) {
        state := ND(States.n12, States.n19)
      }.elsewhen(input === "b11110".U) {
        state := ND(States.n12, States.n18, States.n19, States.n26)
      }.otherwise {
        state := ND(States.n1, States.n12, States.n5, States.n7)
      }
    }
    
    is(States.n3, States.n4, States.n13, States.n22) {
      val input = Cat(io.pc0L4, io.pc1L4, io.pc2L4, io.pc2L9)
      when(input === "b0000".U || input === "b0001".U) {
        state := States.n4
      }.elsewhen(input === "b0010".U) {
        state := ND(States.n20, States.n4)
      }.elsewhen(input === "b0011".U) {
        state := States.n4
      }.elsewhen(input(3,2) === "b01".U) {
        state := States.Trap
      }.elsewhen(input(3) === 1.U) {
        state := States.Trap
      }.otherwise {
        state := States.n4
      }
    }
    
    is(States.n5, States.n18) {
      val input = Cat(io.pc0L9, io.pc1L4, io.pc1L9, io.pc2L9)
      when(input === "b0000".U) {
        state := ND(States.n12, States.n5)
      }.elsewhen(input(0) === 1.U) {
        state := States.Trap
      }.elsewhen(input === "b0010".U) {
        state := ND(States.n12, States.n18, States.n5)
      }.elsewhen(input === "b0100".U) {
        state := States.n12
      }.elsewhen(input === "b0110".U) {
        state := ND(States.n12, States.n18)
      }.elsewhen(input === "b1000".U) {
        state := ND(States.n12, States.n19, States.n25, States.n5)
      }.elsewhen(input === "b1010".U) {
        state := ND(States.n12, States.n18, States.n19, States.n25, States.n26, States.n5)
      }.elsewhen(input === "b1100".U) {
        state := ND(States.n12, States.n19)
      }.elsewhen(input === "b1110".U) {
        state := ND(States.n12, States.n18, States.n19, States.n26)
      }.otherwise {
        state := ND(States.n12, States.n5)
      }
    }
    
    is(States.n6, States.n15) {
      val input = Cat(io.pc0L4, io.pc1L9, io.pc2L4, io.pc2L9)
      when(input === "b0000".U || input === "b0001".U) {
        state := States.n6
      }.elsewhen(input === "b0010".U) {
        state := ND(States.n2, States.n6)
      }.elsewhen(input === "b0011".U) {
        state := States.n6
      }.elsewhen(input === "b0100".U || input === "b0101".U) {
        state := ND(States.n3, States.n6)
      }.elsewhen(input === "b0110".U) {
        state := ND(States.n2, States.n23, States.n3, States.n6)
      }.elsewhen(input === "b0111".U) {
        state := ND(States.n3, States.n6)
      }.elsewhen(input(3) === 1.U) {
        state := States.Trap
      }.otherwise {
        state := States.n6
      }
    }
    
    is(States.n12, States.n17) {
      val input = Cat(io.pc0L9, io.pc1L9, io.pc2L9)
      when(input === "b000".U) {
        state := States.n12
      }.elsewhen(input(0) === 1.U) {
        state := States.Trap
      }.elsewhen(input === "b010".U) {
        state := ND(States.n12, States.n18)
      }.elsewhen(input === "b100".U) {
        state := ND(States.n12, States.n19)
      }.elsewhen(input === "b110".U) {
        state := ND(States.n12, States.n18, States.n19, States.n26)
      }.otherwise {
        state := States.n12
      }
    }
    
    is(States.n2, States.n10) {
      val input = Cat(io.pc0L4, io.pc1L9, io.pc2L9)
      when(input === "b000".U) {
        state := States.n7
      }.elsewhen(input(1,0) === "b01".U) {
        state := States.Trap
      }.elsewhen(input === "b010".U) {
        state := ND(States.n11, States.n7)
      }.elsewhen(input(2) === 1.U) {
        state := States.Trap
      }.otherwise {
        state := States.n7
      }
    }
    
    is(States.n16) {
      val input = Cat(io.pc0L9, io.pc1L9, io.pc2L4, io.pc2L9)
      when(input === "b0000".U || input === "b0001".U) {
        state := States.n16
      }.elsewhen(input === "b0010".U) {
        state := ND(States.n16, States.n17)
      }.elsewhen(input === "b0011".U) {
        state := States.n16
      }.elsewhen(input === "b0100".U || input === "b0101".U) {
        state := ND(States.n16, States.n8)
      }.elsewhen(input === "b0110".U) {
        state := ND(States.n14, States.n16, States.n17, States.n8)
      }.elsewhen(input === "b0111".U) {
        state := ND(States.n16, States.n8)
      }.elsewhen(input === "b1000".U || input === "b1001".U) {
        state := ND(States.n15, States.n16)
      }.elsewhen(input === "b1010".U) {
        state := ND(States.n10, States.n15, States.n16, States.n17)
      }.elsewhen(input === "b1011".U) {
        state := ND(States.n15, States.n16)
      }.elsewhen(input === "b1100".U || input === "b1101".U) {
        state := ND(States.n13, States.n15, States.n16, States.n8)
      }.elsewhen(input === "b1110".U) {
        state := ND(States.n10, States.n13, States.n14, States.n15, States.n16, States.n17, States.n8, States.n9)
      }.elsewhen(input === "b1111".U) {
        state := ND(States.n13, States.n15, States.n16, States.n8)
      }.otherwise {
        state := States.n16
      }
    }
    
    is(States.n14, States.n21) {
      val input = Cat(io.pc0L9, io.pc1L4, io.pc2L9)
      when(input === "b000".U) {
        state := States.n5
      }.elsewhen(input(1,0) === "b01".U) {
        state := States.Trap
      }.elsewhen(input(2) === 1.U) {
        state := States.Trap
      }.elsewhen(input === "b100".U) {
        state := ND(States.n25, States.n5)
      }.otherwise {
        state := States.n5
      }
    }
    
    is(States.Init) {
      val input = Cat(io.pc0L4, io.pc0L9, io.pc1L4, io.pc1L9, io.pc2L4, io.pc2L9)
      when(input === "b000000".U || input === "b000001".U) {
        state := ND(States.n16, States.n24, States.n4, States.n6)
      }.elsewhen(input === "b000010".U) {
        state := ND(States.n16, States.n17, States.n2, States.n20, States.n21, States.n24, States.n4, States.n6)
      }.elsewhen(input === "b000011".U) {
        state := ND(States.n16, States.n24, States.n4, States.n6)
      }.elsewhen(input === "b000100".U || input === "b000101".U) {
        state := ND(States.n16, States.n24, States.n3, States.n4, States.n6, States.n8)
      }.elsewhen(input === "b000110".U) {
        state := ND(States.n14, States.n16, States.n17, States.n2, States.n20, States.n21, States.n23, States.n24, States.n3, States.n4, States.n6, States.n8)
      }.elsewhen(input === "b000111".U) {
        state := ND(States.n16, States.n24, States.n3, States.n4, States.n6, States.n8)
      }.elsewhen(input === "b001000".U || input === "b001001".U) {
        state := ND(States.n16, States.n6)
      }.elsewhen(input === "b001010".U) {
        state := ND(States.n16, States.n17, States.n2, States.n6)
      }.elsewhen(input === "b001011".U) {
        state := ND(States.n16, States.n6)
      }.elsewhen(input === "b001100".U || input === "b001101".U) {
        state := ND(States.n16, States.n3, States.n6, States.n8)
      }.elsewhen(input === "b001110".U) {
        state := ND(States.n14, States.n16, States.n17, States.n2, States.n23, States.n3, States.n6, States.n8)
      }.elsewhen(input === "b001111".U) {
        state := ND(States.n16, States.n3, States.n6, States.n8)
      }.elsewhen(input === "b010000".U || input === "b010001".U) {
        state := ND(States.n15, States.n16, States.n22, States.n24, States.n4, States.n6)
      }.elsewhen(input === "b010010".U) {
        state := ND(States.n10, States.n15, States.n16, States.n17, States.n2, States.n20, States.n21, States.n22, States.n24, States.n27, States.n4, States.n6)
      }.elsewhen(input === "b010011".U) {
        state := ND(States.n15, States.n16, States.n22, States.n24, States.n4, States.n6)
      }.elsewhen(input === "b010100".U || input === "b010101".U) {
        state := ND(States.n13, States.n15, States.n16, States.n22, States.n24, States.n3, States.n4, States.n6, States.n8)
      }.elsewhen(input === "b010110".U) {
        state := ND(States.n10, States.n13, States.n14, States.n15, States.n16, States.n17, States.n2, States.n20, States.n21, States.n22, States.n23, States.n24, States.n27, States.n3, States.n4, States.n6, States.n8, States.n9)
      }.elsewhen(input === "b010111".U) {
        state := ND(States.n13, States.n15, States.n16, States.n22, States.n24, States.n3, States.n4, States.n6, States.n8)
      }.elsewhen(input === "b011000".U || input === "b011001".U) {
        state := ND(States.n15, States.n16, States.n6)
      }.elsewhen(input === "b011010".U) {
        state := ND(States.n10, States.n15, States.n16, States.n17, States.n2, States.n6)
      }.elsewhen(input === "b011011".U) {
        state := ND(States.n15, States.n16, States.n6)
      }.elsewhen(input === "b011100".U || input === "b011101".U) {
        state := ND(States.n13, States.n15, States.n16, States.n3, States.n6, States.n8)
      }.elsewhen(input === "b011110".U) {
        state := ND(States.n10, States.n13, States.n14, States.n15, States.n16, States.n17, States.n2, States.n23, States.n3, States.n6, States.n8, States.n9)
      }.elsewhen(input === "b011111".U) {
        state := ND(States.n13, States.n15, States.n16, States.n3, States.n6, States.n8)
      }.elsewhen(input === "b100000".U || input === "b100001".U) {
        state := ND(States.n16, States.n24)
      }.elsewhen(input === "b100010".U) {
        state := ND(States.n16, States.n17, States.n21, States.n24)
      }.elsewhen(input === "b100011".U) {
        state := ND(States.n16, States.n24)
      }.elsewhen(input === "b100100".U || input === "b100101".U) {
        state := ND(States.n16, States.n24, States.n8)
      }.elsewhen(input === "b100110".U) {
        state := ND(States.n14, States.n16, States.n17, States.n21, States.n24, States.n8)
      }.elsewhen(input === "b100111".U) {
        state := ND(States.n16, States.n24, States.n8)
      }.elsewhen(input === "b101000".U || input === "b101001".U) {
        state := States.n16
      }.elsewhen(input === "b101010".U) {
        state := ND(States.n16, States.n17)
      }.elsewhen(input === "b101011".U) {
        state := States.n16
      }.elsewhen(input === "b101100".U || input === "b101101".U) {
        state := ND(States.n16, States.n8)
      }.elsewhen(input === "b101110".U) {
        state := ND(States.n14, States.n16, States.n17, States.n8)
      }.elsewhen(input === "b101111".U) {
        state := ND(States.n16, States.n8)
      }.elsewhen(input === "b110000".U || input === "b110001".U) {
        state := ND(States.n15, States.n16, States.n22, States.n24)
      }.elsewhen(input === "b110010".U) {
        state := ND(States.n10, States.n15, States.n16, States.n17, States.n21, States.n22, States.n24, States.n27)
      }.elsewhen(input === "b110011".U) {
        state := ND(States.n15, States.n16, States.n22, States.n24)
      }.elsewhen(input === "b110100".U || input === "b110101".U) {
        state := ND(States.n13, States.n15, States.n16, States.n22, States.n24, States.n8)
      }.elsewhen(input === "b110110".U) {
        state := ND(States.n10, States.n13, States.n14, States.n15, States.n16, States.n17, States.n21, States.n22, States.n24, States.n27, States.n8, States.n9)
      }.elsewhen(input === "b110111".U) {
        state := ND(States.n13, States.n15, States.n16, States.n22, States.n24, States.n8)
      }.elsewhen(input === "b111000".U || input === "b111001".U) {
        state := ND(States.n15, States.n16)
      }.elsewhen(input === "b111010".U) {
        state := ND(States.n10, States.n15, States.n16, States.n17)
      }.elsewhen(input === "b111011".U) {
        state := ND(States.n15, States.n16)
      }.elsewhen(input === "b111100".U || input === "b111101".U) {
        state := ND(States.n13, States.n15, States.n16, States.n8)
      }.elsewhen(input === "b111110".U) {
        state := ND(States.n10, States.n13, States.n14, States.n15, States.n16, States.n17, States.n8, States.n9)
      }.elsewhen(input === "b111111".U) {
        state := ND(States.n13, States.n15, States.n16, States.n8)
      }.otherwise {
        state := ND(States.n16, States.n24, States.n4, States.n6)
      }
    }
  }
  
  // Output signals
  io.fair0 := (state === States.n1) || (state === States.n19) || (state === States.n25) || (state === States.n7) || (state === States.n26) || (state === States.n11)
  io.fair1 := (state === States.n1) || (state === States.n5) || (state === States.n11) || (state === States.n18) || (state === States.n25) || (state === States.n26)
  io.scc := (state === States.n11) || (state === States.n12) || (state === States.n5) || (state === States.n25) || (state === States.n7) || (state === States.n26) || (state === States.n18) || (state === States.n19) || (state === States.n1)
}

object VerilogGenerator extends App {
  emitVerilog(new bakery(), args)
}