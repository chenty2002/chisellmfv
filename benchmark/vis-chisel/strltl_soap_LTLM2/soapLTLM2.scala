package llmverify

import chisel3._
import chisel3.util._

object VerilogGenerator extends App {
  emitVerilog(new hotel(), args)
}

// Direction constants
object Directions {
  val N = 0.U(3.W)
  val W = 1.U(3.W)
  val S = 2.U(3.W)
  val E = 3.U(3.W)
  val SELF = 4.U(3.W)
}

// Condition constants
object Conditions {
  val clean = 0.U(2.W)
  val dirty = 1.U(2.W)
  val showering = 2.U(2.W)
}

// Activity constants
object Activities {
  val idle = 0.U(1.W)
  val busy = 1.U(1.W)
}

// Decoder module
class decoder extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(3.W))
    val en = Input(Bool())
    val dec = Output(UInt(4.W))
  })
  
  io.dec := 0.U
  when(io.en) {
    switch(io.in) {
      is(0.U) { io.dec := "b0001".U }
      is(1.U) { io.dec := "b0010".U }
      is(2.U) { io.dec := "b0100".U }
      is(3.U) { io.dec := "b1000".U }
    }
  }
}

// Guest module
class guest extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val reset = Input(Bool())
    val start = Input(Bool())
    val reqIn = Input(UInt(4.W))
    val initpred = Input(UInt(3.W))
    val granted = Input(UInt(4.W))
    val shower = Output(Bool())
    val reqOut = Output(UInt(4.W))
    val grant = Output(UInt(4.W))
    val condition = Output(UInt(2.W))
    val outproceed = Output(Bool())
  })
  
  // State registers
  val conditionReg = RegInit(Conditions.clean)
  val activity = RegInit(Activities.idle)
  val predecessor = RegInit(io.initpred)
  val serving = RegInit(Directions.SELF)
  val requestReg = RegInit(0.U(5.W))
  
  // Helper functions
  def select(in: UInt, sel: UInt): Bool = {
    val result = Wire(Bool())
    result := false.B
    switch(sel) {
      is(0.U) { result := in(0) }
      is(1.U) { result := in(1) }
      is(2.U) { result := in(2) }
      is(3.U) { result := in(3) }
      is(4.U) { result := in(4) }
    }
    result
  }
  
  def incMod5(op: UInt): UInt = {
    Mux(op === 4.U, 0.U, op + 1.U)
  }
  
  def pickRequest(req: UInt, rrobin: UInt): UInt = {
    // Use priority encoder to avoid combinational loops
    val cand0 = incMod5(rrobin)
    val cand1 = incMod5(cand0)
    val cand2 = incMod5(cand1)
    val cand3 = incMod5(cand2)
    val cand4 = incMod5(cand3)
    
    val valid0 = select(req, cand0)
    val valid1 = select(req, cand1)
    val valid2 = select(req, cand2)
    val valid3 = select(req, cand3)
    val valid4 = select(req, cand4)
    
    // Find first valid candidate
    val result = Wire(UInt(3.W))
    result := rrobin // default
    
    when(valid0) { result := cand0 }
    .elsewhen(valid1) { result := cand1 }
    .elsewhen(valid2) { result := cand2 }
    .elsewhen(valid3) { result := cand3 }
    .elsewhen(valid4) { result := cand4 }
    
    result
  }
  
  // Combinational logic
  val soap = predecessor === Directions.SELF
  val toBeServed = pickRequest(requestReg, serving)
  val requestPending = requestReg =/= 0.U
  
  // Decoder instances
  val d1 = Module(new decoder)
  d1.io.in := serving
  d1.io.en := soap
  val grant = d1.io.dec
  
  val d2 = Module(new decoder)
  d2.io.in := serving
  d2.io.en := true.B
  val mbar = d2.io.dec
  
  val d3 = Module(new decoder)
  d3.io.in := predecessor
  d3.io.en := activity === Activities.busy
  val reqOut = d3.io.dec
  
  val mask = ~mbar
  val soapIsComing = select(Cat(0.U(1.W), io.granted), predecessor)
  val proceed = Wire(Bool())
  proceed := true.B // Simplified non-deterministic choice
  
  // Next state logic
  when(io.reset) {
    conditionReg := Conditions.clean
    activity := Activities.idle
    predecessor := io.initpred
    requestReg := 0.U
    serving := Directions.SELF
  }.otherwise {
    // Update request register
    requestReg := Cat(requestReg(4), io.reqIn & mask)
    
    // Condition state machine
    switch(conditionReg) {
      is(Conditions.clean) {
        when(io.start) {
          conditionReg := Conditions.dirty
          requestReg := Cat(1.U(1.W), requestReg(3,0))
        }
      }
      is(Conditions.dirty) {
        when(soap && serving === Directions.SELF) {
          conditionReg := Conditions.showering
          requestReg := Cat(0.U(1.W), requestReg(3,0))
        }
      }
      is(Conditions.showering) {
        when(proceed) {
          conditionReg := Conditions.clean
        }
      }
    }
    
    // Activity state machine
    switch(activity) {
      is(Activities.idle) {
        when(requestPending && conditionReg =/= Conditions.showering) {
          serving := toBeServed
          activity := Activities.busy
        }
      }
      is(Activities.busy) {
        when(soapIsComing) {
          predecessor := Directions.SELF
        }.elsewhen(soap) {
          predecessor := serving
          activity := Activities.idle
        }
      }
    }
  }
  
  // Outputs
  io.shower := conditionReg === Conditions.showering
  io.reqOut := reqOut
  io.grant := grant
  io.condition := conditionReg
  io.outproceed := proceed
}

