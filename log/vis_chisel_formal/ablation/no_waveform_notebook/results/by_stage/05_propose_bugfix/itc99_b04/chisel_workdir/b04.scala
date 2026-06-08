package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

// Verilog translation of the original b04 circuit from the ITC99
// benchmark set.
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

class b04 extends Module with Formal {
  val io = IO(new Bundle {
    val RESTART = Input(Bool())
    val AVERAGE = Input(Bool())
    val ENABLE = Input(Bool())
    val DATA_IN = Input(UInt(8.W))
    val DATA_OUT = Output(UInt(8.W))
    
    // Additional outputs to preserve internal registers
    val stato = Output(UInt(2.W))
    val RMAX = Output(UInt(8.W))
    val RMIN = Output(UInt(8.W))
    val RLAST = Output(UInt(8.W))
    val REG1 = Output(UInt(8.W))
    val REG2 = Output(UInt(8.W))
    val REG3 = Output(UInt(8.W))
    val REG4 = Output(UInt(8.W))
  })
  
  // State enumeration
  val sA :: sB :: sC :: Nil = Enum(3)
  val stato = RegInit(sA)
  
  // Registers
  val RMAX = RegInit(0.U(8.W))
  val RMIN = RegInit(0.U(8.W))
  val RLAST = RegInit(0.U(8.W))
  val REG1 = RegInit(0.U(8.W))
  val REG2 = RegInit(0.U(8.W))
  val REG3 = RegInit(0.U(8.W))
  val REG4 = RegInit(0.U(8.W))
  val DATA_OUT = RegInit(0.U(8.W))
  
  // Two's complement function
  def tc(x: UInt): UInt = {
    val width = x.getWidth
    (~x) + 1.U(width.W)
  }
  
  // Average function for signed numbers
  def avg(x: UInt, y: UInt): UInt = {
    val tmp = Cat(x(7), x) + Cat(y(7), y) // 9-bit sum with sign extension
    val tmp2 = tc(Cat(0.U(1.W), tmp(6, 0))) // 8-bit two's complement of lower 7 bits
    
    Mux(tmp(8), // if sign bit is set
        tc(Cat(tmp2(7), tmp2(7, 1))), // handle negative case
        Cat(0.U(2.W), tmp(6, 1))  // handle positive case
    )
  }
  
  // Signed greater than comparison
  def signGt(x: UInt, y: UInt): Bool = {
    (!x(7) && y(7)) || 
    (x(7) === y(7) && x(6, 0) > y(6, 0))
  }
  
  // State machine logic
  switch(stato) {
    is(sA) {
      stato := sB
    }
    is(sB) {
      RMAX := io.DATA_IN
      RMIN := io.DATA_IN
      REG1 := 0.U
      REG2 := 0.U
      REG3 := 0.U
      REG4 := 0.U
      RLAST := 0.U
      DATA_OUT := 0.U
      stato := sC
    }
    is(sC) {
      when(io.ENABLE) {
        RLAST := io.DATA_IN
      }
      
      when(io.RESTART) {
        DATA_OUT := avg(RMAX, RMIN)
      }.elsewhen(io.ENABLE) {
        when(io.AVERAGE) {
          DATA_OUT := REG4
        }.otherwise {
          DATA_OUT := avg(io.DATA_IN, REG4)
        }
      }.otherwise {
        DATA_OUT := RLAST
      }
      
      when(signGt(io.DATA_IN, RMAX)) {
        RMAX := io.DATA_IN
      }.elsewhen(signGt(RMIN, io.DATA_IN)) {
        RMIN := io.DATA_IN
      }
      
      REG4 := REG3
      REG3 := REG2
      REG2 := REG1
      REG1 := io.DATA_IN
      
      stato := sC
    }
  }
  
  // Connect outputs
  io.DATA_OUT := DATA_OUT
  io.stato := stato
  io.RMAX := RMAX
  io.RMIN := RMIN
  io.RLAST := RLAST
  io.REG1 := REG1
  io.REG2 := REG2
  io.REG3 := REG3
  io.REG4 := REG4

  // ========== Formal Verification Assertions ==========

  // --- Shadow registers for delayed-property checking ---
  // These capture control-signal values from the previous cycle so that
  // assertions on registered outputs (which reflect the prior cycle's
  // control decision) can be expressed correctly.
  val prev_sC       = RegNext(stato === sC)
  val prev_RESTART  = RegNext(io.RESTART)
  val prev_ENABLE   = RegNext(io.ENABLE)
  val prev_AVERAGE  = RegNext(io.AVERAGE)

