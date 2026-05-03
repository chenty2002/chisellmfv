package llmverify

import chisel3._
import chisel3.util._

// Enum for philosopher states
object PhilosopherState {
  val THINKING = 0.U(2.W)
  val HUNGRY = 1.U(2.W)
  val EATING = 2.U(2.W)
  val READING = 3.U(2.W)
}

// Enum for Buechi states
object BuechiState {
  val n1 = 0.U(4.W)
  val n2 = 1.U(4.W)
  val n3 = 2.U(4.W)
  val n4 = 3.U(4.W)
  val n5 = 4.U(4.W)
  val n6 = 5.U(4.W)
  val n7 = 6.U(4.W)
  val n8 = 7.U(4.W)
  val n9 = 8.U(4.W)
  val Trap = 9.U(4.W)
}

class diners extends Module {
  val io = IO(new Bundle {
    // Outputs to preserve the design
    val ph0Eating = Output(Bool())
    val ph1Eating = Output(Bool())
    val ph0Hungry = Output(Bool())
    val ph1Hungry = Output(Bool())
    val fair0 = Output(Bool())
    val fair1 = Output(Bool())
    val scc = Output(Bool())
    val starv_state = Output(Bool())
    // Additional outputs to preserve philosopher states
    val philosopher_states = Output(Vec(64, UInt(2.W)))
  })

  // Create state wires for all philosophers
  val s = Wire(Vec(64, UInt(2.W)))
  
  // Instantiate philosophers
  val philosophers = Array.fill(4)(Module(new philosopher()))
  val nphilosophers = Array.fill(60)(Module(new Nphilosopher()))
  
  // Connect special philosophers (ph0, ph1, ph2, ph3)
  philosophers(0).io.left := s(63)
  philosophers(0).io.right := s(1)
  philosophers(0).io.init := PhilosopherState.EATING
  philosophers(0).io.reset_enable := true.B  // Enable reset to init value
  s(0) := philosophers(0).io.out
  
  philosophers(1).io.left := s(0)
  philosophers(1).io.right := s(2)
  philosophers(1).io.init := PhilosopherState.READING
  philosophers(1).io.reset_enable := true.B
  s(1) := philosophers(1).io.out
  
  philosophers(2).io.left := s(1)
  philosophers(2).io.right := s(3)
  philosophers(2).io.init := PhilosopherState.HUNGRY
  philosophers(2).io.reset_enable := true.B
  s(2) := philosophers(2).io.out
  
  philosophers(3).io.left := s(2)
  philosophers(3).io.right := s(4)
  philosophers(3).io.init := PhilosopherState.THINKING
  philosophers(3).io.reset_enable := true.B
  s(3) := philosophers(3).io.out
  
  // Connect Nphilosophers (ph4 to ph63)
  for (i <- 4 to 63) {
    val idx = i - 4
    nphilosophers(idx).io.left := s(i-1)
    nphilosophers(idx).io.right := s((i+1) % 64)
    nphilosophers(idx).io.init := PhilosopherState.THINKING
    nphilosophers(idx).io.reset_enable := true.B
    s(i) := nphilosophers(idx).io.out
  }
  
  // Extract eating and hungry signals
  io.ph0Eating := s(0) === PhilosopherState.EATING
  io.ph1Eating := s(1) === PhilosopherState.EATING
  io.ph0Hungry := s(0) === PhilosopherState.HUNGRY
  io.ph1Hungry := s(1) === PhilosopherState.HUNGRY
  
  // Instantiate Buechi monitor
  val buechi = Module(new Buechi())
  buechi.io.ph0Eating := io.ph0Eating
  buechi.io.ph1Eating := io.ph1Eating
  buechi.io.ph1Hungry := io.ph1Hungry
  buechi.io.ph0Hungry := io.ph0Hungry
  io.fair0 := buechi.io.fair0
  io.fair1 := buechi.io.fair1
  io.scc := buechi.io.scc
  
  // Instantiate starvation monitor
  val starvation = Module(new starvation())
  starvation.io.starv := s(0)
  io.starv_state := starvation.io.state
  
  // Output all philosopher states to preserve the design
  io.philosopher_states := s
}

