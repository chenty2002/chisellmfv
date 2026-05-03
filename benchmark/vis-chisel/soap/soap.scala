package llmverify

import chisel3._
import chisel3.util._

class Hotel extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val start = Input(UInt(10.W))
    val showering = Output(UInt(10.W))
  })
  
  // Constants
  val N = 0.U(3.W)
  val W = 1.U(3.W)
  val S = 2.U(3.W)
  val E = 3.U(3.W)
  val SELF = 4.U(3.W)
  
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
  
  // Connection matrix assignments
  // Format: {E, S, W, N}
  grantedA := Cat(0.U, grantB(N), 0.U, 0.U)
  reqInA := Cat(0.U, reqOutB(N), 0.U, 0.U)
  
  grantedB := Cat(grantI(W), grantD(N), grantC(E), grantA(S))
  reqInB := Cat(reqOutI(W), reqOutD(N), reqOutC(E), reqOutA(S))
  
  grantedC := Cat(grantB(W), grantJ(N), 0.U, 0.U)
  reqInC := Cat(reqOutB(W), reqOutJ(N), 0.U, 0.U)
  
  grantedD := Cat(grantH(W), grantE(N), 0.U, grantB(S))
  reqInD := Cat(reqOutH(W), reqOutE(N), 0.U, reqOutB(S))
  
  grantedE := Cat(grantG(W), 0.U, grantF(E), grantD(S))
  reqInE := Cat(reqOutG(W), 0.U, reqOutF(E), reqOutD(S))
  
  grantedF := Cat(grantE(W), 0.U, 0.U, grantJ(S))
  reqInF := Cat(reqOutE(W), 0.U, 0.U, reqOutJ(S))
  
  grantedG := Cat(0.U, 0.U, grantE(E), grantH(S))
  reqInG := Cat(0.U, 0.U, reqOutE(E), reqOutH(S))
  
  grantedH := Cat(0.U, grantG(N), grantD(E), grantI(S))
  reqInH := Cat(0.U, reqOutG(N), reqOutD(E), reqOutI(S))
  
  grantedI := Cat(0.U, grantH(N), grantB(E), 0.U)
  reqInI := Cat(0.U, reqOutH(N), reqOutB(E), 0.U)
  
  grantedJ := Cat(0.U, grantF(N), 0.U, grantC(S))
  reqInJ := Cat(0.U, reqOutF(N), 0.U, reqOutC(S))
  
  // Guest instances
  val GA = Module(new Guest())
  GA.io.clk := io.clk
  GA.io.reset := io.rst
  GA.io.start := io.start(0)
  GA.io.reqIn := reqInA
  GA.io.initpred := S
  GA.io.granted := grantedA
  val showerA = GA.io.shower
  reqOutA := GA.io.reqOut
  grantA := GA.io.grant
  
  val GB = Module(new Guest())
  GB.io.clk := io.clk
  GB.io.reset := io.rst
  GB.io.start := io.start(1)
  GB.io.reqIn := reqInB
  GB.io.initpred := S
  GB.io.granted := grantedB
  val showerB = GB.io.shower
  reqOutB := GB.io.reqOut
  grantB := GB.io.grant
  
  val GC = Module(new Guest())
  GC.io.clk := io.clk
  GC.io.reset := io.rst
  GC.io.start := io.start(2)
  GC.io.reqIn := reqInC
  GC.io.initpred := E
  GC.io.granted := grantedC
  val showerC = GC.io.shower
  reqOutC := GC.io.reqOut
  grantC := GC.io.grant
  
  val GD = Module(new Guest())
  GD.io.clk := io.clk
  GD.io.reset := io.rst
  GD.io.start := io.start(3)
  GD.io.reqIn := reqInD
  GD.io.initpred := SELF
  GD.io.granted := grantedD
  val showerD = GD.io.shower
  reqOutD := GD.io.reqOut
  grantD := GD.io.grant
  
  val GE = Module(new Guest())
  GE.io.clk := io.clk
  GE.io.reset := io.rst
  GE.io.start := io.start(4)
  GE.io.reqIn := reqInE
  GE.io.initpred := N
  GE.io.granted := grantedE
  val showerE = GE.io.shower
  reqOutE := GE.io.reqOut
  grantE := GE.io.grant
  
  val GF = Module(new Guest())
  GF.io.clk := io.clk
  GF.io.reset := io.rst
  GF.io.start := io.start(5)
  GF.io.reqIn := reqInF
  GF.io.initpred := E
  GF.io.granted := grantedF
  val showerF = GF.io.shower
  reqOutF := GF.io.reqOut
  grantF := GF.io.grant
  
  val GG = Module(new Guest())
  GG.io.clk := io.clk
  GG.io.reset := io.rst
  GG.io.start := io.start(6)
  GG.io.reqIn := reqInG
  GG.io.initpred := W
  GG.io.granted := grantedG
  val showerG = GG.io.shower
  reqOutG := GG.io.reqOut
  grantG := GG.io.grant
  
  val GH = Module(new Guest())
  GH.io.clk := io.clk
  GH.io.reset := io.rst
  GH.io.start := io.start(7)
  GH.io.reqIn := reqInH
  GH.io.initpred := S
  GH.io.granted := grantedH
  val showerH = GH.io.shower
  reqOutH := GH.io.reqOut
  grantH := GH.io.grant
  
  val GI = Module(new Guest())
  GI.io.clk := io.clk
  GI.io.reset := io.rst
  GI.io.start := io.start(8)
  GI.io.reqIn := reqInI
  GI.io.initpred := W
  GI.io.granted := grantedI
  val showerI = GI.io.shower
  reqOutI := GI.io.reqOut
  grantI := GI.io.grant
  
  val GJ = Module(new Guest())
  GJ.io.clk := io.clk
  GJ.io.reset := io.rst
  GJ.io.start := io.start(9)
  GJ.io.reqIn := reqInJ
  GJ.io.initpred := S
  GJ.io.granted := grantedJ
  val showerJ = GJ.io.shower
  reqOutJ := GJ.io.reqOut
  grantJ := GJ.io.grant
  
  // Output
  io.showering := Cat(showerJ, showerI, showerH, showerG, showerF, showerE, showerD, showerC, showerB, showerA)
}

object VerilogGenerator extends App {
  emitVerilog(new Hotel(), args)
}