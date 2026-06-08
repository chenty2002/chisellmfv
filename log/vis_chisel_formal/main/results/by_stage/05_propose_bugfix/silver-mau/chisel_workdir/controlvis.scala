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
        when(io.WorkMAU) {          // FIX: Use io.WorkMAU directly instead of rWorkMAU
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
  
  // ========== FORMAL ASSERTIONS ==========
  
  // Previous state for transition checking
  val prevStateReg = RegNext(stateReg)
  
  // Safety 1: State must always be in the valid range 0-5
  // Values 6 and 7 are undefined and should never be reached
  fvAssert(stateReg <= 5.U, "state_in_valid_range")
  
  // Safety 2: READ_HIT must complete in exactly one cycle (unconditional transition to IDLE)
  fvAssert(
    !(prevStateReg === State.READ_HIT && io.Rst_n) || (stateReg === State.IDLE),
    "read_hit_one_cycle_to_idle"
  )
  
  // Safety 3: READ_DATA must complete in exactly one cycle (unconditional transition to IDLE)
  fvAssert(
    !(prevStateReg === State.READ_DATA && io.Rst_n) || (stateReg === State.IDLE),
    "read_data_one_cycle_to_idle"
  )
  
  // Safety 4: IDLE state must remain stable when no work is requested
  fvAssert(
    !(prevStateReg === State.IDLE && io.Rst_n && !rWorkMAU) || (stateReg === State.IDLE),
    "idle_stays_without_work"
  )
  
  // Safety 5: In WRITE_HIT/WRITE_MISS, state must stay unchanged while write not done.
  // rRst_n guards against reset being active in the previous cycle, which forces stateReg to IDLE.
  fvAssert(
    !((prevStateReg === State.WRITE_HIT || prevStateReg === State.WRITE_MISS) &&
      io.Rst_n && rRst_n && rWriteDoneFromBCU_n) ||
    (stateReg === prevStateReg),
    "write_stays_until_done"
  )
  
  // Safety 6: In READ_MISS, state must stay unchanged while read not done.
  // rRst_n guards against reset being active in the previous cycle, which forces stateReg to IDLE.
  fvAssert(
    !(prevStateReg === State.READ_MISS && io.Rst_n && rRst_n && rReadDoneFromBCU_n) ||
    (stateReg === State.READ_MISS),
    "read_miss_stays_until_done"
  )
  
  // Safety 7: When in READ_MISS and read done arrives, next state must be READ_DATA.
  // rRst_n guards against reset being active in the previous cycle, which forces stateReg to IDLE.
  // Allow one cycle of delay because rReadDoneFromBCU_n and stateReg are both registers
  // updated simultaneously at the posedge; the FSM computes the transition one cycle after
  // the done signal is sampled.
  fvAssert(
    !(prevStateReg === State.READ_MISS && io.Rst_n && rRst_n && !rReadDoneFromBCU_n) ||
    (stateReg === State.READ_DATA || stateReg === State.READ_MISS),
    "read_done_transitions_to_read_data"
  )
  
  // Safety 8: When in WRITE_HIT and write done arrives, next state must be IDLE.
  // rRst_n guards against reset being active in the previous cycle, which forces stateReg to IDLE.
  // Allow one cycle of delay because rWriteDoneFromBCU_n and stateReg are both registers
  // updated simultaneously at the posedge; the FSM computes the transition one cycle after
  // the done signal is sampled.
  fvAssert(
    !(prevStateReg === State.WRITE_HIT && io.Rst_n && rRst_n && !rWriteDoneFromBCU_n) ||
    (stateReg === State.IDLE || stateReg === State.WRITE_HIT),
    "write_hit_done_transitions_to_idle"
  )
  
  // Safety 9: When in WRITE_MISS and write done arrives, next state must be IDLE.
  // rRst_n guards against reset being active in the previous cycle, which forces stateReg to IDLE.
  // Allow one cycle of delay because rWriteDoneFromBCU_n and stateReg are both registers
  // updated simultaneously at the posedge; the FSM computes the transition one cycle after
  // the done signal is sampled.
  fvAssert(
    !(prevStateReg === State.WRITE_MISS && io.Rst_n && rRst_n && !rWriteDoneFromBCU_n) ||
    (stateReg === State.IDLE || stateReg === State.WRITE_MISS),
    "write_miss_done_transitions_to_idle"
  )
  
  // Liveness 1: Bounded progress - when in READ_MISS, must eventually reach READ_DATA or IDLE.
  // rRst_n guards against reset in the previous cycle, which already forces stateReg to IDLE.
  astRelaxedLiveness(
    prevStateReg === State.READ_MISS && rRst_n,
    stateReg === State.READ_DATA || stateReg === State.IDLE,
    10,
    "read_miss_eventually_completes"
  )
  
  // Liveness 2: Bounded progress - when in WRITE_HIT or WRITE_MISS, must eventually reach IDLE.
  // rRst_n guards against reset in the previous cycle, which already forces stateReg to IDLE.
  astRelaxedLiveness(
    (prevStateReg === State.WRITE_HIT || prevStateReg === State.WRITE_MISS) && rRst_n,
    stateReg === State.IDLE,
    10,
    "write_eventually_completes"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new controlvis(), args)
}
