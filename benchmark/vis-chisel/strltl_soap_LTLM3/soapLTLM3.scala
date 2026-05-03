package llmverify
import chisel3._
import chisel3.util._

// Constants
object Constants {
  val N = 0.U(3.W)
  val W = 1.U(3.W) 
  val S = 2.U(3.W)
  val E = 3.U(3.W)
  val SELF = 4.U(3.W)
  
  val clean = 0.U(2.W)
  val dirty = 1.U(2.W)
  val showering = 2.U(2.W)
  
  val idle = 0.U(1.W)
  val busy = 1.U(1.W)
}

// Buechi states
object BuechiStates extends ChiselEnum {
  val Init = Value
  val n2, n3, n4, n5, n7, n8, n9, n10, n11, n12, n13, n14, n16, n18, n19, n20, n23, n24, n27, n28, n29, n30, n31, n32 = Value
  val Trap = Value
}

class hotel extends Module {
  val io = IO(new Bundle {
    val rst = Input(Bool())
    val start = Input(UInt(10.W))
    val showering = Output(UInt(10.W))
    val fair0 = Output(Bool())
    val fair1 = Output(Bool())
    val scc = Output(Bool())
  })
  
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
  grantedA := Cat(0.U, grantB(Constants.N), 0.U, 0.U)
  reqInA := Cat(0.U, reqOutB(Constants.N), 0.U, 0.U)
  grantedB := Cat(grantI(Constants.W), grantD(Constants.N), grantC(Constants.E), grantA(Constants.S))
  reqInB := Cat(reqOutI(Constants.W), reqOutD(Constants.N), reqOutC(Constants.E), reqOutA(Constants.S))
  grantedC := Cat(grantB(Constants.W), grantJ(Constants.N), 0.U, 0.U)
  reqInC := Cat(reqOutB(Constants.W), reqOutJ(Constants.N), 0.U, 0.U)
  grantedD := Cat(grantH(Constants.W), grantE(Constants.N), 0.U, grantB(Constants.S))
  reqInD := Cat(reqOutH(Constants.W), reqOutE(Constants.N), 0.U, reqOutB(Constants.S))
  grantedE := Cat(grantG(Constants.W), 0.U, grantF(Constants.E), grantD(Constants.S))
  reqInE := Cat(reqOutG(Constants.W), 0.U, reqOutF(Constants.E), reqOutD(Constants.S))
  grantedF := Cat(grantE(Constants.W), 0.U, 0.U, grantJ(Constants.S))
  reqInF := Cat(reqOutE(Constants.W), 0.U, 0.U, reqOutJ(Constants.S))
  grantedG := Cat(0.U, 0.U, grantE(Constants.E), grantH(Constants.S))
  reqInG := Cat(0.U, 0.U, reqOutE(Constants.E), reqOutH(Constants.S))
  grantedH := Cat(0.U, grantG(Constants.N), grantD(Constants.E), grantI(Constants.S))
  reqInH := Cat(0.U, reqOutG(Constants.N), reqOutD(Constants.E), reqOutI(Constants.S))
  grantedI := Cat(0.U, grantH(Constants.N), grantB(Constants.E), 0.U)
  reqInI := Cat(0.U, reqOutH(Constants.N), reqOutB(Constants.E), 0.U)
  grantedJ := Cat(0.U, grantF(Constants.N), 0.U, grantC(Constants.S))
  reqInJ := Cat(0.U, reqOutF(Constants.N), 0.U, reqOutC(Constants.S))
  
  // Instantiate guests
  val GA = Module(new guest())
  GA.io.rst := io.rst
  GA.io.start := io.start(0)
  GA.io.reqIn := reqInA
  GA.io.initpred := Constants.S
  GA.io.granted := grantedA
  val showeringA = GA.io.shower
  reqOutA := GA.io.reqOut
  grantA := GA.io.grant
  conditionA := GA.io.condition
  outproceedA := GA.io.outproceed
  
  val GB = Module(new guest())
  GB.io.rst := io.rst
  GB.io.start := io.start(1)
  GB.io.reqIn := reqInB
  GB.io.initpred := Constants.S
  GB.io.granted := grantedB
  val showeringB = GB.io.shower
  reqOutB := GB.io.reqOut
  grantB := GB.io.grant
  conditionB := GB.io.condition
  outproceedB := GB.io.outproceed
  
