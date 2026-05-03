package llmverify

import chisel3._
import chisel3.util._

// Constants for directions
class Directions {
  val N = 0.U(3.W)
  val W = 1.U(3.W)
  val S = 2.U(3.W)
  val E = 3.U(3.W)
  val SELF = 4.U(3.W)
}

// Constants for conditions
class Conditions {
  val clean = 0.U(2.W)
  val dirty = 1.U(2.W)
  val showering = 2.U(2.W)
}

// Decoder module
class Decoder extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(3.W))
    val en = Input(Bool())
    val dec = Output(UInt(4.W))
  })
  
  io.dec := Cat(
    io.en && !io.in(2) && !io.in(1) && io.in(0),  // bit 3
    io.en && !io.in(2) && io.in(1) && !io.in(0),  // bit 2
    io.en && !io.in(2) && !io.in(1) && io.in(0),  // bit 1
    io.en && !io.in(2) && !io.in(1) && !io.in(0)  // bit 0
  )
}

// Guest module
class Guest extends Module {
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
  
  val dirs = new Directions()
  val conds = new Conditions()
  
  // State registers
  val conditionReg = RegInit(conds.clean)
  val activity = RegInit(0.U(1.W)) // 0=idle, 1=busy
  val predecessor = RegInit(io.initpred)
  val serving = RegInit(dirs.SELF)
  val requestReg = RegInit(0.U(5.W)) // N,W,S,E,SELF
  
  // Helper functions
  def select(in: UInt, sel: UInt): Bool = {
    val result = Wire(Bool())
    result := false.B
    when(sel === 0.U(3.W)) { result := in(0) }
    .elsewhen(sel === 1.U(3.W)) { result := in(1) }
    .elsewhen(sel === 2.U(3.W)) { result := in(2) }
    .elsewhen(sel === 3.U(3.W)) { result := in(3) }
    .elsewhen(sel === 4.U(3.W)) { result := in(4) }
    result
  }
  
  def incMod5(op: UInt): UInt = {
    Mux(op === 4.U, 0.U, op + 1.U)
  }
  
  def pickRequest(req: UInt, rrobin: UInt): UInt = {
    val candidates = VecInit(Seq(
      incMod5(rrobin),           // candidate 0
      incMod5(incMod5(rrobin)),  // candidate 1
      incMod5(incMod5(incMod5(rrobin))), // candidate 2
      incMod5(incMod5(incMod5(incMod5(rrobin))), // candidate 3
      incMod5(incMod5(incMod5(incMod5(incMod5(rrobin)))) // candidate 4
    ))
    
    val result = Wire(UInt(3.W))
    result := candidates(0) // default to first candidate
    
    when(req =/= 0.U) {
      when(select(req, candidates(0)) === false.B) {
        result := candidates(1)
        when(select(req, candidates(1)) === false.B) {
          result := candidates(2)
          when(select(req, candidates(2)) === false.B) {
            result := candidates(3)
            when(select(req, candidates(3)) === false.B) {
              result := candidates(4)
            }
          }
        }
      }
    }
    result
  }
  
  // Combinational logic
  val soap = Wire(Bool())
  val toBeServed = Wire(UInt(3.W))
  val requestPending = Wire(Bool())
  val mask = Wire(UInt(4.W))
  val mbar = Wire(UInt(4.W))
  val soapIsComing = Wire(Bool())
  val proceed = Wire(Bool())
  
  soap := predecessor === dirs.SELF
  toBeServed := pickRequest(requestReg, serving)
  requestPending := requestReg =/= 0.U
  soapIsComing := select(Cat(0.U(1.W), io.granted), predecessor)
  proceed := WireInit(0.B) // Simplified from $ND(0,1)
  
  // Instantiate decoders
  val decoder1 = Module(new Decoder())
  decoder1.io.in := serving
  decoder1.io.en := soap
  
  val decoder2 = Module(new Decoder())
  decoder2.io.in := serving
  decoder2.io.en := true.B
  
  val decoder3 = Module(new Decoder())
  decoder3.io.in := predecessor
  decoder3.io.en := activity === 1.U
  
  mask := ~mbar
  mbar := decoder2.io.dec
  
  // Next state logic
  when(io.reset) {
    conditionReg := conds.clean
    activity := 0.U
    predecessor := io.initpred
    requestReg := 0.U
    serving := dirs.SELF
  }.otherwise {
    // Update request register - fix the bit assignment issue
    val newRequestReg = Wire(UInt(5.W))
    newRequestReg := Cat(requestReg(4), io.reqIn & mask)
    requestReg := newRequestReg
    
    // Condition state machine
    switch(conditionReg) {
      is(conds.clean) {
        when(io.start) {
          conditionReg := conds.dirty
          requestReg := Cat(1.U(1.W), io.reqIn & mask) // Set SELF bit
        }
      }
      is(conds.dirty) {
        when(soap && serving === dirs.SELF) {
          conditionReg := conds.showering
          requestReg := Cat(0.U(1.W), io.reqIn & mask) // Clear SELF bit
        }
      }
      is(conds.showering) {
        when(proceed) {
          conditionReg := conds.clean
        }
      }
    }
    
    // Activity state machine
    switch(activity) {
      is(0.U) { // idle
        when(requestPending && conditionReg =/= conds.showering) {
          serving := toBeServed
          activity := 1.U
        }
      }
      is(1.U) { // busy
        when(soapIsComing) {
          predecessor := dirs.SELF
        }.elsewhen(soap) {
          predecessor := serving
          activity := 0.U
        }
      }
    }
  }
  
  // Outputs
  io.shower := conditionReg === conds.showering
  io.reqOut := decoder3.io.dec
  io.grant := decoder1.io.dec
  io.condition := conditionReg
  io.outproceed := proceed
}

// Buechi automaton states
object BuechiStates {
  val Init = 0.U(3.W)
  val n1 = 1.U(3.W)
  val n2 = 2.U(3.W)
  val n3 = 3.U(3.W)
  val n4 = 4.U(3.W)
  val n5 = 5.U(3.W)
  val n6 = 6.U(3.W)
  val Trap = 7.U(3.W)
}

// Buechi automaton module
class Buechi extends Module {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val Aclean = Input(Bool())
    val Bproceed = Input(Bool())
    val Adirty = Input(Bool())
    val fair = Output(Bool())
    val scc = Output(Bool())
  })
  
