package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

class controlvis extends Module with Formal {
  val io = IO(new Bundle {
    val Rst_n = Input(Bool())
    val WorkMAU = Input(Bool())
    val AccessMode = Input(UInt(2.W))
    val Match = Input(Bool())
    val Valid = Input(Bool())
    val ReadDoneFromBCU_n = Input(Bool())
    val WriteDoneFromBCU_n = Input(Bool())
    
    val Write = Output(Bool())
    val BCURequest_n = Output(Bool())
    val BCUWriteRequest_n = Output(Bool())
    val BCUDataOE = Output(Bool())
    val CacheDataSelect = Output(Bool())
    val MAUNotReady_n = Output(Bool())
    
    // Debug outputs to preserve internal signals
    val State = Output(UInt(3.W))
    val Vector = Output(UInt(6.W))
  })
  
  // State definitions using object with UInt values
  object State {
    val IDLE = 0.U(3.W)
    val READ_HIT = 1.U(3.W)
    val READ_MISS = 2.U(3.W)
    val READ_DATA = 3.U(3.W)
    val WRITE_HIT = 4.U(3.W)
    val WRITE_MISS = 5.U(3.W)
  }
  
  // State register
  val stateReg = RegInit(State.IDLE)
  
  // Input registers (synchronized)
  val rRst_n = RegNext(io.Rst_n, false.B)
  val rWorkMAU = RegNext(io.WorkMAU, false.B)
  val rAccessMode = RegNext(io.AccessMode, 0.U)
  val rMatch = RegNext(io.Match, false.B)
  val rValid = RegNext(io.Valid, false.B)
  val rReadDoneFromBCU_n = RegNext(io.ReadDoneFromBCU_n, false.B)
  val rWriteDoneFromBCU_n = RegNext(io.WriteDoneFromBCU_n, false.B)
  
  // Vector register for combinatorial logic - use RegInit to ensure initialization
  val vector = RegInit("b011001".U(6.W))
  
  // State machine logic
  when(!io.Rst_n) {
    stateReg := State.IDLE
  }.otherwise {
    switch(stateReg) {
      is(State.IDLE) {
        when(rWorkMAU) {
          when(rAccessMode(0)) { // write
            when(rValid && rMatch) { // write hit
              stateReg := State.WRITE_HIT
            }.otherwise { // write miss
              stateReg := State.WRITE_MISS
            }
          }.elsewhen(!rAccessMode(0)) { // read
            when(rValid && rMatch) { // read hit
              stateReg := State.READ_HIT
            }.otherwise { // read miss
              stateReg := State.READ_MISS
            }
          }
        }
      }
      is(State.READ_HIT) {
        stateReg := State.IDLE
      }
      is(State.READ_MISS) {
        when(!rReadDoneFromBCU_n) { // data is ready
          stateReg := State.READ_DATA // update cache
        }
      }
      is(State.READ_DATA) {
        stateReg := State.IDLE
      }
      is(State.WRITE_HIT) {
        when(!rWriteDoneFromBCU_n) {
          stateReg := State.IDLE
        }
      }
      is(State.WRITE_MISS) {
        when(!rWriteDoneFromBCU_n) {
          stateReg := State.IDLE
        }
      }
    }
  }
  
  // Combinatorial logic for vector assignment using when/elsewhen/otherwise
  // This ensures all cases are covered
  when(stateReg === State.IDLE) {
    vector := "b011001".U
  }.elsewhen(stateReg === State.READ_HIT) {
    vector := "b011000".U
  }.elsewhen(stateReg === State.READ_MISS) {
    vector := "b001000".U
  }.elsewhen(stateReg === State.READ_DATA) {
    vector := "b111010".U
  }.elsewhen(stateReg === State.WRITE_HIT) {
    vector := "b110100".U
  }.elsewhen(stateReg === State.WRITE_MISS) {
    vector := "b010100".U
  }.otherwise {
    vector := "b011001".U // default to IDLE value
  }
  
  // Output assignments
  io.Write := vector(5)
  io.BCURequest_n := vector(4)
  io.BCUWriteRequest_n := vector(3)
  io.BCUDataOE := vector(2)
  io.CacheDataSelect := vector(1)
  io.MAUNotReady_n := vector(0)
  
  // Debug outputs
  io.State := stateReg
  io.Vector := vector

  // =====================================================
  // Formal Verification Assertions
  // =====================================================

  // Safety: stateReg must always be one of the six defined states (0-5).
  // Illegal values 6 and 7 indicate an encoding violation.
  fvAssert(
    stateReg === State.IDLE    || stateReg === State.READ_HIT  ||
    stateReg === State.READ_MISS || stateReg === State.READ_DATA ||
    stateReg === State.WRITE_HIT || stateReg === State.WRITE_MISS,
    "state_encoding_valid"
  )

  // Structural: READ_HIT unconditionally transitions to IDLE in the next cycle.
  assertNextStepWhen(stateReg === State.READ_HIT, stateReg === State.IDLE,
    "read_hit_to_idle")

  // Structural: READ_DATA unconditionally transitions to IDLE in the next cycle.
  assertNextStepWhen(stateReg === State.READ_DATA, stateReg === State.IDLE,
    "read_data_to_idle")

  // Correctness: when in READ_MISS and the BCU signals read data ready
  // (!rReadDoneFromBCU_n asserted, active low), the next state is READ_DATA,
  // not IDLE or anything else.
  assertNextStepWhen(stateReg === State.READ_MISS && !rReadDoneFromBCU_n,
    stateReg === State.READ_DATA,
    "read_miss_to_read_data_on_done")

  // Correctness: when in WRITE_HIT and the BCU signals write done
  // (!rWriteDoneFromBCU_n asserted, active low), the next state is IDLE.
  assertNextStepWhen(stateReg === State.WRITE_HIT && !rWriteDoneFromBCU_n,
    stateReg === State.IDLE,
    "write_hit_to_idle_on_done")

  // Correctness: when in WRITE_MISS and the BCU signals write done,
  // the next state is IDLE.
  assertNextStepWhen(stateReg === State.WRITE_MISS && !rWriteDoneFromBCU_n,
    stateReg === State.IDLE,
    "write_miss_to_idle_on_done")

  // Bounded liveness / progress: once the state machine leaves IDLE,
  // it must return to IDLE within a bounded number of cycles.
  // This catches deadlocks, stuck FSMs, or lost responses.
  astRelaxedLiveness(stateReg =/= State.IDLE, stateReg === State.IDLE,
    50, "return_to_idle_bounded")
}

object VerilogGenerator extends App {
  emitVerilog(new controlvis(), args)
}