class Buechi extends Module {
  val io = IO(new Bundle {
    val ph0Eating = Input(Bool())
    val ph1Eating = Input(Bool())
    val ph1Hungry = Input(Bool())
    val ph0Hungry = Input(Bool())
    val fair0 = Output(Bool())
    val fair1 = Output(Bool())
    val scc = Output(Bool())
  })
  
  val state = RegInit(BuechiState.n5)
  
  // Nondeterministic choices - simplified for Chisel
  val ND_n3_n4 = Wire(UInt(4.W))
  val ND_n3_n4_n6_n8 = Wire(UInt(4.W))
  val ND_n4_n8 = Wire(UInt(4.W))
  val ND_n1_n7 = Wire(UInt(4.W))
  val ND_n6_n8 = Wire(UInt(4.W))
  val ND_n5_n9 = Wire(UInt(4.W))
  
  // Simple pseudo-random choice using LFSR
  val lfsr = RegInit(1.U(8.W))
  lfsr := Cat(lfsr(6,0), lfsr(7) ^ lfsr(5) ^ lfsr(4) ^ lfsr(3))
  
  ND_n3_n4 := Mux(lfsr(0), BuechiState.n3, BuechiState.n4)
  ND_n3_n4_n6_n8 := MuxLookup(lfsr(1,0), BuechiState.n3)(Seq(
    0.U -> BuechiState.n3,
    1.U -> BuechiState.n4,
    2.U -> BuechiState.n6,
    3.U -> BuechiState.n8
  ))
  ND_n4_n8 := Mux(lfsr(2), BuechiState.n4, BuechiState.n8)
  ND_n1_n7 := Mux(lfsr(3), BuechiState.n1, BuechiState.n7)
  ND_n6_n8 := Mux(lfsr(4), BuechiState.n6, BuechiState.n8)
  ND_n5_n9 := Mux(lfsr(5), BuechiState.n5, BuechiState.n9)
  
  io.fair0 := (state === BuechiState.n1) || (state === BuechiState.n7)
  io.fair1 := (state === BuechiState.n7) || (state === BuechiState.n2)
  io.scc := (state === BuechiState.n1) || (state === BuechiState.n2) || (state === BuechiState.n7)
  
  // Initialize nextState with default value
  val nextState = Wire(UInt(4.W))
  nextState := state  // Default: stay in current state
  