  val GC = Module(new guest())
  GC.io.rst := io.rst
  GC.io.start := io.start(2)
  GC.io.reqIn := reqInC
  GC.io.initpred := Constants.E
  GC.io.granted := grantedC
  val showeringC = GC.io.shower
  reqOutC := GC.io.reqOut
  grantC := GC.io.grant
  conditionC := GC.io.condition
  outproceedC := GC.io.outproceed
  
  val GD = Module(new guest())
  GD.io.rst := io.rst
  GD.io.start := io.start(3)
  GD.io.reqIn := reqInD
  GD.io.initpred := Constants.SELF
  GD.io.granted := grantedD
  val showeringD = GD.io.shower
  reqOutD := GD.io.reqOut
  grantD := GD.io.grant
  conditionD := GD.io.condition
  outproceedD := GD.io.outproceed
  
  val GE = Module(new guest())
  GE.io.rst := io.rst
  GE.io.start := io.start(4)
  GE.io.reqIn := reqInE
  GE.io.initpred := Constants.N
  GE.io.granted := grantedE
  val showeringE = GE.io.shower
  reqOutE := GE.io.reqOut
  grantE := GE.io.grant
  conditionE := GE.io.condition
  outproceedE := GE.io.outproceed
  
  val GF = Module(new guest())
  GF.io.rst := io.rst
  GF.io.start := io.start(5)
  GF.io.reqIn := reqInF
  GF.io.initpred := Constants.E
  GF.io.granted := grantedF
  val showeringF = GF.io.shower
  reqOutF := GF.io.reqOut
  grantF := GF.io.grant
  conditionF := GF.io.condition
  outproceedF := GF.io.outproceed
  
  val GG = Module(new guest())
  GG.io.rst := io.rst
  GG.io.start := io.start(6)
  GG.io.reqIn := reqInG
  GG.io.initpred := Constants.W
  GG.io.granted := grantedG
  val showeringG = GG.io.shower
  reqOutG := GG.io.reqOut
  grantG := GG.io.grant
  conditionG := GG.io.condition
  outproceedG := GG.io.outproceed
  
  val GH = Module(new guest())
  GH.io.rst := io.rst
  GH.io.start := io.start(7)
  GH.io.reqIn := reqInH
  GH.io.initpred := Constants.S
  GH.io.granted := grantedH
  val showeringH = GH.io.shower
  reqOutH := GH.io.reqOut
  grantH := GH.io.grant
  conditionH := GH.io.condition
  outproceedH := GH.io.outproceed
  
  val GI = Module(new guest())
  GI.io.rst := io.rst
  GI.io.start := io.start(8)
  GI.io.reqIn := reqInI
  GI.io.initpred := Constants.W
  GI.io.granted := grantedI
  val showeringI = GI.io.shower
  reqOutI := GI.io.reqOut
  grantI := GI.io.grant
  conditionI := GI.io.condition
  outproceedI := GI.io.outproceed
  
  val GJ = Module(new guest())
  GJ.io.rst := io.rst
  GJ.io.start := io.start(9)
  GJ.io.reqIn := reqInJ
  GJ.io.initpred := Constants.S
  GJ.io.granted := grantedJ
  val showeringJ = GJ.io.shower
  reqOutJ := GJ.io.reqOut
  grantJ := GJ.io.grant
  conditionJ := GJ.io.condition
  outproceedJ := GJ.io.outproceed
  
  // Compose showering output
  io.showering := Cat(showeringJ, showeringI, showeringH, showeringG, showeringF, showeringE, showeringD, showeringC, showeringB, showeringA)
  
  // For compositional LTL model checking
  val Adirty = (conditionA === Constants.dirty)
  val Aclean = (conditionA === Constants.clean)
  val Aproceed = outproceedA
  val Bdirty = (conditionB === Constants.dirty)
  val Bclean = (conditionB === Constants.clean)
  val Bproceed = outproceedB
  
  // Buechi automaton
  val buechi = Module(new Buechi())
  buechi.io.Adirty := Adirty
  buechi.io.Bdirty := Bdirty
  buechi.io.Aclean := Aclean
  buechi.io.Aproceed := Aproceed
  buechi.io.Bclean := Bclean
  buechi.io.Bproceed := Bproceed
  io.fair0 := buechi.io.fair0
  io.fair1 := buechi.io.fair1
  io.scc := buechi.io.scc
}

class Buechi extends Module {
  val io = IO(new Bundle {
    val Adirty = Input(Bool())
    val Bdirty = Input(Bool())
    val Aclean = Input(Bool())
    val Aproceed = Input(Bool())
    val Bclean = Input(Bool())
    val Bproceed = Input(Bool())
    val fair0 = Output(Bool())
    val fair1 = Output(Bool())
    val scc = Output(Bool())
  })
  