  import BuechiStates._
  
  val state = RegInit(Init)
  
  // Simplified nondeterministic choices
  val ND_n3_n4_n6 = WireInit(n3) // Simplified from $ND
  val ND_n3_n6 = WireInit(n3)
  val ND_n4_n5 = WireInit(n4)
  val ND_n1_n2_n3 = WireInit(n1)
  val ND_n1_n2_n3_n4_n5 = WireInit(n1)
  val ND_n4_n6 = WireInit(n4)
  val ND_n1_n2 = WireInit(n1)
  val ND_n1_n2_n5 = WireInit(n1)
  val ND_n1_n4_n5 = WireInit(n1)
  val ND_n1_n5 = WireInit(n1)
  
  io.fair := (state === n4) || (state === n3)
  io.scc := (state === n3) || (state === n4) || (state === n6)
  
  // State transition logic
  switch(state) {
    is(Trap) {
      state := Trap
    }
    is(n3, n4) {
      switch(Cat(io.Aclean, io.Adirty, io.Bproceed)) {
        is("b000".U) { state := ND_n4_n6 }
        is("b001".U) { state := Trap }
        is("b010".U) { state := n6 }
        is("b011".U) { state := Trap }
        is("b100".U) { state := ND_n3_n4_n6 }
        is("b110".U) { state := ND_n3_n6 }
        is("b101".U) { state := Trap }
        is("b111".U) { state := Trap }
      }
    }
    is(n1) {
      switch(Cat(io.Aclean, io.Bproceed)) {
        is("b00".U) { state := n1 }
        is("b01".U) { state := n1 }
        is("b10".U) { state := ND_n1_n2_n3 }
        is("b11".U) { state := ND_n1_n2 }
      }
    }
    is(n6) {
      switch(Cat(io.Aclean, io.Bproceed)) {
        is("b00".U) { state := n6 }
        is("b01".U) { state := Trap }
        is("b10".U) { state := ND_n3_n6 }
        is("b11".U) { state := Trap }
      }
    }
    is(n2, n5) {
      switch(Cat(io.Adirty, io.Bproceed)) {
        is("b00".U) { state := ND_n4_n5 }
        is("b01".U) { state := n5 }
        is("b10".U) { state := Trap }
        is("b11".U) { state := Trap }
      }
    }
    is(Init) {
      switch(Cat(io.Aclean, io.Adirty, io.Bproceed)) {
        is("b000".U) { state := ND_n1_n4_n5 }
        is("b001".U) { state := ND_n1_n5 }
        is("b010".U) { state := n1 }
        is("b011".U) { state := n1 }
        is("b100".U) { state := ND_n1_n2_n3_n4_n5 }
        is("b101".U) { state := ND_n1_n2_n5 }
        is("b110".U) { state := ND_n1_n2_n3 }
        is("b111".U) { state := ND_n1_n2 }
      }
    }
  }
}