  switch(state) {
    is(BuechiState.n5) {
      val inputs = Cat(io.ph0Eating, io.ph0Hungry, io.ph1Eating, io.ph1Hungry)
      switch(inputs) {
        is("b0000".U) { nextState := ND_n4_n8 }
        is("b0001".U) { nextState := ND_n3_n4_n6_n8 }
        is("b0010".U) { nextState := ND_n4_n8 }
        is("b0011".U) { nextState := ND_n4_n8 }
        is("b0100".U) { nextState := BuechiState.n4 }
        is("b0101".U) { nextState := ND_n3_n4 }
        is("b0110".U) { nextState := BuechiState.n4 }
        is("b0111".U) { nextState := BuechiState.n4 }
        is("b1000".U) { nextState := BuechiState.n5 }
        is("b1001".U) { nextState := ND_n5_n9 }
        is("b1010".U) { nextState := BuechiState.n5 }
        is("b1011".U) { nextState := BuechiState.n5 }
        is("b1100".U) { nextState := BuechiState.n5 }
        is("b1101".U) { nextState := ND_n5_n9 }
        is("b1110".U) { nextState := BuechiState.n5 }
        is("b1111".U) { nextState := BuechiState.n5 }
      }
    }
    is(BuechiState.Trap) {
      nextState := BuechiState.Trap
    }
    is(BuechiState.n4) {
      val inputs = Cat(io.ph0Eating, io.ph1Eating, io.ph1Hungry)
      switch(inputs) {
        is("b000".U) { nextState := BuechiState.n4 }
        is("b001".U) { nextState := ND_n3_n4 }
        is("b010".U) { nextState := BuechiState.n4 }
        is("b011".U) { nextState := BuechiState.n4 }
        is("b100".U) { nextState := BuechiState.n5 }
        is("b101".U) { nextState := ND_n5_n9 }
        is("b110".U) { nextState := BuechiState.n5 }
        is("b111".U) { nextState := BuechiState.n5 }
      }
    }
    is(BuechiState.n1) {
      val inputs = Cat(io.ph0Eating, io.ph1Eating)
      switch(inputs) {
        is("b00".U) { nextState := BuechiState.n1 }
        is("b01".U) { nextState := BuechiState.Trap }
        is("b10".U) { nextState := BuechiState.n2 }
        is("b11".U) { nextState := BuechiState.Trap }
      }
    }
    is(BuechiState.n3) {
      val inputs = Cat(io.ph0Eating, io.ph1Eating)
      switch(inputs) {
        is("b00".U) { nextState := BuechiState.n1 }
        is("b01".U) { nextState := BuechiState.Trap }
        is("b10".U) { nextState := BuechiState.n2 }
        is("b11".U) { nextState := BuechiState.Trap }
      }
    }
    is(BuechiState.n6) {
      val inputs = Cat(io.ph0Eating, io.ph0Hungry, io.ph1Eating)
      switch(inputs) {
        is("b000".U) { nextState := BuechiState.n7 }
        is("b001".U) { nextState := BuechiState.Trap }
        is("b010".U) { nextState := BuechiState.Trap }
        is("b011".U) { nextState := BuechiState.Trap }
        is("b100".U) { nextState := BuechiState.Trap }
        is("b101".U) { nextState := BuechiState.Trap }
        is("b110".U) { nextState := BuechiState.Trap }
        is("b111".U) { nextState := BuechiState.Trap }
      }
    }
    is(BuechiState.n2) {
      val inputs = Cat(io.ph0Eating, io.ph0Hungry, io.ph1Eating)
      switch(inputs) {
        is("b000".U) { nextState := ND_n1_n7 }
        is("b001".U) { nextState := BuechiState.Trap }
        is("b010".U) { nextState := BuechiState.n1 }
        is("b011".U) { nextState := BuechiState.Trap }
        is("b100".U) { nextState := BuechiState.n2 }
        is("b101".U) { nextState := BuechiState.Trap }
        is("b110".U) { nextState := BuechiState.n2 }
        is("b111".U) { nextState := BuechiState.Trap }
      }
    }
    is(BuechiState.n7) {
      val inputs = Cat(io.ph0Eating, io.ph0Hungry, io.ph1Eating)
      switch(inputs) {
        is("b000".U) { nextState := ND_n1_n7 }
        is("b001".U) { nextState := BuechiState.Trap }
        is("b010".U) { nextState := BuechiState.n1 }
        is("b011".U) { nextState := BuechiState.Trap }
        is("b100".U) { nextState := BuechiState.n2 }
        is("b101".U) { nextState := BuechiState.Trap }
        is("b110".U) { nextState := BuechiState.n2 }
        is("b111".U) { nextState := BuechiState.Trap }
      }
    }
    is(BuechiState.n9) {
      val inputs = Cat(io.ph0Eating, io.ph0Hungry, io.ph1Eating)
      switch(inputs) {
        is("b000".U) { nextState := ND_n1_n7 }
        is("b001".U) { nextState := BuechiState.Trap }
        is("b010".U) { nextState := BuechiState.n1 }
        is("b011".U) { nextState := BuechiState.Trap }
        is("b100".U) { nextState := BuechiState.n2 }
        is("b101".U) { nextState := BuechiState.Trap }
        is("b110".U) { nextState := BuechiState.n2 }
        is("b111".U) { nextState := BuechiState.Trap }
      }
    }
    is(BuechiState.n8) {
      val inputs = Cat(io.ph0Eating, io.ph0Hungry, io.ph1Eating, io.ph1Hungry)
      switch(inputs) {
        is("b0000".U) { nextState := BuechiState.n8 }
        is("b0001".U) { nextState := ND_n6_n8 }
        is("b0010".U) { nextState := BuechiState.n8 }
        is("b0011".U) { nextState := BuechiState.n8 }
        is("b0100".U) { nextState := BuechiState.Trap }
        is("b0101".U) { nextState := BuechiState.Trap }
        is("b0110".U) { nextState := BuechiState.Trap }
        is("b0111".U) { nextState := BuechiState.Trap }
        is("b1000".U) { nextState := BuechiState.Trap }
        is("b1001".U) { nextState := BuechiState.Trap }
        is("b1010".U) { nextState := BuechiState.Trap }
        is("b1011".U) { nextState := BuechiState.Trap }
        is("b1100".U) { nextState := BuechiState.Trap }
        is("b1101".U) { nextState := BuechiState.Trap }
        is("b1110".U) { nextState := BuechiState.Trap }
        is("b1111".U) { nextState := BuechiState.Trap }
      }
    }
  }
  
