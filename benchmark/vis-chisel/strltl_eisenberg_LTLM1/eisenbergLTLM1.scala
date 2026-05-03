package llmverify

import chisel3._
import chisel3.util._

// Enum for program counter locations
object Loc extends ChiselEnum {
  val L1, L2, L3, L4, L5, L6, L7, L8, L9, L10, L11, L12, L13, L14, L15, L16 = Value
}

// Enum for process activity
object Activity extends ChiselEnum {
  val idle, waiting, active = Value
}

// Enum for Büchi automaton states
object States extends ChiselEnum {
  val n2, n4, n11, n12, n19, n20, n21, n30, n31, n32, n34, n38, n40, n42, n43, n47, n48, Trap = Value
}

class EisenbergLTLM1 extends Module {
  val io = IO(new Bundle {
    val select = Input(UInt(2.W))
    val pause = Input(Bool())
    val fair0 = Output(Bool())
    val fair1 = Output(Bool())
    val fair2 = Output(Bool())
    val fair3 = Output(Bool())
    val scc = Output(Bool())
    // Additional outputs to preserve internal signals
    val flag0 = Output(Activity())
    val flag1 = Output(Activity())
    val flag2 = Output(Activity())
    val pc0 = Output(Loc())
    val pc1 = Output(Loc())
    val pc2 = Output(Loc())
    val turn = Output(UInt(2.W))
  })
  
  val HIPROC = 2
  val SELMSB = 1
  
  // Process flags
  val flag = RegInit(VecInit(Seq.fill(HIPROC + 1)(Activity.idle)))
  
  // Turn variable
  val turn = RegInit(0.U(2.W))
  
  // Program counters
  val pc = RegInit(VecInit(Seq.fill(HIPROC + 1)(Loc.L1)))
  
  // Loop indices
  val j = RegInit(VecInit(Seq.fill(HIPROC + 1)(0.U(2.W))))
  
  // Latched selection
  val selReg = RegInit(0.U(2.W))
  
  // Process logic for each selected process
  when(io.select <= HIPROC.U) {
    selReg := io.select
  }.otherwise {
    selReg := 0.U
  }
  