  // --- 1. State encoding safety ---
  // stato is 2-bit but only encodes 3 states (sA=0, sB=1, sC=2).
  // The encoding 3 is illegal and should never appear.
  fvAssert(stato =/= 3.U, "state_valid_encoding")

  // --- 2. State stability in sC ---
  // Once the machine enters the steady operating state sC, it must
  // never leave (sC case unconditionally assigns stato := sC).
  // Use forward-looking semantic: if stato was sC last cycle,
  // it must be sC this cycle.
  fvAssert(!RegNext(stato === sC) || (stato === sC), "state_stays_sC")

  // --- 3. Signed max/min invariant ---
  // After initialization in sB, RMAX (signed maximum tracker) must
  // always be >= RMIN (signed minimum tracker).
  fvAssert(!(stato === sC) || !signGt(RMIN, RMAX), "rmax_ge_rmin")

  // --- 4. Monotonicity of RMAX and RMIN in steady state ---
  // In sC (excluding the first entry cycle where RegNext(RMAX)=0 reset
  // may be > the sB-initialized RMAX when DATA_IN is negative, and
  // similarly RegNext(RMIN)=0 may be < a positive DATA_IN), RMAX can
  // only increase (or stay) and RMIN can only decrease (or stay) in
  // signed comparison.
  fvAssert(!(stato === sC) || !prev_sC || !signGt(RegNext(RMAX), RMAX), "rmax_monotonic")
  fvAssert(!(stato === sC) || !prev_sC || !signGt(RMIN, RegNext(RMIN)), "rmin_monotonic")

  // --- 5. RLAST update correctness ---
  // When ENABLE is asserted in sC, RLAST must capture DATA_IN.
  // Because RLAST is a register, the captured value appears one
  // cycle later.
  val prev_sC_enable = RegNext(stato === sC && io.ENABLE)
  fvAssert(!prev_sC_enable || (RLAST === RegNext(io.DATA_IN)), "rlast_update")

  // --- 6. DATA_OUT correctness (four mutually exclusive paths) ---
  //
  // DATA_OUT is a register whose next value is selected in sC by the
  // priority-encoded control logic.  Each assertion uses the previous
  // cycle's control signals and the previous cycle's operand values
  // (via RegNext) to match the register-update timing.

  // Path 1: RESTART → DATA_OUT = avg(RMAX, RMIN)
  fvAssert(!(prev_sC && prev_RESTART) ||
    (DATA_OUT === avg(RegNext(RMAX), RegNext(RMIN))),
    "dataout_restart")

  // Path 2: ENABLE && AVERAGE (and not RESTART) → DATA_OUT = REG4
  fvAssert(!(prev_sC && prev_ENABLE && prev_AVERAGE && !prev_RESTART) ||
    (DATA_OUT === RegNext(REG4)),
    "dataout_enable_avg")

  // Path 3: ENABLE && !AVERAGE (and not RESTART) → DATA_OUT = avg(DATA_IN, REG4)
  fvAssert(!(prev_sC && prev_ENABLE && !prev_AVERAGE && !prev_RESTART) ||
    (DATA_OUT === avg(RegNext(io.DATA_IN), RegNext(REG4))),
    "dataout_enable_no_avg")

  // Path 4: !RESTART && !ENABLE → DATA_OUT = RLAST
  fvAssert(!(prev_sC && !prev_RESTART && !prev_ENABLE) ||
    (DATA_OUT === RegNext(RLAST)),
    "dataout_idle")

  // --- 7. Bounded liveness ---
  // After reset the state machine must reach the steady operating
  // state sC within 3 clock cycles (sA → sB → sC).
  // Manually delay the first_cycle marker by 3 cycles using explicit
  // RegNext stages with false.B initialization so formal verification
  // cannot assign symbolic initial values to the delay stages.
  val first_cycle = RegInit(true.B)
  first_cycle := false.B
  val first_cycle_d1 = RegNext(first_cycle, false.B)
  val first_cycle_d2 = RegNext(first_cycle_d1, false.B)
  val first_cycle_d3 = RegNext(first_cycle_d2, false.B)
  fvAssert(!first_cycle_d3 || (stato === sC), "liveness_reach_sC")
}

object VerilogGenerator extends App {
  emitVerilog(new b04(), args)
}
