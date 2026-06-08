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
      DATA_OUT := Mux(io.RESTART, avg(io.DATA_IN, io.DATA_IN), 0.U)
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
  
  // ========== Formal Verification Assertions ==========
  
  // Safety 1: Min/Max invariant -- RMIN should never exceed RMAX in signed comparison
  fvAssert(!signGt(RMIN, RMAX), "min_less_eq_max")
  
  // Safety 2: State machine stays in sC once reached (no illegal transitions)
  val reachedSC = RegInit(false.B)
  when(stato === sC) { reachedSC := true.B }
  fvAssert(!reachedSC || stato === sC, "stay_in_sC")
  
  // Safety 3: When RESTART is asserted in sC, DATA_OUT must equal avg(RMAX, RMIN)
  // DATA_OUT is a register that updates on the next cycle, so use assertNextStepWhen
  assertNextStepWhen(stato === sC && io.RESTART, DATA_OUT === avg(RMAX, RMIN), "restart_output_correct")
  
  // Safety 4: Pipeline shift -- REG1 shifts into REG2 each cycle in sC
  // REG2 gets the old value of REG1 from the previous cycle (parallel update)
  assertNextStepWhen(stato === sC, REG2 === RegNext(REG1), "pipeline_reg1_to_reg2")
  
  // Safety 5: Pipeline shift -- REG2 shifts into REG3 each cycle in sC
  // REG3 gets the old value of REG2 from the previous cycle (parallel update)
  assertNextStepWhen(stato === sC, REG3 === RegNext(REG2), "pipeline_reg2_to_reg3")
  
  // Safety 6: Pipeline shift -- REG3 shifts into REG4 each cycle in sC
  // REG4 gets the old value of REG3 from the previous cycle (parallel update)
  assertNextStepWhen(stato === sC, REG4 === RegNext(REG3), "pipeline_reg3_to_reg4")
  
  // Safety 7: When ENABLE is asserted in sC, RLAST captures DATA_IN on the next cycle
  // Snapshot DATA_IN when ENABLE is true; on the next cycle RLAST holds that value
  val rlast_snapshot = RegInit(0.U(8.W))
  when(stato === sC && io.ENABLE) {
    rlast_snapshot := io.DATA_IN
  }
  assertNextStepWhen(stato === sC && io.ENABLE, RLAST === rlast_snapshot, "rlast_update_on_enable")
  
  // Liveness: After reset deassertion, the state machine reaches sC within 3 cycles
  val resetDeasserted = RegInit(false.B)
  when(!reset.asBool) { resetDeasserted := true.B }
  astRelaxedLiveness(resetDeasserted && stato =/= sC, stato === sC, 3, "reaches_sC_after_reset")
  
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
}

object VerilogGenerator extends App {
  emitVerilog(new b04(), args)
}