  // Process execution logic
  val sel = selReg
  
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
      when(flag(j(sel)) =/= Activity.idle) {
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
      when(j(sel) <= HIPROC.U && (j(sel) === sel || flag(j(sel)) =/= Activity.active)) {
        j(sel) := j(sel) + 1.U
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
      when(flag(j(sel)) === Activity.idle) {
        when(j(sel) === HIPROC.U) {
          j(sel) := 0.U
        }.otherwise {
          j(sel) := j(sel) + 1.U
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
  
  // Büchi automaton
  val state = RegInit(States.n4)
  
  // Input signals for Büchi
  val pc0L12 = pc(0) === Loc.L12
  val pc1L12 = pc(1) === Loc.L12
  val pc2L12 = pc(2) === Loc.L12
  val pc0L16 = pc(0) === Loc.L16
  val pc1L16 = pc(1) === Loc.L16
  val pc2L16 = pc(2) === Loc.L16
  val pc0L1 = pc(0) === Loc.L1
  
  // Büchi state transitions
  switch(state) {
    is(States.n4) {
      when(!pc0L1 && !pc0L12) {
        state := States.n4
      }.elsewhen(pc0L1 && !pc0L12) {
        // Nondeterministic choice: prioritize n31 over n4
        state := States.n31
      }.elsewhen(pc0L1 && pc0L12) {
        state := States.n4
      }
    }
    is(States.n31) {
      when(!pc0L12) {
        state := States.n40
      }.otherwise {
        state := States.Trap
      }
    }
    is(States.Trap) {
      state := States.Trap
    }
    is(States.n2, States.n11, States.n12, States.n19, States.n20, States.n21, 
       States.n30, States.n32, States.n34, States.n38, States.n40, States.n42, 
       States.n43, States.n47, States.n48) {
      when(!pc0L12 && !pc1L12 && !pc1L16 && !pc2L12 && !pc2L16) {
        // Nondeterministic choice: prioritize n11
        state := States.n11
      }.elsewhen(!pc0L12 && !pc1L12 && !pc1L16 && !pc2L12 && pc2L16) {
        // Nondeterministic choice: prioritize n11
        state := States.n11
      }.elsewhen(!pc0L12 && !pc1L12 && !pc1L16 && pc2L12 && !pc2L16) {
        // Nondeterministic choice: prioritize n12
        state := States.n12
      }.elsewhen(!pc0L12 && !pc1L12 && !pc1L16 && pc2L12 && pc2L16) {
        // Nondeterministic choice: prioritize n12
        state := States.n12
      }.elsewhen(!pc0L12 && !pc1L12 && pc1L16 && !pc2L12 && !pc2L16) {
        // Nondeterministic choice: prioritize n11
        state := States.n11
      }.elsewhen(!pc0L12 && !pc1L12 && pc1L16 && !pc2L12 && pc2L16) {
        // Nondeterministic choice: prioritize n11
        state := States.n11
      }.elsewhen(!pc0L12 && !pc1L12 && pc1L16 && pc2L12 && !pc2L16) {
        // Nondeterministic choice: prioritize n12
        state := States.n12
      }.elsewhen(!pc0L12 && !pc1L12 && pc1L16 && pc2L12 && pc2L16) {
        // Nondeterministic choice: prioritize n12
        state := States.n12
      }.elsewhen(!pc0L12 && pc1L12 && !pc1L16 && !pc2L12 && !pc2L16) {
        // Nondeterministic choice: prioritize n2
        state := States.n2
      }.elsewhen(!pc0L12 && pc1L12 && !pc1L16 && !pc2L12 && pc2L16) {
        // Nondeterministic choice: prioritize n2
        state := States.n2
      }.elsewhen(!pc0L12 && pc1L12 && !pc1L16 && pc2L12 && !pc2L16) {
        // Nondeterministic choice: prioritize n34
        state := States.n34
      }.elsewhen(!pc0L12 && pc1L12 && !pc1L16 && pc2L12 && pc2L16) {
        // Nondeterministic choice: prioritize n38
        state := States.n38
      }.elsewhen(!pc0L12 && pc1L12 && pc1L16 && !pc2L12 && !pc2L16) {
        // Nondeterministic choice: prioritize n20
        state := States.n20
      }.elsewhen(!pc0L12 && pc1L12 && pc1L16 && !pc2L12 && pc2L16) {
        // Nondeterministic choice: prioritize n20
        state := States.n20
      }.elsewhen(!pc0L12 && pc1L12 && pc1L16 && pc2L12 && !pc2L16) {
        // Nondeterministic choice: prioritize n34
        state := States.n34
      }.elsewhen(!pc0L12 && pc1L12 && pc1L16 && pc2L12 && pc2L16) {
        state := States.n40
      }.elsewhen(pc0L12) {
        state := States.Trap
      }
    }
  }
  
  // Fairness conditions
  io.fair0 := (state === States.n2) || (state === States.n32) || (state === States.n38) || 
              (state === States.n42) || (state === States.n43) || (state === States.n19) || 
              (state === States.n47)
  io.fair1 := (state === States.n2) || (state === States.n30) || (state === States.n32) || 
              (state === States.n11) || (state === States.n43) || (state === States.n48) || 
              (state === States.n20)
  io.fair2 := (state === States.n30) || (state === States.n34) || (state === States.n42) || 
              (state === States.n43) || (state === States.n48) || (state === States.n47) || 
              (state === States.n21)
  io.fair3 := (state === States.n32) || (state === States.n11) || (state === States.n12) || 
              (state === States.n19) || (state === States.n48) || (state === States.n47) || 
              (state === States.n21)
  
  io.scc := (state === States.n20) || (state === States.n2) || (state === States.n11) || 
            (state === States.n21) || (state === States.n30) || (state === States.n12) || 
            (state === States.n40) || (state === States.n32) || (state === States.n42) || 
            (state === States.n34) || (state === States.n43) || (state === States.n19) || 
            (state === States.n38) || (state === States.n47) || (state === States.n48)
  
  // Output internal signals for verification
  io.flag0 := flag(0)
  io.flag1 := flag(1)
  io.flag2 := flag(2)
  io.pc0 := pc(0)
  io.pc1 := pc(1)
  io.pc2 := pc(2)
  io.turn := turn
}

object VerilogGenerator extends App {
  emitVerilog(new EisenbergLTLM1(), args)
}