  val state = RegInit(BuechiStates.Init)
  
  // Non-deterministic choice helper
  def ND(choices: BuechiStates.Type*): BuechiStates.Type = {
    // Simple implementation - just return the first choice
    // In a real implementation, this would be non-deterministic
    choices.head
  }
  
  // State transition logic
  switch(state) {
    is(BuechiStates.n11) {
      when(Cat(io.Adirty, io.Bclean, io.Bdirty) === 0.U) {
        state := BuechiStates.n30
      }.elsewhen(Cat(io.Adirty, io.Bclean, io.Bdirty) === 1.U) {
        state := ND(BuechiStates.n28, BuechiStates.n30)
      }.elsewhen(Cat(io.Adirty, io.Bclean, io.Bdirty)(2,1) === 2.U) {
        state := BuechiStates.n30
      }.elsewhen(io.Adirty) {
        state := BuechiStates.Trap
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n30) {
      when(Cat(io.Adirty, io.Bclean, io.Bdirty) === 0.U) {
        state := BuechiStates.n30
      }.elsewhen(Cat(io.Adirty, io.Bclean, io.Bdirty) === 1.U) {
        state := ND(BuechiStates.n28, BuechiStates.n30)
      }.elsewhen(Cat(io.Adirty, io.Bclean, io.Bdirty)(2,1) === 2.U) {
        state := BuechiStates.n30
      }.elsewhen(io.Adirty) {
        state := BuechiStates.Trap
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n13) {
      when(Cat(io.Adirty, io.Bclean) === 0.U) {
        state := BuechiStates.n14
      }.elsewhen(Cat(io.Adirty, io.Bclean) === 1.U) {
        state := BuechiStates.Trap
      }.elsewhen(io.Adirty) {
        state := BuechiStates.Trap
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n28) {
      when(Cat(io.Adirty, io.Bclean) === 0.U) {
        state := BuechiStates.n14
      }.elsewhen(Cat(io.Adirty, io.Bclean) === 1.U) {
        state := BuechiStates.Trap
      }.elsewhen(io.Adirty) {
        state := BuechiStates.Trap
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n9) {
      when(Cat(io.Aclean, io.Aproceed, io.Bclean) === 0.U) {
        state := BuechiStates.n19
      }.elsewhen(Cat(io.Aclean, io.Aproceed, io.Bclean)(1,0) === 3.U) {
        state := BuechiStates.Trap
      }.elsewhen(Cat(io.Aclean, io.Aproceed, io.Bclean) === 2.U) {
        state := ND(BuechiStates.n19, BuechiStates.n9)
      }.elsewhen(Cat(io.Aclean, io.Aproceed, io.Bclean) === 4.U) {
        state := ND(BuechiStates.n19, BuechiStates.n8)
      }.elsewhen(Cat(io.Aclean, io.Aproceed, io.Bclean) === 6.U) {
        state := ND(BuechiStates.n19, BuechiStates.n5, BuechiStates.n8, BuechiStates.n9)
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n19) {
      when(Cat(io.Aclean, io.Aproceed, io.Bclean) === 0.U) {
        state := BuechiStates.n19
      }.elsewhen(Cat(io.Aclean, io.Aproceed, io.Bclean)(1,0) === 3.U) {
        state := BuechiStates.Trap
      }.elsewhen(Cat(io.Aclean, io.Aproceed, io.Bclean) === 2.U) {
        state := ND(BuechiStates.n19, BuechiStates.n9)
      }.elsewhen(Cat(io.Aclean, io.Aproceed, io.Bclean) === 4.U) {
        state := ND(BuechiStates.n19, BuechiStates.n8)
      }.elsewhen(Cat(io.Aclean, io.Aproceed, io.Bclean) === 6.U) {
        state := ND(BuechiStates.n19, BuechiStates.n5, BuechiStates.n8, BuechiStates.n9)
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n4) {
      when(Cat(io.Aproceed, io.Bclean, io.Bdirty) === 0.U) {
        state := ND(BuechiStates.n12, BuechiStates.n27)
      }.elsewhen(Cat(io.Aproceed, io.Bclean, io.Bdirty) === 1.U) {
        state := BuechiStates.n27
      }.elsewhen(Cat(io.Aproceed, io.Bclean, io.Bdirty) === 2.U) {
        state := ND(BuechiStates.n12, BuechiStates.n27, BuechiStates.n4)
      }.elsewhen(Cat(io.Aproceed, io.Bclean, io.Bdirty) === 3.U) {
        state := ND(BuechiStates.n27, BuechiStates.n4)
      }.elsewhen(io.Aproceed) {
        state := BuechiStates.Trap
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n12) {
      when(Cat(io.Aproceed, io.Bclean, io.Bdirty) === 0.U) {
        state := ND(BuechiStates.n12, BuechiStates.n27)
      }.elsewhen(Cat(io.Aproceed, io.Bclean, io.Bdirty) === 1.U) {
        state := BuechiStates.n27
      }.elsewhen(Cat(io.Aproceed, io.Bclean, io.Bdirty) === 2.U) {
        state := ND(BuechiStates.n12, BuechiStates.n27, BuechiStates.n4)
      }.elsewhen(Cat(io.Aproceed, io.Bclean, io.Bdirty) === 3.U) {
        state := ND(BuechiStates.n27, BuechiStates.n4)
      }.elsewhen(io.Aproceed) {
        state := BuechiStates.Trap
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n18) {
      when(Cat(io.Aclean, io.Bclean, io.Bdirty) === 0.U) {
        state := ND(BuechiStates.n20, BuechiStates.n7)
      }.elsewhen(Cat(io.Aclean, io.Bclean, io.Bdirty) === 1.U) {
        state := BuechiStates.n7
      }.elsewhen(Cat(io.Aclean, io.Bclean, io.Bdirty) === 2.U) {
        state := ND(BuechiStates.n18, BuechiStates.n20, BuechiStates.n7)
      }.elsewhen(Cat(io.Aclean, io.Bclean, io.Bdirty) === 3.U) {
        state := ND(BuechiStates.n18, BuechiStates.n7)
      }.elsewhen(io.Aclean) {
        state := BuechiStates.Trap
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n20) {
      when(Cat(io.Aclean, io.Bclean, io.Bdirty) === 0.U) {
        state := ND(BuechiStates.n20, BuechiStates.n7)
      }.elsewhen(Cat(io.Aclean, io.Bclean, io.Bdirty) === 1.U) {
        state := BuechiStates.n7
      }.elsewhen(Cat(io.Aclean, io.Bclean, io.Bdirty) === 2.U) {
        state := ND(BuechiStates.n18, BuechiStates.n20, BuechiStates.n7)
      }.elsewhen(Cat(io.Aclean, io.Bclean, io.Bdirty) === 3.U) {
        state := ND(BuechiStates.n18, BuechiStates.n7)
      }.elsewhen(io.Aclean) {
        state := BuechiStates.Trap
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.Trap) {
      state := BuechiStates.Trap
    }
    is(BuechiStates.Init) {
      when(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 0.U) {
        state := ND(BuechiStates.n12, BuechiStates.n29, BuechiStates.n3, BuechiStates.n30)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 1.U || 
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 3.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 5.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 7.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 9.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 11.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 13.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 15.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 17.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 19.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 21.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 23.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 25.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 27.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 29.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 31.U) {
        state := ND(BuechiStates.n24, BuechiStates.n28, BuechiStates.n29, BuechiStates.n30)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 2.U) {
        state := ND(BuechiStates.n12, BuechiStates.n29, BuechiStates.n3, BuechiStates.n30, BuechiStates.n32, BuechiStates.n4)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 3.U) {
        state := ND(BuechiStates.n29, BuechiStates.n30, BuechiStates.n32, BuechiStates.n4)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 4.U) {
        state := ND(BuechiStates.n29, BuechiStates.n3, BuechiStates.n30)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 6.U) {
        state := ND(BuechiStates.n29, BuechiStates.n3, BuechiStates.n30, BuechiStates.n32)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 7.U) {
        state := ND(BuechiStates.n29, BuechiStates.n30, BuechiStates.n32)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 8.U) {
        state := ND(BuechiStates.n12, BuechiStates.n16, BuechiStates.n2, BuechiStates.n29, BuechiStates.n3)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 9.U || 
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 11.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 13.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 15.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 17.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 19.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 21.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 23.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 25.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 27.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 29.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 31.U) {
        state := ND(BuechiStates.n16, BuechiStates.n24, BuechiStates.n29)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 10.U) {
        state := ND(BuechiStates.n10, BuechiStates.n12, BuechiStates.n16, BuechiStates.n2, BuechiStates.n29, BuechiStates.n3, BuechiStates.n32, BuechiStates.n4)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 11.U) {
        state := ND(BuechiStates.n10, BuechiStates.n16, BuechiStates.n29, BuechiStates.n32, BuechiStates.n4)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 12.U) {
        state := ND(BuechiStates.n16, BuechiStates.n2, BuechiStates.n29, BuechiStates.n3)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 14.U) {
        state := ND(BuechiStates.n10, BuechiStates.n16, BuechiStates.n2, BuechiStates.n29, BuechiStates.n3, BuechiStates.n32)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 15.U) {
        state := ND(BuechiStates.n10, BuechiStates.n16, BuechiStates.n29, BuechiStates.n32)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 16.U) {
        state := ND(BuechiStates.n11, BuechiStates.n12, BuechiStates.n29, BuechiStates.n3, BuechiStates.n30)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 17.U || 
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 19.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 21.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 23.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 25.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 27.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 29.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 31.U) {
        state := ND(BuechiStates.n11, BuechiStates.n13, BuechiStates.n24, BuechiStates.n28, BuechiStates.n29, BuechiStates.n30)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 18.U) {
        state := ND(BuechiStates.n11, BuechiStates.n12, BuechiStates.n29, BuechiStates.n3, BuechiStates.n30, BuechiStates.n32, BuechiStates.n4)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 19.U) {
        state := ND(BuechiStates.n11, BuechiStates.n29, BuechiStates.n30, BuechiStates.n32, BuechiStates.n4)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 20.U) {
        state := ND(BuechiStates.n11, BuechiStates.n29, BuechiStates.n3, BuechiStates.n30)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 22.U) {
        state := ND(BuechiStates.n11, BuechiStates.n29, BuechiStates.n3, BuechiStates.n30, BuechiStates.n32)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 23.U) {
        state := ND(BuechiStates.n11, BuechiStates.n29, BuechiStates.n30, BuechiStates.n32)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 24.U) {
        state := ND(BuechiStates.n11, BuechiStates.n12, BuechiStates.n29, BuechiStates.n3)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 25.U || 
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 27.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 29.U ||
                Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 31.U) {
        state := ND(BuechiStates.n11, BuechiStates.n13, BuechiStates.n24, BuechiStates.n29)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 26.U) {
        state := ND(BuechiStates.n11, BuechiStates.n12, BuechiStates.n29, BuechiStates.n3, BuechiStates.n32, BuechiStates.n4)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 27.U) {
        state := ND(BuechiStates.n11, BuechiStates.n29, BuechiStates.n32, BuechiStates.n4)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 28.U) {
        state := ND(BuechiStates.n11, BuechiStates.n29, BuechiStates.n3)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 30.U) {
        state := ND(BuechiStates.n11, BuechiStates.n29, BuechiStates.n3, BuechiStates.n32)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty) === 31.U) {
        state := ND(BuechiStates.n11, BuechiStates.n29, BuechiStates.n32)
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n31) {
      when(!io.Bproceed) {
        state := BuechiStates.n31
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n24) {
      when(Cat(io.Aclean, io.Bclean) === 0.U) {
        state := BuechiStates.n19
      }.elsewhen(Cat(io.Aclean, io.Bclean)(0)) {
        state := BuechiStates.Trap
      }.elsewhen(Cat(io.Aclean, io.Bclean) === 2.U) {
        state := ND(BuechiStates.n19, BuechiStates.n8)
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n5) {
      when(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 0.U) {
        state := ND(BuechiStates.n14, BuechiStates.n19)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean)(0)) {
        state := BuechiStates.Trap
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 2.U) {
        state := ND(BuechiStates.n14, BuechiStates.n19, BuechiStates.n23, BuechiStates.n9)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 4.U) {
        state := BuechiStates.n19
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 6.U) {
        state := ND(BuechiStates.n19, BuechiStates.n9)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 8.U) {
        state := ND(BuechiStates.n14, BuechiStates.n19, BuechiStates.n8)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 10.U) {
        state := ND(BuechiStates.n14, BuechiStates.n19, BuechiStates.n23, BuechiStates.n5, BuechiStates.n8, BuechiStates.n9)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 12.U) {
        state := ND(BuechiStates.n19, BuechiStates.n8)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 14.U) {
        state := ND(BuechiStates.n19, BuechiStates.n5, BuechiStates.n8, BuechiStates.n9)
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n8) {
      when(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 0.U) {
        state := ND(BuechiStates.n14, BuechiStates.n19)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean)(0)) {
        state := BuechiStates.Trap
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 2.U) {
        state := ND(BuechiStates.n14, BuechiStates.n19, BuechiStates.n23, BuechiStates.n9)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 4.U) {
        state := BuechiStates.n19
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 6.U) {
        state := ND(BuechiStates.n19, BuechiStates.n9)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 8.U) {
        state := ND(BuechiStates.n14, BuechiStates.n19, BuechiStates.n8)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 10.U) {
        state := ND(BuechiStates.n14, BuechiStates.n19, BuechiStates.n23, BuechiStates.n5, BuechiStates.n8, BuechiStates.n9)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 12.U) {
        state := ND(BuechiStates.n19, BuechiStates.n8)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 14.U) {
        state := ND(BuechiStates.n19, BuechiStates.n5, BuechiStates.n8, BuechiStates.n9)
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n14) {
      when(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 0.U) {
        state := ND(BuechiStates.n14, BuechiStates.n19)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean)(0)) {
        state := BuechiStates.Trap
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 2.U) {
        state := ND(BuechiStates.n14, BuechiStates.n19, BuechiStates.n23, BuechiStates.n9)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 4.U) {
        state := BuechiStates.n19
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 6.U) {
        state := ND(BuechiStates.n19, BuechiStates.n9)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 8.U) {
        state := ND(BuechiStates.n14, BuechiStates.n19, BuechiStates.n8)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 10.U) {
        state := ND(BuechiStates.n14, BuechiStates.n19, BuechiStates.n23, BuechiStates.n5, BuechiStates.n8, BuechiStates.n9)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 12.U) {
        state := ND(BuechiStates.n19, BuechiStates.n8)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 14.U) {
        state := ND(BuechiStates.n19, BuechiStates.n5, BuechiStates.n8, BuechiStates.n9)
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n23) {
      when(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 0.U) {
        state := ND(BuechiStates.n14, BuechiStates.n19)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean)(0)) {
        state := BuechiStates.Trap
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 2.U) {
        state := ND(BuechiStates.n14, BuechiStates.n19, BuechiStates.n23, BuechiStates.n9)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 4.U) {
        state := BuechiStates.n19
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 6.U) {
        state := ND(BuechiStates.n19, BuechiStates.n9)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 8.U) {
        state := ND(BuechiStates.n14, BuechiStates.n19, BuechiStates.n8)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 10.U) {
        state := ND(BuechiStates.n14, BuechiStates.n19, BuechiStates.n23, BuechiStates.n5, BuechiStates.n8, BuechiStates.n9)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 12.U) {
        state := ND(BuechiStates.n19, BuechiStates.n8)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean) === 14.U) {
        state := ND(BuechiStates.n19, BuechiStates.n5, BuechiStates.n8, BuechiStates.n9)
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n3) {
      when(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bdirty) === 0.U) {
        state := ND(BuechiStates.n12, BuechiStates.n3)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bdirty)(0)) {
        state := BuechiStates.Trap
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bdirty) === 2.U) {
        state := BuechiStates.n3
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bdirty) === 4.U) {
        state := ND(BuechiStates.n12, BuechiStates.n2, BuechiStates.n3)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bdirty) === 6.U) {
        state := ND(BuechiStates.n2, BuechiStates.n3)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bdirty)(3,2) === 2.U && Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bdirty)(0) === 0.U) {
        state := ND(BuechiStates.n12, BuechiStates.n3)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bdirty)(3,2) === 2.U && Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bdirty)(0) === 1.U) {
        state := BuechiStates.n3
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n32) {
      when(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bdirty) === 0.U) {
        state := ND(BuechiStates.n12, BuechiStates.n3)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bdirty)(0)) {
        state := BuechiStates.Trap
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bdirty) === 2.U) {
        state := BuechiStates.n3
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bdirty) === 4.U) {
        state := ND(BuechiStates.n12, BuechiStates.n2, BuechiStates.n3)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bdirty) === 6.U) {
        state := ND(BuechiStates.n2, BuechiStates.n3)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bdirty)(3,2) === 2.U && Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bdirty)(0) === 0.U) {
        state := ND(BuechiStates.n12, BuechiStates.n3)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bdirty)(3,2) === 2.U && Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bdirty)(0) === 1.U) {
        state := BuechiStates.n3
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n27) {
      when(Cat(io.Aproceed, io.Bclean) === 0.U) {
        state := BuechiStates.n27
      }.elsewhen(Cat(io.Aproceed, io.Bclean) === 1.U) {
        state := ND(BuechiStates.n27, BuechiStates.n4)
      }.elsewhen(io.Aproceed) {
        state := BuechiStates.Trap
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n29) {
      when(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty, io.Bproceed)(2,0) === 0.U && Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty, io.Bproceed)(5,3) === 0.U) {
        state := ND(BuechiStates.n29, BuechiStates.n31)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty, io.Bproceed)(2,0) === 0.U && Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty, io.Bproceed)(5,3) === 1.U) {
        state := BuechiStates.n29
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty, io.Bproceed)(2,0) === 0.U && Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty, io.Bproceed)(5,3) === 2.U) {
        state := ND(BuechiStates.n24, BuechiStates.n29, BuechiStates.n31)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty, io.Bproceed)(2,0) === 0.U && Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty, io.Bproceed)(5,3) === 3.U) {
        state := ND(BuechiStates.n24, BuechiStates.n29)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty, io.Bproceed) === 16.U || Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty, io.Bproceed) === 17.U) {
        state := ND(BuechiStates.n29, BuechiStates.n31, BuechiStates.n32, BuechiStates.n4)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty, io.Bproceed) === 18.U || Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty, io.Bproceed) === 19.U) {
        state := ND(BuechiStates.n29, BuechiStates.n32, BuechiStates.n4)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty, io.Bproceed) === 28.U || Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty, io.Bproceed) === 29.U) {
        state := ND(BuechiStates.n29, BuechiStates.n31, BuechiStates.n32)
      }.elsewhen(Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty, io.Bproceed) === 30.U || Cat(io.Aclean, io.Adirty, io.Aproceed, io.Bclean, io.Bdirty, io.Bproceed) === 31.U) {
        state := ND(BuechiStates.n29, BuechiStates.n32)
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n2) {
      when(Cat(io.Aclean, io.Bdirty) === 0.U) {
        state := BuechiStates.n20
      }.elsewhen(Cat(io.Aclean, io.Bdirty) === 1.U) {
        state := BuechiStates.Trap
      }.elsewhen(io.Aclean) {
        state := BuechiStates.Trap
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n10) {
      when(Cat(io.Aclean, io.Bdirty) === 0.U) {
        state := BuechiStates.n20
      }.elsewhen(Cat(io.Aclean, io.Bdirty) === 1.U) {
        state := BuechiStates.Trap
      }.elsewhen(io.Aclean) {
        state := BuechiStates.Trap
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n7) {
      when(Cat(io.Aclean, io.Bclean) === 0.U) {
        state := BuechiStates.n7
      }.elsewhen(Cat(io.Aclean, io.Bclean) === 1.U) {
        state := ND(BuechiStates.n18, BuechiStates.n7)
      }.elsewhen(io.Aclean) {
        state := BuechiStates.Trap
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
    is(BuechiStates.n16) {
      when(Cat(io.Aclean, io.Bclean) === 0.U) {
        state := BuechiStates.n7
      }.elsewhen(Cat(io.Aclean, io.Bclean) === 1.U) {
        state := ND(BuechiStates.n18, BuechiStates.n7)
      }.elsewhen(io.Aclean) {
        state := BuechiStates.Trap
      }.otherwise {
        state := BuechiStates.Trap
      }
    }
  }
  
  io.fair0 := (state === BuechiStates.n4) || (state === BuechiStates.n5) || (state === BuechiStates.n9) || (state === BuechiStates.n12) || (state === BuechiStates.n18) || (state === BuechiStates.n20) || (state === BuechiStates.n23) || (state === BuechiStates.n31)
  io.fair1 := (state === BuechiStates.n4) || (state === BuechiStates.n5) || (state === BuechiStates.n8) || (state === BuechiStates.n12) || (state === BuechiStates.n14) || (state === BuechiStates.n18) || (state === BuechiStates.n20) || (state === BuechiStates.n23) || (state === BuechiStates.n31)
  io.scc := (state === BuechiStates.n31) || (state === BuechiStates.n20) || (state === BuechiStates.n7) || (state === BuechiStates.n18) || (state === BuechiStates.n12) || (state === BuechiStates.n4) || (state === BuechiStates.n27) || (state === BuechiStates.n19) || (state === BuechiStates.n5) || (state === BuechiStates.n14) || (state === BuechiStates.n23) || (state === BuechiStates.n8) || (state === BuechiStates.n9)
}

class guest extends Module {
  val io = IO(new Bundle {
    val rst = Input(Bool())
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
  
  val condition = RegInit(Constants.clean)
  val activity = RegInit(Constants.idle)
  val predecessor = RegInit(io.initpred)
  val serving = RegInit(Constants.SELF)
  val requestReg = RegInit(0.U(5.W))
  
  val soap = Wire(Bool())
  val toBeServed = Wire(UInt(3.W))
  val requestPending = Wire(Bool())
  val mask = Wire(UInt(4.W))
  val mbar = Wire(UInt(4.W))
  val soapIsComing = Wire(Bool())
  val proceed = Wire(Bool())
  
  // Helper functions
  def select(in: UInt, sel: UInt): Bool = {
    when(sel === 0.U) {
      in(0)
    }.elsewhen(sel === 1.U) {
      in(1)
    }.elsewhen(sel === 2.U) {
      in(2)
    }.elsewhen(sel === 3.U) {
      in(3)
    }.elsewhen(sel === 4.U) {
      in(4)
    }.otherwise {
      false.B
    }
  }
  
  def incMod5(op: UInt): UInt = {
    Mux(op === 4.U, 0.U, op + 1.U)
  }
  
  def pickRequest(req: UInt, rrobin: UInt): UInt = {
    val result = Wire(UInt(3.W))
    result := incMod5(rrobin)
    
    when(req =/= 0.U) {
      when(!select(req, result)) {
        result := incMod5(result)
      }
      when(!select(req, result)) {
        result := incMod5(result)
      }
      when(!select(req, result)) {
        result := incMod5(result)
      }
      when(!select(req, result)) {
        result := incMod5(result)
      }
    }
    result
  }
  
  soap := (predecessor === Constants.SELF)
  toBeServed := pickRequest(requestReg, serving)
  requestPending := (requestReg =/= 0.U)
  soapIsComing := select(Cat(0.U, io.granted), predecessor)
  proceed := false.B // Non-deterministic - simplified to false
  
  io.shower := (condition === Constants.showering)
  io.condition := condition
  io.outproceed := proceed
  
  // Decoder instances
  val d1 = Module(new decoder())
  d1.io.in := serving
  d1.io.en := soap
  io.grant := d1.io.dec
  
  val d2 = Module(new decoder())
  d2.io.in := serving
  d2.io.en := true.B
  mbar := d2.io.dec
  
  val d3 = Module(new decoder())
  d3.io.in := predecessor
  d3.io.en := (activity === Constants.busy)
  io.reqOut := d3.io.dec
  
  mask := ~mbar
  
  // State updates
  when(io.rst) {
    condition := Constants.clean
    activity := Constants.idle
    predecessor := io.initpred
    requestReg := 0.U
    serving := Constants.SELF
  }.otherwise {
    requestReg(3,0) := io.reqIn & mask
    
    switch(condition) {
      is(Constants.clean) {
        when(io.start) {
          condition := Constants.dirty
          requestReg(4) := true.B
        }
      }
      is(Constants.dirty) {
        when(soap && (serving === Constants.SELF)) {
          condition := Constants.showering
          requestReg(4) := false.B
        }
      }
      is(Constants.showering) {
        when(proceed) {
          condition := Constants.clean
        }
      }
    }
    
    switch(activity) {
      is(Constants.idle) {
        when(requestPending && (condition =/= Constants.showering)) {
          serving := toBeServed
          activity := Constants.busy
        }
      }
      is(Constants.busy) {
        when(soapIsComing) {
          predecessor := Constants.SELF
        }.elsewhen(soap) {
          predecessor := serving
          activity := Constants.idle
        }
      }
    }
  }
}

class decoder extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(3.W))
    val en = Input(Bool())
    val dec = Output(UInt(4.W))
  })
  
  io.dec := Cat(
    io.en && !io.in(2) && !io.in(1) && !io.in(0),
    io.en && !io.in(2) && !io.in(1) &&  io.in(0),
    io.en && !io.in(2) &&  io.in(1) && !io.in(0),
    io.en && !io.in(2) &&  io.in(1) &&  io.in(0)
  )
}

class monitor extends Module {
  val io = IO(new Bundle {
    val condition = Input(UInt(2.W))
    val fair = Output(Bool())
  })
  
  val state = RegInit(0.U(2.W))
  val zeroorone = Wire(UInt(2.W))
  
  zeroorone := Cat(0.U, 0.U) // Non-deterministic choice - simplified to 0
  
  io.fair := (state === 1.U)
  
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

object VerilogGenerator extends App {
  emitVerilog(new hotel(), args)
}