  state := nextState
}

class philosopher extends Module {
  val io = IO(new Bundle {
    val left = Input(UInt(2.W))
    val right = Input(UInt(2.W))
    val init = Input(UInt(2.W))
    val reset_enable = Input(Bool())
    val out = Output(UInt(2.W))
  })
  
  val state = RegInit(PhilosopherState.THINKING)
  
  // Handle reset to initial value
  when(io.reset_enable) {
    state := io.init
  }
  
  // Nondeterministic choices
  val lfsr = RegInit(1.U(8.W))
  lfsr := Cat(lfsr(6,0), lfsr(7) ^ lfsr(5) ^ lfsr(4) ^ lfsr(3))
  
  val r0_state = Mux(lfsr(0), PhilosopherState.THINKING, PhilosopherState.HUNGRY)
  val r1_state = Mux(lfsr(1), PhilosopherState.THINKING, PhilosopherState.EATING)
  
  io.out := state
  
  when(!io.reset_enable) {
    switch(state) {
      is(PhilosopherState.READING) {
        when(io.left === PhilosopherState.THINKING) {
          state := PhilosopherState.THINKING
        }
      }
      is(PhilosopherState.THINKING) {
        when(io.right === PhilosopherState.READING) {
          state := PhilosopherState.READING
        }.otherwise {
          state := r0_state
        }
      }
      is(PhilosopherState.EATING) {
        state := r1_state
      }
      is(PhilosopherState.HUNGRY) {
        when(io.left =/= PhilosopherState.EATING && io.right =/= PhilosopherState.HUNGRY && io.right =/= PhilosopherState.EATING) {
          state := PhilosopherState.EATING
        }
      }
    }
  }
}

class Nphilosopher extends Module {
  val io = IO(new Bundle {
    val left = Input(UInt(2.W))
    val right = Input(UInt(2.W))
    val init = Input(UInt(2.W))
    val reset_enable = Input(Bool())
    val out = Output(UInt(2.W))
  })
  
  val state = RegInit(PhilosopherState.THINKING)
  
  // Handle reset to initial value
  when(io.reset_enable) {
    state := io.init
  }
  
  // Nondeterministic choices
  val lfsr = RegInit(1.U(8.W))
  lfsr := Cat(lfsr(6,0), lfsr(7) ^ lfsr(5) ^ lfsr(4) ^ lfsr(3))
  
  val r0_state = Mux(lfsr(0), PhilosopherState.THINKING, PhilosopherState.HUNGRY)
  // Note: In Nphilosopher, EATING always goes to THINKING (not r1_state)
  
  io.out := state
  
  when(!io.reset_enable) {
    switch(state) {
      is(PhilosopherState.READING) {
        when(io.left === PhilosopherState.THINKING) {
          state := PhilosopherState.THINKING
        }
      }
      is(PhilosopherState.THINKING) {
        when(io.right === PhilosopherState.READING) {
          state := PhilosopherState.READING
        }.otherwise {
          state := r0_state
        }
      }
      is(PhilosopherState.EATING) {
        state := PhilosopherState.THINKING // Fixed behavior for Nphilosopher
      }
      is(PhilosopherState.HUNGRY) {
        when(io.left =/= PhilosopherState.EATING && io.right =/= PhilosopherState.HUNGRY && io.right =/= PhilosopherState.EATING) {
          state := PhilosopherState.EATING
        }
      }
    }
  }
}

class starvation extends Module {
  val io = IO(new Bundle {
    val starv = Input(UInt(2.W))
    val state = Output(Bool())
  })
  
  val state_reg = RegInit(false.B)
  io.state := state_reg
  
  switch(state_reg) {
    is(false.B) {
      when(io.starv === PhilosopherState.HUNGRY) {
        state_reg := true.B
      }
    }
    is(true.B) {
      when(io.starv === PhilosopherState.THINKING) {
        state_reg := false.B
      }
    }
  }
}

object VerilogGenerator extends App {
  emitVerilog(new diners(), args)
}