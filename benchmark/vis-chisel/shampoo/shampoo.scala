package llmverify

import chisel3._
import chisel3.util._

// Direction constants
object Directions {
  val N = 0.U(3.W)
  val W = 1.U(3.W)
  val S = 2.U(3.W)
  val E = 3.U(3.W)
  val SELF = 4.U(3.W)
}

// Condition states for guest
object ConditionStates {
  val clean = 0.U(2.W)
  val dirty = 1.U(2.W)
  val showering = 2.U(2.W)
}

// Activity states for guest
object ActivityStates {
  val idle = 0.U(1.W)
  val busy = 1.U(1.W)
}

class Decoder extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(3.W))
    val en = Input(Bool())
    val dec = Output(UInt(4.W))
  })
  
  io.dec := Cat(
    io.en && !io.in(2) && !io.in(1) && io.in(0),  // E
    io.en && !io.in(2) && io.in(1) && !io.in(0),  // S
    io.en && !io.in(2) && !io.in(1) && io.in(0),  // W
    io.en && !io.in(2) && !io.in(1) && !io.in(0)  // N
  )
}

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
  })
  
  // State registers
  val condition = RegInit(ConditionStates.clean)
  val activity = RegInit(ActivityStates.idle)
  val predecessor = RegInit(io.initpred)
  val serving = RegInit(Directions.SELF)
  val requestReg = RegInit(0.U(5.W))
  
  // Helper functions
  def select(in: UInt, sel: UInt): Bool = {
    val result = Wire(Bool())
    result := false.B
    
    when(sel === 0.U) {
      result := in(0)
    }.elsewhen(sel === 1.U) {
      result := in(1)
    }.elsewhen(sel === 2.U) {
      result := in(2)
    }.elsewhen(sel === 3.U) {
      result := in(3)
    }.elsewhen(sel === 4.U) {
      result := in(4)
    }
    
    result
  }
  
  def incMod5(op: UInt): UInt = {
    Mux(op === 4.U, 0.U, op + 1.U)
  }
  
  // Combinational signals
  val soap = Wire(Bool())
  val requestPending = Wire(Bool())
  val mask = Wire(UInt(4.W))
  val mbar = Wire(UInt(4.W))
  val soapIsComing = Wire(Bool())
  val proceed = Wire(Bool())
  
  // Instantiate decoders
  val decoder1 = Module(new Decoder())
  val decoder2 = Module(new Decoder())
  val decoder3 = Module(new Decoder())
  
  decoder1.io.in := serving
  decoder1.io.en := soap
  
  decoder2.io.in := serving
  decoder2.io.en := true.B
  
  decoder3.io.in := predecessor
  decoder3.io.en := (activity === ActivityStates.busy)
  
  // Assign outputs
  io.shower := (condition === ConditionStates.showering)
  soap := (predecessor === Directions.SELF)
  requestPending := (requestReg =/= 0.U)
  mask := ~mbar
  soapIsComing := select(Cat(0.U(1.W), io.granted), predecessor)
  proceed := false.B // Simplified from $ND(0,1)
  
  io.grant := decoder1.io.dec
  mbar := decoder2.io.dec
  io.reqOut := decoder3.io.dec
  
  // State machine logic
  when(io.reset) {
    condition := ConditionStates.clean
    activity := ActivityStates.idle
    predecessor := io.initpred
    requestReg := 0.U
    serving := Directions.SELF
  }.otherwise {
    // Update request register (disregard requests from guest being served)
    val newRequestReg = Wire(UInt(5.W))
    newRequestReg := Cat(requestReg(4), io.reqIn & mask)
    requestReg := newRequestReg
    
    // Condition state machine
    val newCondition = Wire(UInt(2.W))
    newCondition := condition
    
    switch(condition) {
      is(ConditionStates.clean) {
        when(io.start) {
          newCondition := ConditionStates.dirty
          requestReg := Cat(1.U(1.W), io.reqIn & mask)
        }
      }
      is(ConditionStates.dirty) {
        when(soap && (serving === Directions.SELF)) {
          newCondition := ConditionStates.showering
          requestReg := Cat(0.U(1.W), io.reqIn & mask)
        }
      }
      is(ConditionStates.showering) {
        when(proceed) { newCondition := ConditionStates.clean }
      }
    }
    condition := newCondition
    
    // Activity state machine
    val newActivity = Wire(UInt(1.W))
    val newServing = Wire(UInt(3.W))
    val newPredecessor = Wire(UInt(3.W))
    
    newActivity := activity
    newServing := serving
    newPredecessor := predecessor
    
    // Compute toBeServed using current serving value to avoid combinational loop
    val toBeServed = Wire(UInt(3.W))
    val result = Wire(UInt(3.W))
    result := incMod5(serving)
    
    when(requestReg =/= 0.U) {
      when(!select(requestReg, result)) { result := incMod5(result) }
      when(!select(requestReg, result)) { result := incMod5(result) }
      when(!select(requestReg, result)) { result := incMod5(result) }
      when(!select(requestReg, result)) { result := incMod5(result) }
    }
    toBeServed := result
    
    switch(activity) {
      is(ActivityStates.idle) {
        when(requestPending && (condition =/= ConditionStates.showering)) {
          newServing := toBeServed
          newActivity := ActivityStates.busy
        }
      }
      is(ActivityStates.busy) {
        when(soapIsComing) {
          newPredecessor := Directions.SELF
        }.elsewhen(soap) {
          newPredecessor := serving
          newActivity := ActivityStates.idle
        }
      }
    }
    
    activity := newActivity
    serving := newServing
    predecessor := newPredecessor
  }
}