// Monitor module
class monitor extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val condition = Input(UInt(2.W))
    val fair = Output(Bool())
  })
  
  val state = RegInit(0.U(2.W))
  val zeroorone = Wire(UInt(2.W))
  zeroorone := 1.U // Simplified non-deterministic choice
  
  io.fair := state === 1.U
  
  switch(state) {
    is(0.U) {
      when(io.condition === 1.U) {
        state := zeroorone
      }.otherwise {
        state := 0.U
      }
    }
    is(1.U) {
      when(io.condition === 0.U) {
        state := 2.U
      }.otherwise {
        state := 1.U
      }
    }
    is(2.U) {
      state := 2.U
    }
  }
}

// Buechi automaton states
object BuechiStates {
  val Init = 0.U(4.W)
  val n1 = 1.U(4.W)
  val n3 = 2.U(4.W)
  val n6 = 3.U(4.W)
  val n7 = 4.U(4.W)
  val n8 = 5.U(4.W)
  val n9 = 6.U(4.W)
  val n10 = 7.U(4.W)
  val n12 = 8.U(4.W)
  val n13 = 9.U(4.W)
  val n16 = 10.U(4.W)
  val n17 = 11.U(4.W)
  val n18 = 12.U(4.W)
  val Trap = 13.U(4.W)
}

