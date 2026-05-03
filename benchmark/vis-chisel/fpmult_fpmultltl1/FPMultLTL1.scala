package llmverify

import chisel3._
import chisel3.util._

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

// Monitor module
class monitor(MBITS: Int = 3, EBITS: Int = 4) extends Module {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val FPMstate = Input(UInt(2.W))
    val x = Input(UInt((MBITS + EBITS + 1).W))
    val y = Input(UInt((MBITS + EBITS + 1).W))
    val z = Input(UInt((MBITS + EBITS + 1).W))
    val fair = Output(Bool())
  })
  
  val state = RegInit(0.U(3.W))
  
  val validx = io.x(MBITS + EBITS - 1, MBITS) =/= 0.U || io.x(MBITS - 1, 0) === 0.U
  val validy = io.y(MBITS + EBITS - 1, MBITS) =/= 0.U || io.y(MBITS - 1, 0) === 0.U
  val validz = io.z(MBITS + EBITS - 1, MBITS) =/= 0.U || io.z(MBITS - 1, 0) === 0.U
  
  io.fair := (state === 5.U)
  
  when(state === 0.U) {
    when(io.FPMstate === 0.U && validx && validy) {
      state := 1.U
    } .otherwise {
      state := 0.U
    }
  } .elsewhen(state === 1.U) {
    state := 2.U
  } .elsewhen(state === 2.U) {
    state := 3.U
  } .elsewhen(state === 3.U) {
    when(validz) {
      state := 4.U
    } .otherwise {
      state := 5.U
    }
  } .elsewhen(state === 4.U) {
    state := 4.U
  } .elsewhen(state === 5.U) {
    state := 5.U
  }
}

// IEEE Floating Point Multiplier
class IEEEfpMult(MBITS: Int = 3, EBITS: Int = 4) extends Module {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val start = Input(Bool())
    val x = Input(UInt((MBITS + EBITS + 1).W))
    val y = Input(UInt((MBITS + EBITS + 1).W))
    val z = Output(UInt((MBITS + EBITS + 1).W))
    val state = Output(UInt(2.W))
  })
  
  // State definitions - use UInt literals instead of Enum
  val idle :: computing :: postprocessing :: Nil = List(0.U(2.W), 1.U(2.W), 2.U(2.W))
  val stateReg = RegInit(idle)
  
  // Registers for unpacked operands
  val xSign = RegInit(false.B)
  val xExp = RegInit(0.U(EBITS.W))
  val xMant = RegInit(0.U((MBITS + 1).W))
  val ySign = RegInit(false.B)
  val yExp = RegInit(0.U(EBITS.W))
  val yMant = RegInit(0.U((MBITS + 1).W))
  
  // Product components
  val signProd = RegInit(false.B)
  val expProd = RegInit(0.U((EBITS + 2).W))
  val mantProd = RegInit(0.U((2 * MBITS + 2).W))
  val zReg = RegInit(0.U((MBITS + EBITS + 1).W))
  
  // Instantiate integer multiplier
  val im = Module(new intMult)
  im.io.x := xMant
  im.io.y := yMant
  val combZ = im.io.z
  
  // Helper functions
  def isNaN(aExp: UInt, aMant: UInt): Bool = {
    aExp === Fill(EBITS, 1.U) && aMant =/= 0.U
  }
  
  def isZero(aExp: UInt, aMant: UInt): Bool = {
    aExp === 0.U && aMant === 0.U
  }
  
  def isInfinity(aExp: UInt, aMant: UInt): Bool = {
    aExp === Fill(EBITS, 1.U) && aMant === 0.U
  }
  
  // Combinational logic for rounding and normalization
  val msb = mantProd(2 * MBITS + 1)
  val lsb = mantProd(MBITS + 1)
  val guard = mantProd(MBITS)
  val round = mantProd(MBITS - 1)
  val sticky = mantProd(MBITS - 2, 0).orR
  
  // Round to nearest even
  val preMant = Wire(UInt((MBITS + 2).W))
  when(msb) {
    preMant := mantProd(2 * MBITS + 1, MBITS) + 
      Cat(0.U(MBITS.W), guard & (round | sticky | lsb), 0.U(1.W))
  } .otherwise {
    preMant := mantProd(2 * MBITS + 1, MBITS) + 
      Cat(0.U((MBITS + 1).W), round & (sticky | guard))
  }
  
  // Normalize
  val scaledExp = Wire(UInt((EBITS + 2).W))
  val scaledMant = Wire(UInt(MBITS.W))
  
  when(preMant(MBITS + 1)) {
    scaledExp := expProd + 1.U
    scaledMant := preMant(MBITS, 1)
  } .otherwise {
    scaledExp := expProd
    scaledMant := preMant(MBITS - 1, 0)
  }
  
  // State machine
  switch(stateReg) {
    is(idle) {
      when(io.start) {
        // Unpack operands
        xSign := io.x(MBITS + EBITS)
        xExp := io.x(MBITS + EBITS - 1, MBITS)
        xMant := Cat(1.U, io.x(MBITS - 1, 0))
        ySign := io.y(MBITS + EBITS)
        yExp := io.y(MBITS + EBITS - 1, MBITS)
        yMant := Cat(1.U, io.y(MBITS - 1, 0))
        stateReg := computing
      }
    }
    is(computing) {
      mantProd := combZ
      when(isZero(xExp, xMant(MBITS - 1, 0)) || isZero(yExp, yMant(MBITS - 1, 0))) {
        expProd := 0.U
      } .otherwise {
        expProd := xExp +& yExp - Fill(EBITS - 1, 1.U)
      }
      signProd := xSign ^ ySign
      stateReg := postprocessing
    }
    is(postprocessing) {
      when(isNaN(xExp, xMant(MBITS - 1, 0)) || isNaN(yExp, yMant(MBITS - 1, 0)) ||
           (isInfinity(xExp, xMant(MBITS - 1, 0)) && isZero(yExp, yMant(MBITS - 1, 0))) ||
           (isZero(xExp, xMant(MBITS - 1, 0)) && isInfinity(yExp, yMant(MBITS - 1, 0)))) {
        zReg := Cat(0.U, Fill(EBITS, 1.U), Fill(MBITS, 1.U)) // NaN
      } .elsewhen(isInfinity(xExp, xMant(MBITS - 1, 0)) || isInfinity(yExp, yMant(MBITS - 1, 0))) {
        zReg := Cat(signProd, Fill(EBITS, 1.U), 0.U(MBITS.W)) // +/- Infinity
      } .otherwise {
        // Check for underflow and overflow
        when(scaledExp(EBITS + 1) || scaledExp === 0.U) {
          zReg := Cat(signProd, 0.U((MBITS + EBITS).W)) // signed zero
        } .elsewhen(scaledExp >= Fill(EBITS, 1.U)) { // overflow
          zReg := Cat(signProd, Fill(EBITS, 1.U), 0.U(MBITS.W)) // +/- Infinity
        } .otherwise {
          zReg := Cat(signProd, scaledExp(EBITS - 1, 0), scaledMant)
        }
      }
      stateReg := idle
    }
  }
  
  io.z := zReg
  io.state := stateReg
}

