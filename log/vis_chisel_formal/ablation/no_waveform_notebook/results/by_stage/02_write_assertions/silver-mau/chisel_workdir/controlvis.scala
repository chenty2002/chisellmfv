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

  // ===================================================================
  // Formal Verification Assertions
  // ===================================================================

  // --- Safety: State validity ---
  // Ensure the 3-bit state register never enters an undefined state (6 or 7).
  val validState = stateReg === State.IDLE ||
                   stateReg === State.READ_HIT ||
                   stateReg === State.READ_MISS ||
                   stateReg === State.READ_DATA ||
                   stateReg === State.WRITE_HIT ||
                   stateReg === State.WRITE_MISS
  fvAssert(validState, "state_is_valid")

  // --- Safety: One-cycle auto-transition states ---
  // READ_HIT unconditionally transitions to IDLE in one cycle.
  assertImpliesDelay(stateReg === State.READ_HIT,
                     stateReg === State.IDLE,
                     1,
                     "read_hit_to_idle")

  // READ_DATA unconditionally transitions to IDLE in one cycle.
  assertImpliesDelay(stateReg === State.READ_DATA,
                     stateReg === State.IDLE,
                     1,
                     "read_data_to_idle")

  // --- Safety: Condition-driven transition states ---
  // READ_MISS transitions to READ_DATA when BCU read completes (ReadDone_n asserted low).
  assertImpliesDelay(stateReg === State.READ_MISS && !rReadDoneFromBCU_n,
                     stateReg === State.READ_DATA,
                     1,
                     "read_miss_to_read_data")

  // WRITE_HIT transitions to IDLE when BCU write completes (WriteDone_n asserted low).
  assertImpliesDelay(stateReg === State.WRITE_HIT && !rWriteDoneFromBCU_n,
                     stateReg === State.IDLE,
                     1,
                     "write_hit_to_idle")

  // WRITE_MISS transitions to IDLE when BCU write completes (WriteDone_n asserted low).
  assertImpliesDelay(stateReg === State.WRITE_MISS && !rWriteDoneFromBCU_n,
                     stateReg === State.IDLE,
                     1,
                     "write_miss_to_idle")

  // --- Safety: IDLE stays IDLE when no work request ---
  // When idle and WorkMAU is not asserted, the state must remain IDLE next cycle.
  assertImpliesDelay(stateReg === State.IDLE && !rWorkMAU,
                     stateReg === State.IDLE,
                     1,
                     "idle_remains_idle")

  // --- Safety: Reset establishes IDLE ---
  // When !io.Rst_n is asserted, the state becomes IDLE on the next clock edge.
  assertImpliesDelay(!io.Rst_n,
                     stateReg === State.IDLE,
                     1,
                     "reset_goes_to_idle")

  // --- Bounded Liveness: Read miss eventually completes ---
  // If the FSM enters READ_MISS, it must reach IDLE within 100 cycles.
  // This catches deadlocks where the BCU read-done signal never arrives or the
  // FSM gets stuck in READ_MISS or READ_DATA.
  astRelaxedLiveness(stateReg === State.READ_MISS,
                     stateReg === State.IDLE,
                     100,
                     "read_miss_eventually_completes")

  // --- Bounded Liveness: Write operations eventually complete ---
  // If the FSM enters WRITE_HIT or WRITE_MISS, it must reach IDLE within 100 cycles.
  // This catches deadlocks where the BCU write-done signal never arrives or the
  // FSM gets stuck.
  astRelaxedLiveness(stateReg === State.WRITE_HIT || stateReg === State.WRITE_MISS,
                     stateReg === State.IDLE,
                     100,
                     "write_eventually_completes")
}

object VerilogGenerator extends App {
  emitVerilog(new controlvis(), args)
}