// Buechi module
class Buechi extends Module {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val Bclean = Input(Bool())
    val Aproceed = Input(Bool())
    val Bdirty = Input(Bool())
    val Adirty = Input(Bool())
    val Aclean = Input(Bool())
    val fair0 = Output(Bool())
    val fair1 = Output(Bool())
    val scc = Output(Bool())
  })
  
  val state = RegInit(BuechiStates.Init)
  
  // Simplified non-deterministic choices - return first option
  def ND(choices: UInt*): UInt = {
    choices.head
  }
  
  val ND_n12_n13_n16_n17_n3_n8 = ND(BuechiStates.n12, BuechiStates.n13, BuechiStates.n16, BuechiStates.n17, BuechiStates.n3, BuechiStates.n8)
  val ND_n12_n13 = ND(BuechiStates.n12, BuechiStates.n13)
  val ND_n6_n9 = ND(BuechiStates.n6, BuechiStates.n9)
  val ND_n13_n3 = ND(BuechiStates.n13, BuechiStates.n3)
  val ND_n1_n10_n18_n6_n7_n9 = ND(BuechiStates.n1, BuechiStates.n10, BuechiStates.n18, BuechiStates.n6, BuechiStates.n7, BuechiStates.n9)
  val ND_n1_n7 = ND(BuechiStates.n1, BuechiStates.n7)
  val ND_n1_n18_n6_n9 = ND(BuechiStates.n1, BuechiStates.n18, BuechiStates.n6, BuechiStates.n9)
  val ND_n12_n13_n17_n3 = ND(BuechiStates.n12, BuechiStates.n13, BuechiStates.n17, BuechiStates.n3)
  val ND_n1_n10_n18_n7 = ND(BuechiStates.n1, BuechiStates.n10, BuechiStates.n18, BuechiStates.n7)
  val ND_n12_n13_n16 = ND(BuechiStates.n12, BuechiStates.n13, BuechiStates.n16)
  val ND_n1_n18 = ND(BuechiStates.n1, BuechiStates.n18)
  val ND_n1_n9 = ND(BuechiStates.n1, BuechiStates.n9)
  val ND_n13_n16 = ND(BuechiStates.n13, BuechiStates.n16)
  val ND_n1_n7_n9 = ND(BuechiStates.n1, BuechiStates.n7, BuechiStates.n9)
  val ND_n13_n16_n3_n8 = ND(BuechiStates.n13, BuechiStates.n16, BuechiStates.n3, BuechiStates.n8)
  
  io.fair0 := (state === BuechiStates.n8) || (state === BuechiStates.n12) || (state === BuechiStates.n17) || (state === BuechiStates.n16)
  io.fair1 := (state === BuechiStates.n3) || (state === BuechiStates.n8) || (state === BuechiStates.n17)
  io.scc := (state === BuechiStates.n3) || (state === BuechiStates.n12) || (state === BuechiStates.n13) || (state === BuechiStates.n16) || (state === BuechiStates.n17) || (state === BuechiStates.n8)
  
  // State transition logic
  when(state === BuechiStates.n8 || state === BuechiStates.n12 || state === BuechiStates.n16 || state === BuechiStates.n17) {
    when(!io.Aclean && !io.Adirty && !io.Aproceed && !io.Bclean) {
      state := ND_n12_n13
    }.elsewhen(io.Aproceed) {
      state := BuechiStates.Trap
    }.elsewhen(!io.Aclean && !io.Adirty && io.Aproceed && !io.Bclean) {
      state := ND_n12_n13_n17_n3
    }.elsewhen(!io.Aclean && io.Adirty && !io.Aproceed && !io.Bclean) {
      state := BuechiStates.n13
    }.elsewhen(!io.Aclean && io.Adirty && io.Aproceed && !io.Bclean) {
      state := ND_n13_n3
    }.elsewhen(io.Aclean && !io.Adirty && !io.Aproceed && !io.Bclean) {
      state := ND_n12_n13_n16
    }.elsewhen(io.Aclean && !io.Adirty && io.Aproceed && !io.Bclean) {
      state := ND_n12_n13_n16_n17_n3_n8
    }.elsewhen(io.Aclean && io.Adirty && !io.Aproceed && !io.Bclean) {
      state := ND_n13_n16
    }.elsewhen(io.Aclean && io.Adirty && io.Aproceed && !io.Bclean) {
      state := ND_n13_n16_n3_n8
    }
  }.elsewhen(state === BuechiStates.n1) {
    when(!io.Aclean && !io.Bclean && !io.Bdirty) {
      state := BuechiStates.n1
    }.elsewhen(!io.Aclean && !io.Bclean && io.Bdirty) {
      state := ND_n1_n18
    }.elsewhen(!io.Aclean && io.Bclean) {
      state := BuechiStates.n1
    }.elsewhen(io.Aclean && !io.Bclean && !io.Bdirty) {
      state := ND_n1_n7
    }.elsewhen(io.Aclean && !io.Bclean && io.Bdirty) {
      state := ND_n1_n10_n18_n7
    }.elsewhen(io.Aclean && io.Bclean) {
      state := ND_n1_n7
    }
  }.elsewhen(state === BuechiStates.Trap) {
    state := BuechiStates.Trap
  }.elsewhen(state === BuechiStates.n18) {
    when(!io.Aclean && !io.Bclean) {
      state := BuechiStates.n13
    }.elsewhen(io.Bclean) {
      state := BuechiStates.Trap
    }.elsewhen(io.Aclean && !io.Bclean) {
      state := ND_n13_n16
    }
  }.elsewhen(state === BuechiStates.Init) {
    when(!io.Aclean && !io.Adirty && !io.Bclean && !io.Bdirty) {
      state := ND_n1_n9
    }.elsewhen(!io.Aclean && !io.Adirty && !io.Bclean && io.Bdirty) {
      state := ND_n1_n18_n6_n9
    }.elsewhen(!io.Aclean && !io.Adirty && io.Bclean) {
      state := ND_n1_n9
    }.elsewhen(!io.Aclean && io.Adirty && !io.Bclean && !io.Bdirty) {
      state := BuechiStates.n1
    }.elsewhen(!io.Aclean && io.Adirty && !io.Bclean && io.Bdirty) {
      state := ND_n1_n18
    }.elsewhen(!io.Aclean && io.Adirty && io.Bclean) {
      state := BuechiStates.n1
    }.elsewhen(io.Aclean && !io.Adirty && !io.Bclean && !io.Bdirty) {
      state := ND_n1_n7_n9
    }.elsewhen(io.Aclean && !io.Adirty && !io.Bclean && io.Bdirty) {
      state := ND_n1_n10_n18_n6_n7_n9
    }.elsewhen(io.Aclean && !io.Adirty && io.Bclean) {
      state := ND_n1_n7_n9
    }.elsewhen(io.Aclean && io.Adirty && !io.Bclean && !io.Bdirty) {
      state := ND_n1_n7
    }.elsewhen(io.Aclean && io.Adirty && !io.Bclean && io.Bdirty) {
      state := ND_n1_n10_n18_n7
    }.elsewhen(io.Aclean && io.Adirty && io.Bclean) {
      state := ND_n1_n7
    }
  }.elsewhen(state === BuechiStates.n3 || state === BuechiStates.n13) {
    when(!io.Aclean && !io.Aproceed && !io.Bclean) {
      state := BuechiStates.n13
    }.elsewhen(io.Aproceed) {
      state := BuechiStates.Trap
    }.elsewhen(!io.Aclean && io.Aproceed && io.Bclean) {
      state := ND_n13_n3
    }.elsewhen(io.Aclean && !io.Aproceed && !io.Bclean) {
      state := ND_n13_n16
    }.elsewhen(io.Aclean && io.Aproceed && io.Bclean) {
      state := ND_n13_n16_n3_n8
    }
  }.elsewhen(state === BuechiStates.n7 || state === BuechiStates.n9) {
    when(!io.Adirty && !io.Bclean && !io.Bdirty) {
      state := BuechiStates.n9
    }.elsewhen(!io.Adirty && !io.Bclean && io.Bdirty) {
      state := ND_n6_n9
    }.elsewhen(!io.Adirty && io.Bclean) {
      state := BuechiStates.n9
    }.elsewhen(io.Adirty) {
      state := BuechiStates.Trap
    }
  }.elsewhen(state === BuechiStates.n6 || state === BuechiStates.n10) {
    when(!io.Adirty && !io.Bclean) {
      state := BuechiStates.n12
    }.elsewhen(!io.Adirty && io.Bclean) {
      state := BuechiStates.Trap
    }.elsewhen(io.Adirty) {
      state := BuechiStates.Trap
    }
  }
}