class Hotel extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val start = Input(UInt(10.W))
    val showering = Output(UInt(10.W))
  })
  
  // Request and grant signals for each guest
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
  
  // Connection matrix - granted signals
  grantedA := Cat(0.U(1.W), grantB(0), 0.U(1.W), 0.U(1.W))
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
  val GA = Module(new Guest())
  GA.io.clk := io.clk
  GA.io.reset := io.rst
  GA.io.start := io.start(0)
  GA.io.reqIn := reqInA
  GA.io.initpred := Directions.S
  GA.io.granted := grantedA
  
  val GB = Module(new Guest())
  GB.io.clk := io.clk
  GB.io.reset := io.rst
  GB.io.start := io.start(1)
  GB.io.reqIn := reqInB
  GB.io.initpred := Directions.S
  GB.io.granted := grantedB
  
  val GC = Module(new Guest())
  GC.io.clk := io.clk
  GC.io.reset := io.rst
  GC.io.start := io.start(2)
  GC.io.reqIn := reqInC
  GC.io.initpred := Directions.E
  GC.io.granted := grantedC
  
  val GD = Module(new Guest())
  GD.io.clk := io.clk
  GD.io.reset := io.rst
  GD.io.start := io.start(3)
  GD.io.reqIn := reqInD
  GD.io.initpred := Directions.SELF
  GD.io.granted := grantedD
  
  val GE = Module(new Guest())
  GE.io.clk := io.clk
  GE.io.reset := io.rst
  GE.io.start := io.start(4)
  GE.io.reqIn := reqInE
  GE.io.initpred := Directions.N
  GE.io.granted := grantedE
  
  val GF = Module(new Guest())
  GF.io.clk := io.clk
  GF.io.reset := io.rst
  GF.io.start := io.start(5)
  GF.io.reqIn := reqInF
  GF.io.initpred := Directions.E
  GF.io.granted := grantedF
  
  val GG = Module(new Guest())
  GG.io.clk := io.clk
  GG.io.reset := io.rst
  GG.io.start := io.start(6)
  GG.io.reqIn := reqInG
  GG.io.initpred := Directions.W
  GG.io.granted := grantedG
  
  val GH = Module(new Guest())
  GH.io.clk := io.clk
  GH.io.reset := io.rst
  GH.io.start := io.start(7)
  GH.io.reqIn := reqInH
  GH.io.initpred := Directions.S
  GH.io.granted := grantedH
  
  val GI = Module(new Guest())
  GI.io.clk := io.clk
  GI.io.reset := io.rst
  GI.io.start := io.start(8)
  GI.io.reqIn := reqInI
  GI.io.initpred := Directions.W
  GI.io.granted := grantedI
  
  val GJ = Module(new Guest())
  GJ.io.clk := io.clk
  GJ.io.reset := io.rst
  GJ.io.start := io.start(9)
  GJ.io.reqIn := reqInJ
  GJ.io.initpred := Directions.S
  GJ.io.granted := grantedJ
  
  // Connect outputs
  reqOutA := GA.io.reqOut
  reqOutB := GB.io.reqOut
  reqOutC := GC.io.reqOut
  reqOutD := GD.io.reqOut
  reqOutE := GE.io.reqOut
  reqOutF := GF.io.reqOut
  reqOutG := GG.io.reqOut
  reqOutH := GH.io.reqOut
  reqOutI := GI.io.reqOut
  reqOutJ := GJ.io.reqOut
  
  grantA := GA.io.grant
  grantB := GB.io.grant
  grantC := GC.io.grant
  grantD := GD.io.grant
  grantE := GE.io.grant
  grantF := GF.io.grant
  grantG := GG.io.grant
  grantH := GH.io.grant
  grantI := GI.io.grant
  grantJ := GJ.io.grant
  
  // Output showering signals
  io.showering := Cat(
    GJ.io.shower,
    GI.io.shower,
    GH.io.shower,
    GG.io.shower,
    GF.io.shower,
    GE.io.shower,
    GD.io.shower,
    GC.io.shower,
    GB.io.shower,
    GA.io.shower
  )
}

object VerilogGenerator extends App {
  emitVerilog(new Hotel(), args)
}