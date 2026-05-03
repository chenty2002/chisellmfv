package llmverify

import chisel3._
import chisel3.util._

// VIS testbench for a sequential floating point multiplier.
// The purpose of this testbench is exclusively to latch the inputs, so
// that CTL properties may refer to them.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>
//
class fvFPMult(val MBITS: Int = 3, val EBITS: Int = 4) extends Module {
  val io = IO(new Bundle {
    val i = Input(UInt((MBITS + EBITS + 1).W))
    val j = Input(UInt((MBITS + EBITS + 1).W))
    val z = Output(UInt((MBITS + EBITS + 1).W))
    val x = Output(UInt((MBITS + EBITS + 1).W))
    val y = Output(UInt((MBITS + EBITS + 1).W))
  })
  
  // Internal registers
  val x = Reg(UInt((MBITS + EBITS + 1).W))
  val y = Reg(UInt((MBITS + EBITS + 1).W))
  
  // Instantiate IEEEfpMult
  val fpMult = Module(new IEEEfpMult(MBITS, EBITS))
  fpMult.io.start := true.B
  fpMult.io.x := x
  fpMult.io.y := y
  
  // Latch inputs on clock edge
  x := io.i
  y := io.j
  
  // Connect outputs
  io.z := fpMult.io.z
  io.x := x
  io.y := y
}

