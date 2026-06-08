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

  // ========== FORMAL ASSERTIONS ==========

  // ---------------------------------------------------------------------------
  // 1. Valid state encoding
  //    stato is 2-bit but only 3 states (0,1,2) are used.  Value 3 is illegal.
  // ---------------------------------------------------------------------------
  fvAssert(stato =/= 3.U, "state_encoding_valid")

  // ---------------------------------------------------------------------------
  // 2. FSM transition determinism
  //    sA -> sB -> sC -> sC  (unconditional every cycle)
  //    Use forward-looking semantics: if previous state was sX, current must be sY.
  // ---------------------------------------------------------------------------
  fvAssert(RegNext(stato) =/= sA || stato === sB, "trans_sA_to_sB")
  fvAssert(RegNext(stato) =/= sB || stato === sC, "trans_sB_to_sC")
  fvAssert(RegNext(stato) =/= sC || stato === sC, "trans_sC_stays_sC")

  // ---------------------------------------------------------------------------
  // 3. Shift-register pipeline correctness (in state sC)
  //    REG1 <- DATA_IN, REG2 <- REG1, REG3 <- REG2, REG4 <- REG3
  //    Check: when in sC, the *next* value of REG_{i+1} equals the *current* REG_i
  // ---------------------------------------------------------------------------
  fvAssert(stato =/= sC || RegNext(REG4) === REG3, "shift_reg_4_from_3")
  fvAssert(stato =/= sC || RegNext(REG3) === REG2, "shift_reg_3_from_2")
  fvAssert(stato =/= sC || RegNext(REG2) === REG1, "shift_reg_2_from_1")
  fvAssert(stato =/= sC || RegNext(REG1) === io.DATA_IN, "shift_reg_1_from_data")

  // ---------------------------------------------------------------------------
  // 4. RMAX non-decreasing (signed comparison)
  //    RMAX should never decrease; it either stays the same or increases.
  // ---------------------------------------------------------------------------
  fvAssert(
    RegNext(RMAX) === RMAX || signGt(RMAX, RegNext(RMAX)),
    "rmax_non_decreasing"
  )

  // ---------------------------------------------------------------------------
  // 5. RMIN non-increasing (signed comparison)
  //    RMIN should never increase; it either stays the same or decreases.
  // ---------------------------------------------------------------------------
  fvAssert(
    RegNext(RMIN) === RMIN || signGt(RegNext(RMIN), RMIN),
    "rmin_non_increasing"
  )

  // ---------------------------------------------------------------------------
  // 6. DATA_OUT functional correctness (delay-1 implication)
  //    The assertImpliesDelay macro generates unconditional shadow registers
  //    and unconditional assertions.  Instead, use fvAssert with RegNext on
  //    the antecedent to implement proper forward-looking semantics.
  //
  //    When RESTART is asserted, DATA_OUT gets avg(RMAX,RMIN) on the next cycle.
  //    When ENABLE&&AVERAGE, DATA_OUT gets REG4 on the next cycle.
  //    When ENABLE&&!AVERAGE, DATA_OUT gets avg(DATA_IN,REG4) on the next cycle.
  //    When !RESTART&&!ENABLE, DATA_OUT gets RLAST on the next cycle.
  //
  //    The pattern is: fvAssert(!RegNext(antecedent) || consequent_with_delayed_rhs)
  //    RegNext(antecedent) is true if the antecedent was true *last* cycle.
  //    consequent_with_delayed_rhs uses RegNext() on signals that are also
  //    updated in the same sC cycle to capture their pre-update snapshot.
  // ---------------------------------------------------------------------------
  fvAssert(
    !RegNext(io.RESTART) || DATA_OUT === avg(RegNext(RMAX), RegNext(RMIN)),
    "restart_data_out"
  )
  fvAssert(
    !RegNext(io.ENABLE && io.AVERAGE && !io.RESTART) || DATA_OUT === RegNext(REG4),
    "average_data_out"
  )
  fvAssert(
    !RegNext(io.ENABLE && !io.AVERAGE && !io.RESTART) || DATA_OUT === avg(RegNext(io.DATA_IN), RegNext(REG4)),
    "ena_no_avg_data_out"
  )
  fvAssert(
    !RegNext(!io.ENABLE && !io.RESTART) || DATA_OUT === RegNext(RLAST),
    "default_data_out"
  )

  // ---------------------------------------------------------------------------
  // 7. Liveness: the FSM must reach and stay in sC
  //    After reset, sA->sB->sC takes at most 3 cycles.
  //    Once in sC, the FSM must never leave sC.
  // ---------------------------------------------------------------------------
  // If stato is ever not sC for more than 5 consecutive cycles, fail.
  // (After reset the initial sA->sB->sC sequence takes at most 3 cycles.)
  assertLivenessTimer(stato =/= sC, false.B, 5, "liveness_fsm_reaches_sC")

  // ---------------------------------------------------------------------------
  // 8. Mutex on control signals (optional but useful for correctness)
  //    RESTART, ENABLE, and AVERAGE are independent; no mutex needed.
  //    However, the register-update priority is well-defined.
  // ---------------------------------------------------------------------------
  // When the FSM is not in sC, no register updates happen;
  // all registers are initialized in sB, then shift in sC.
  fvAssert(stato === sC || RegNext(RMAX) === RMAX, "rmax_stable_outside_sC")
  fvAssert(stato === sC || RegNext(RMIN) === RMIN, "rmin_stable_outside_sC")
}

object VerilogGenerator extends App {
  emitVerilog(new b04(), args)
}