// Top-level testbench module
class fvFPMult(MBITS: Int = 3, EBITS: Int = 4) extends Module {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val i = Input(UInt((MBITS + EBITS + 1).W))
    val j = Input(UInt((MBITS + EBITS + 1).W))
    val z = Output(UInt((MBITS + EBITS + 1).W))
    val state = Output(UInt(2.W))
    val fair = Output(Bool())
    val x = Output(UInt((MBITS + EBITS + 1).W)) // For debugging
    val y = Output(UInt((MBITS + EBITS + 1).W)) // For debugging
  })
  
  // Registers to latch inputs
  val x = RegInit(0.U((MBITS + EBITS + 1).W))
  val y = RegInit(0.U((MBITS + EBITS + 1).W))
  
  // Start signal (always true after first cycle)
  val start = RegInit(true.B)
  
  // Instantiate IEEE floating point multiplier
  val fpm = Module(new IEEEfpMult(MBITS, EBITS))
  fpm.io.clock := io.clock
  fpm.io.start := start
  fpm.io.x := x
  fpm.io.y := y
  
  // Instantiate monitor
  val mtr = Module(new monitor(MBITS, EBITS))
  mtr.io.clock := io.clock
  mtr.io.FPMstate := fpm.io.state
  mtr.io.x := x
  mtr.io.y := y
  mtr.io.z := fpm.io.z
  
  // Latch inputs on clock edge
  when(true.B) { // Always latch inputs
    x := io.i
    y := io.j
  }
  
  // Connect outputs
  io.z := fpm.io.z
  io.state := fpm.io.state
  io.fair := mtr.io.fair
  io.x := x
  io.y := y
}

object VerilogGenerator extends App {
  emitVerilog(new fvFPMult(), args)
}