// Monitor module
class Monitor extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val condition = Input(UInt(2.W))
    val fair = Output(Bool())
  })
  
  val conds = new Conditions()
  
  val state = RegInit(0.U(2.W))
  val zeroorone = Wire(UInt(2.W))
  
  zeroorone := Cat(0.U(1.W), WireInit(0.B)) // Simplified from $ND(0,1)
  
  io.fair := (state === 1.U)
  
  switch(state) {
    is(0.U) {
      state := Mux(io.condition === conds.dirty, zeroorone, 0.U)
    }
    is(1.U) {
      state := Mux(io.condition === conds.clean, 2.U, 1.U)
    }
    is(2.U) {
      state := 2.U
    }
  }
}

// Top-level hotel module
class Hotel extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val start = Input(UInt(10.W))
    val showering = Output(UInt(10.W))
    val fair = Output(Bool())
  })
  
  val dirs = new Directions()
  val conds = new Conditions()
  
  // Request and grant wires for each guest
  val reqInA = Wire(UInt(4.W))
  val reqInB = Wire(UInt(4.W))
  val reqInC = Wire(UInt(4.W))
  val reqInD = Wire(UInt(4.W))
  val reqInE = Wire(UInt(4.W))
  val reqInF = Wire(UInt(4.W))
  val reqInG = Wire(UInt(4.W))
  val reqInH = Wire(UInt(4.W))
  val reqInI = Wire(UInt(4.W))
  val reqInJ = Wire(UInt(4.W))
  
  val reqOutA = Wire(UInt(4.W))
  val reqOutB = Wire(UInt(4.W))
  val reqOutC = Wire(UInt(4.W))
  val reqOutD = Wire(UInt(4.W))
  val reqOutE = Wire(UInt(4.W))
  val reqOutF = Wire(UInt(4.W))
  val reqOutG = Wire(UInt(4.W))
  val reqOutH = Wire(UInt(4.W))
  val reqOutI = Wire(UInt(4.W))
  val reqOutJ = Wire(UInt(4.W))
  
  val grantedA = Wire(UInt(4.W))
  val grantedB = Wire(UInt(4.W))
  val grantedC = Wire(UInt(4.W))
  val grantedD = Wire(UInt(4.W))
  val grantedE = Wire(UInt(4.W))
  val grantedF = Wire(UInt(4.W))
  val grantedG = Wire(UInt(4.W))
  val grantedH = Wire(UInt(4.W))
  val grantedI = Wire(UInt(4.W))
  val grantedJ = Wire(UInt(4.W))
  
  val grantA = Wire(UInt(4.W))
  val grantB = Wire(UInt(4.W))
  val grantC = Wire(UInt(4.W))
  val grantD = Wire(UInt(4.W))
  val grantE = Wire(UInt(4.W))
  val grantF = Wire(UInt(4.W))
  val grantG = Wire(UInt(4.W))
  val grantH = Wire(UInt(4.W))
  val grantI = Wire(UInt(4.W))
  val grantJ = Wire(UInt(4.W))
  
  val conditionA = Wire(UInt(2.W))
  val conditionB = Wire(UInt(2.W))
  val conditionC = Wire(UInt(2.W))
  val conditionD = Wire(UInt(2.W))
  val conditionE = Wire(UInt(2.W))
  val conditionF = Wire(UInt(2.W))
  val conditionG = Wire(UInt(2.W))
  val conditionH = Wire(UInt(2.W))
  val conditionI = Wire(UInt(2.W))
  val conditionJ = Wire(UInt(2.W))
  
  val outproceedA = Wire(Bool())
  val outproceedB = Wire(Bool())
  val outproceedC = Wire(Bool())
  val outproceedD = Wire(Bool())
  val outproceedE = Wire(Bool())
  val outproceedF = Wire(Bool())
  val outproceedG = Wire(Bool())
  val outproceedH = Wire(Bool())
  val outproceedI = Wire(Bool())
  val outproceedJ = Wire(Bool())
  
  // Connection matrix assignments
  grantedA := Cat(0.U(1.W), grantB(0), 0.U(1.W), 0.U(1.W)) // E,S,W,N
  reqInA := Cat(0.U(1.W), reqOutB(0), 0.U(1.W), 0.U(1.W))
  
  grantedB := Cat(grantI(1), grantD(0), grantC(3), grantA(2))
  reqInB := Cat(reqOutI(1), reqOutD(0), reqOutC(3), reqOutA(2))
  
  grantedC := Cat(grantB(1), grantJ(0), 0.U(1.W), 0.U(1.W))
  reqInC := Cat(reqOutB(1), reqOutJ(0), 0.U(1.W), 0.U(1.W))
  
  grantedD := Cat(grantH(1), grantE(0), 0.U(1.W), grantB(2))
  reqInD := Cat(reqOutH(1), reqOutE(0), 0.U(1.W), reqOutB(2))
  
  grantedE := Cat(grantG(1), 0.U(1.W), grantF(3), grantD(2))
  reqInE := Cat(reqOutG(1), 0.U(1.W), reqOutF(3), reqOutD(2))
  
  grantedF := Cat(grantE(1), 0.U(1.W), 0.U(1.W), grantJ(2))
  reqInF := Cat(reqOutE(1), 0.U(1.W), 0.U(1.W), reqOutJ(2))
  
  grantedG := Cat(0.U(1.W), 0.U(1.W), grantE(3), grantH(2))
  reqInG := Cat(0.U(1.W), 0.U(1.W), reqOutE(3), reqOutH(2))
  
  grantedH := Cat(0.U(1.W), grantG(0), grantD(3), grantI(2))
  reqInH := Cat(0.U(1.W), reqOutG(0), reqOutD(3), reqOutI(2))
  
  grantedI := Cat(0.U(1.W), grantH(0), grantB(3), 0.U(1.W))
  reqInI := Cat(0.U(1.W), reqOutH(0), reqOutB(3), 0.U(1.W))
  
  grantedJ := Cat(0.U(1.W), grantF(0), 0.U(1.W), grantC(2))
  reqInJ := Cat(0.U(1.W), reqOutF(0), 0.U(1.W), reqOutC(2))
  
  // Instantiate guests
  val guestA = Module(new Guest())
  guestA.io.clk := io.clk
  guestA.io.reset := io.rst
  guestA.io.start := io.start(0)
  guestA.io.reqIn := reqInA
  guestA.io.initpred := dirs.S
  guestA.io.granted := grantedA
  
  val guestB = Module(new Guest())
  guestB.io.clk := io.clk
  guestB.io.reset := io.rst
  guestB.io.start := io.start(1)
  guestB.io.reqIn := reqInB
  guestB.io.initpred := dirs.S
  guestB.io.granted := grantedB
  
  val guestC = Module(new Guest())
  guestC.io.clk := io.clk
  guestC.io.reset := io.rst
  guestC.io.start := io.start(2)
  guestC.io.reqIn := reqInC
  guestC.io.initpred := dirs.E
  guestC.io.granted := grantedC
  
  val guestD = Module(new Guest())
  guestD.io.clk := io.clk
  guestD.io.reset := io.rst
  guestD.io.start := io.start(3)
  guestD.io.reqIn := reqInD
  guestD.io.initpred := dirs.SELF
  guestD.io.granted := grantedD
  
  val guestE = Module(new Guest())
  guestE.io.clk := io.clk
  guestE.io.reset := io.rst
  guestE.io.start := io.start(4)
  guestE.io.reqIn := reqInE
  guestE.io.initpred := dirs.N
  guestE.io.granted := grantedE
  
  val guestF = Module(new Guest())
  guestF.io.clk := io.clk
  guestF.io.reset := io.rst
  guestF.io.start := io.start(5)
  guestF.io.reqIn := reqInF
  guestF.io.initpred := dirs.E
  guestF.io.granted := grantedF
  
  val guestG = Module(new Guest())
  guestG.io.clk := io.clk
  guestG.io.reset := io.rst
  guestG.io.start := io.start(6)
  guestG.io.reqIn := reqInG
  guestG.io.initpred := dirs.W
  guestG.io.granted := grantedG
  
  val guestH = Module(new Guest())
  guestH.io.clk := io.clk
  guestH.io.reset := io.rst
  guestH.io.start := io.start(7)
  guestH.io.reqIn := reqInH
  guestH.io.initpred := dirs.S
  guestH.io.granted := grantedH
  
  val guestI = Module(new Guest())
  guestI.io.clk := io.clk
  guestI.io.reset := io.rst
  guestI.io.start := io.start(8)
  guestI.io.reqIn := reqInI
  guestI.io.initpred := dirs.W
  guestI.io.granted := grantedI
  
  val guestJ = Module(new Guest())
  guestJ.io.clk := io.clk
  guestJ.io.reset := io.rst
  guestJ.io.start := io.start(9)
  guestJ.io.reqIn := reqInJ
  guestJ.io.initpred := dirs.S
  guestJ.io.granted := grantedJ
  
  // Connect outputs
  reqOutA := guestA.io.reqOut
  reqOutB := guestB.io.reqOut
  reqOutC := guestC.io.reqOut
  reqOutD := guestD.io.reqOut
  reqOutE := guestE.io.reqOut
  reqOutF := guestF.io.reqOut
  reqOutG := guestG.io.reqOut
  reqOutH := guestH.io.reqOut
  reqOutI := guestI.io.reqOut
  reqOutJ := guestJ.io.reqOut
  
  grantA := guestA.io.grant
  grantB := guestB.io.grant
  grantC := guestC.io.grant
  grantD := guestD.io.grant
  grantE := guestE.io.grant
  grantF := guestF.io.grant
  grantG := guestG.io.grant
  grantH := guestH.io.grant
  grantI := guestI.io.grant
  grantJ := guestJ.io.grant
  
  conditionA := guestA.io.condition
  conditionB := guestB.io.condition
  conditionC := guestC.io.condition
  conditionD := guestD.io.condition
  conditionE := guestE.io.condition
  conditionF := guestF.io.condition
  conditionG := guestG.io.condition
  conditionH := guestH.io.condition
  conditionI := guestI.io.condition
  conditionJ := guestJ.io.condition
  
  outproceedA := guestA.io.outproceed
  outproceedB := guestB.io.outproceed
  outproceedC := guestC.io.outproceed
  outproceedD := guestD.io.outproceed
  outproceedE := guestE.io.outproceed
  outproceedF := guestF.io.outproceed
  outproceedG := guestG.io.outproceed
  outproceedH := guestH.io.outproceed
  outproceedI := guestI.io.outproceed
  outproceedJ := guestJ.io.outproceed
  
  // Compose showering output
  io.showering := Cat(
    guestJ.io.shower,
    guestI.io.shower,
    guestH.io.shower,
    guestG.io.shower,
    guestF.io.shower,
    guestE.io.shower,
    guestD.io.shower,
    guestC.io.shower,
    guestB.io.shower,
    guestA.io.shower
  )
  
  // For compositional LTL model checking
  val Adirty = Wire(Bool())
  val Aclean = Wire(Bool())
  val Aproceed = Wire(Bool())
  val Bdirty = Wire(Bool())
  val Bclean = Wire(Bool())
  val Bproceed = Wire(Bool())
  
  Adirty := conditionA === conds.dirty
  Aclean := conditionA === conds.clean
  Aproceed := outproceedA
  
  Bdirty := conditionB === conds.dirty
  Bclean := conditionB === conds.clean
  Bproceed := outproceedB
  
  // Instantiate Buechi automaton
  val buechi = Module(new Buechi())
  buechi.io.clock := io.clk
  buechi.io.Aclean := Aclean
  buechi.io.Bproceed := Bproceed
  buechi.io.Adirty := Adirty
  io.fair := buechi.io.fair
}

object VerilogGenerator extends App {
  emitVerilog(new Hotel(), args)
}