// Hotel top-level module
class hotel extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val start = Input(UInt(10.W))
    val showering = Output(UInt(10.W))
    // Additional outputs to preserve design
    val fair0 = Output(Bool())
    val fair1 = Output(Bool())
    val scc = Output(Bool())
  })
  
  // Guest instances
  val guests = Array.fill(10)(Module(new guest()))
  
  // Request and grant wires
  val reqIn = Wire(Vec(10, UInt(4.W)))
  val reqOut = Wire(Vec(10, UInt(4.W)))
  val granted = Wire(Vec(10, UInt(4.W)))
  val grant = Wire(Vec(10, UInt(4.W)))
  val condition = Wire(Vec(10, UInt(2.W)))
  val outproceed = Wire(Vec(10, Bool()))
  
  // Connect guests
  for (i <- 0 until 10) {
    guests(i).io.clk := io.clk
    guests(i).io.reset := io.rst
    guests(i).io.start := io.start(i)
    guests(i).io.reqIn := reqIn(i)
    guests(i).io.granted := granted(i)
    reqOut(i) := guests(i).io.reqOut
    grant(i) := guests(i).io.grant
    condition(i) := guests(i).io.condition
    outproceed(i) := guests(i).io.outproceed
  }
  
  // Set initial predecessors (spanning tree)
  guests(0).io.initpred := Directions.S  // A
  guests(1).io.initpred := Directions.S  // B
  guests(2).io.initpred := Directions.E  // C
  guests(3).io.initpred := Directions.SELF  // D (root)
  guests(4).io.initpred := Directions.N  // E
  guests(5).io.initpred := Directions.E  // F
  guests(6).io.initpred := Directions.W  // G
  guests(7).io.initpred := Directions.S  // H
  guests(8).io.initpred := Directions.W  // I
  guests(9).io.initpred := Directions.S  // J
  
  // Connection matrix
  granted(0) := Cat(0.U(1.W), grant(1)(0), 0.U(2.W))  // A <- B[N]
  reqIn(0) := Cat(0.U(1.W), reqOut(1)(0), 0.U(2.W))
  
  granted(1) := Cat(grant(8)(1), grant(3)(0), grant(2)(3), grant(0)(2))  // B
  reqIn(1) := Cat(reqOut(8)(1), reqOut(3)(0), reqOut(2)(3), reqOut(0)(2))
  
  granted(2) := Cat(grant(1)(1), grant(9)(0), 0.U(2.W))  // C
  reqIn(2) := Cat(reqOut(1)(1), reqOut(9)(0), 0.U(2.W))
  
  granted(3) := Cat(grant(7)(1), grant(4)(0), 0.U(1.W), grant(1)(2))  // D
  reqIn(3) := Cat(reqOut(7)(1), reqOut(4)(0), 0.U(1.W), reqOut(1)(2))
  
  granted(4) := Cat(grant(6)(1), 0.U(1.W), grant(5)(3), grant(3)(2))  // E
  reqIn(4) := Cat(reqOut(6)(1), 0.U(1.W), reqOut(5)(3), reqOut(3)(2))
  
  granted(5) := Cat(grant(4)(1), 0.U(2.W), grant(9)(2))  // F
  reqIn(5) := Cat(reqOut(4)(1), 0.U(2.W), reqOut(9)(2))
  
  granted(6) := Cat(0.U(2.W), grant(4)(3), grant(7)(2))  // G
  reqIn(6) := Cat(0.U(2.W), reqOut(4)(3), reqOut(7)(2))
  
  granted(7) := Cat(0.U(1.W), grant(6)(0), grant(3)(3), grant(8)(2))  // H
  reqIn(7) := Cat(0.U(1.W), reqOut(6)(0), reqOut(3)(3), reqOut(8)(2))
  
  granted(8) := Cat(0.U(1.W), grant(7)(0), grant(1)(3), 0.U(1.W))  // I
  reqIn(8) := Cat(0.U(1.W), reqOut(7)(0), reqOut(1)(3), 0.U(1.W))
  
  granted(9) := Cat(0.U(1.W), grant(5)(0), 0.U(1.W), grant(2)(2))  // J
  reqIn(9) := Cat(0.U(1.W), reqOut(5)(0), 0.U(1.W), reqOut(2)(2))
  
  // Output showering signals
  io.showering := Cat(
    guests(9).io.shower,
    guests(8).io.shower,
    guests(7).io.shower,
    guests(6).io.shower,
    guests(5).io.shower,
    guests(4).io.shower,
    guests(3).io.shower,
    guests(2).io.shower,
    guests(1).io.shower,
    guests(0).io.shower
  )
  
  // Compositional LTL model checking signals
  val Adirty = condition(0) === Conditions.dirty
  val Aclean = condition(0) === Conditions.clean
  val Aproceed = outproceed(0)
  val Bdirty = condition(1) === Conditions.dirty
  val Bclean = condition(1) === Conditions.clean
  val Bproceed = outproceed(1)
  
  // Buechi automaton instance
  val buechi = Module(new Buechi())
  buechi.io.clock := io.clk
  buechi.io.Bclean := Bclean
  buechi.io.Aproceed := Aproceed
  buechi.io.Bdirty := Bdirty
  buechi.io.Adirty := Adirty
  buechi.io.Aclean := Aclean
  
  io.fair0 := buechi.io.fair0
  io.fair1 := buechi.io.fair1
  io.scc := buechi.io.scc
}