// Floating point multiplier.
// Not exactly IEEE 754-compliant, but largely inspired to the standard.
//
// The significand uses the hidden bit and is between 1 (included) and 2
// (excluded).
// The exponent uses the excess (2**(n-1) - 1) representation. For single
// precision, this is excess 127.
// The smallest exponent (0) is used for the represenation of 0. Denormals
// are not supported.
// The largest exponent is used for infinities and NaNs. Infinities use
// the smallest possible significand (all zeroes). Everything else is deemed
// a NaN. No distinction is made between signalling and non-signalling NaNs.
// When the multiplier generates a NaN, it uses the all-one significand.
// One multiplication takes three clock cycles and it is not pipelined.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>
//
class IEEEfpMult(val MBITS: Int = 3, val EBITS: Int = 4) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val x = Input(UInt((MBITS + EBITS + 1).W))
    val y = Input(UInt((MBITS + EBITS + 1).W))
    val z = Output(UInt((MBITS + EBITS + 1).W))
  })
  
  // State definitions
  val idle :: computing :: postprocessing :: Nil = Enum(3)
  val state = RegInit(idle)
  
  // Unpacked operands
  val xSign = Reg(Bool())
  val xExp = Reg(UInt(EBITS.W))
  val xMant = Reg(UInt((MBITS + 1).W))
  val ySign = Reg(Bool())
  val yExp = Reg(UInt(EBITS.W))
  val yMant = Reg(UInt((MBITS + 1).W))
  
  // Product components
  val signProd = Reg(Bool())
  val expProd = Reg(UInt((EBITS + 2).W))
  val mantProd = Reg(UInt((2 * MBITS + 2).W))
  
  // Output register
  val z = Reg(UInt((MBITS + EBITS + 1).W))
  
  // Instantiate integer multiplier
  val intMult = Module(new intMult())
  intMult.io.x := xMant
  intMult.io.y := yMant
  val combZ = intMult.io.z
  
  // Helper functions
  def isNaN(aExp: UInt, aMant: UInt): Bool = {
    (aExp === Fill(EBITS, 1.U)) && (aMant =/= 0.U)
  }
  
  def isZero(aExp: UInt, aMant: UInt): Bool = {
    (aExp === 0.U) && (aMant === 0.U)
  }
  
  def isInfinity(aExp: UInt, aMant: UInt): Bool = {
    (aExp === Fill(EBITS, 1.U)) && (aMant === 0.U)
  }
  
  // Combinational logic for rounding and normalization
  val msb = mantProd(2 * MBITS + 1)
  val lsb = mantProd(MBITS + 1)
  val guard = mantProd(MBITS)
  val round = mantProd(MBITS - 1)
  val sticky = mantProd(MBITS - 2, 0).orR
  
  // Round to nearest even
  val preMant = Mux(msb,
    mantProd(2 * MBITS + 1, MBITS) + 
      Cat(0.U(MBITS.W), guard & (round | sticky | lsb), 0.U(1.W)),
    mantProd(2 * MBITS + 1, MBITS) + 
      Cat(0.U((MBITS + 1).W), round & (sticky | guard))
  )
  
  // Normalize
  val scaledExp = Mux(preMant(MBITS + 1),
    expProd + 1.U,
    expProd
  )
  
  val scaledMant = Mux(preMant(MBITS + 1),
    preMant(MBITS, 1),
    preMant(MBITS - 1, 0)
  )
  
  // State machine
  switch(state) {
    is(idle) {
      when(io.start) {
        // Unpack operands
        xSign := io.x(MBITS + EBITS)
        xExp := io.x(MBITS + EBITS - 1, MBITS)
        xMant := Cat(1.U(1.W), io.x(MBITS - 1, 0))
        ySign := io.y(MBITS + EBITS)
        yExp := io.y(MBITS + EBITS - 1, MBITS)
        yMant := Cat(1.U(1.W), io.y(MBITS - 1, 0))
        state := computing
      }
    }
    is(computing) {
      mantProd := combZ
      when(isZero(xExp, xMant(MBITS - 1, 0)) || isZero(yExp, yMant(MBITS - 1, 0))) {
        expProd := 0.U
      }.otherwise {
        expProd := xExp +& yExp - Fill(EBITS - 1, 1.U)
      }
      signProd := xSign ^ ySign
      state := postprocessing
    }
    is(postprocessing) {
      when(isNaN(xExp, xMant(MBITS - 1, 0)) || isNaN(yExp, yMant(MBITS - 1, 0)) ||
           (isInfinity(xExp, xMant(MBITS - 1, 0)) && isZero(yExp, yMant(MBITS - 1, 0))) ||
           (isZero(xExp, xMant(MBITS - 1, 0)) && isInfinity(yExp, yMant(MBITS - 1, 0)))) {
        z := Cat(0.U(1.W), Fill(EBITS, 1.U), Fill(MBITS, 1.U)) // NaN
      }.elsewhen(isInfinity(xExp, xMant(MBITS - 1, 0)) || isInfinity(yExp, yMant(MBITS - 1, 0))) {
        z := Cat(signProd, Fill(EBITS, 1.U), 0.U(MBITS.W)) // +/- Infinity
      }.otherwise {
        // Check for underflow and overflow
        when(scaledExp(EBITS + 1) || (scaledExp === 0.U)) {
          z := Cat(signProd, 0.U((MBITS + EBITS).W)) // signed zero
        }.elsewhen(scaledExp >= Fill(EBITS, 1.U)) {
          z := Cat(signProd, Fill(EBITS, 1.U), 0.U(MBITS.W)) // +/- Infinity
        }.otherwise {
          z := Cat(signProd, scaledExp(EBITS - 1, 0), scaledMant)
        }
      }
      state := idle
    }
  }
  
  io.z := z
}

// Integer multiplier module
class intMult extends Module {
  val io = IO(new Bundle {
    val x = Input(UInt(4.W))
    val y = Input(UInt(4.W))
    val z = Output(UInt(8.W))
  })
  
  val int0 = Wire(UInt(4.W))
  val int1 = Wire(UInt(6.W))
  val int2 = Wire(UInt(7.W))
  val int3 = Wire(UInt(8.W))
  
  int0 := Fill(4, io.y(0)) & io.x
  int1 := int0 + Cat(Fill(4, io.y(1)) & io.x, 0.U(1.W))
  int2 := int1 + Cat(Fill(4, io.y(2)) & io.x, 0.U(2.W))
  int3 := int2 + Cat(Fill(4, io.y(3)) & io.x, 0.U(3.W))
  
  io.z := int3
}

object VerilogGenerator extends App {
  emitVerilog(new fvFPMult(), args)
}