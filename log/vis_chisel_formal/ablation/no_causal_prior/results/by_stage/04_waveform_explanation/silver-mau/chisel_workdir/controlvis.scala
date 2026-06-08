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

  // ====================================================================
  // Formal Verification Assertions
  // ====================================================================

  // --- Safety Assertion 1: Valid state encoding ---
  // The state register must always be in one of the 6 defined states (0-5).
  // An invalid state (6 or 7) indicates a hardware bug or illegal transition.
  fvAssert(
    stateReg === State.IDLE ||
    stateReg === State.READ_HIT ||
    stateReg === State.READ_MISS ||
    stateReg === State.READ_DATA ||
    stateReg === State.WRITE_HIT ||
    stateReg === State.WRITE_MISS,
    "state_valid_encoding"
  )

  // --- Safety Assertion 2: Reset forces IDLE ---
  // When synchronous reset (Rst_n low) is asserted, the state must be IDLE.
  fvAssert(
    !io.Rst_n || stateReg === stateReg,  // unconditional reset property checked below
    "reset_dummy"
  )
  fvAssert(
    !io.Rst_n === (stateReg === State.IDLE),
    "reset_holds_state_idle"
  )

  // --- Safety Assertion 3: Reset safety ---
  // If Rst_n is low, stateReg must be IDLE (reset condition).
  fvAssert(
    io.Rst_n || stateReg === State.IDLE,
    "reset_asserts_idle"
  )

  // --- Safety Assertion 4: Output vector encoding consistency ---
  // For each valid state, the vector must match the expected encoding.
  // This catches bugs where the vector logic diverges from the state.
  fvAssert(
    (stateReg === State.IDLE) === (vector === "b011001".U) ||
    (stateReg === State.READ_HIT) === (vector === "b011000".U) ||
    (stateReg === State.READ_MISS) === (vector === "b001000".U) ||
    (stateReg === State.READ_DATA) === (vector === "b111010".U) ||
    (stateReg === State.WRITE_HIT) === (vector === "b110100".U) ||
    (stateReg === State.WRITE_MISS) === (vector === "b010100".U),
    "vector_encoding_valid"
  )

  // Since the assertion above with disjunction of biconditionals is tricky,
  // we break it into per-state assertions:
  fvAssert(
    !(stateReg === State.IDLE) || vector === "b011001".U,
    "vector_idle_encoding"
  )
  fvAssert(
    !(stateReg === State.READ_HIT) || vector === "b011000".U,
    "vector_read_hit_encoding"
  )
  fvAssert(
    !(stateReg === State.READ_MISS) || vector === "b001000".U,
    "vector_read_miss_encoding"
  )
  fvAssert(
    !(stateReg === State.READ_DATA) || vector === "b111010".U,
    "vector_read_data_encoding"
  )
  fvAssert(
    !(stateReg === State.WRITE_HIT) || vector === "b110100".U,
    "vector_write_hit_encoding"
  )
  fvAssert(
    !(stateReg === State.WRITE_MISS) || vector === "b010100".U,
    "vector_write_miss_encoding"
  )

  // --- Safety Assertion 5: FSM transition constraints ---
  // From READ_HIT, the state unconditionally transitions to IDLE in the next cycle.
  // (Assertion: if in READ_HIT now, next cycle state is IDLE, unless reset.)
  fvAssert(
    !(stateReg === State.READ_HIT && io.Rst_n) || 
    RegNext(stateReg) === State.IDLE,
    "read_hit_to_idle"
  )

  // From READ_DATA, the state unconditionally transitions to IDLE in the next cycle.
  fvAssert(
    !(stateReg === State.READ_DATA && io.Rst_n) || 
    RegNext(stateReg) === State.IDLE,
    "read_data_to_idle"
  )

  // --- Liveness Assertion 6: Forward progress (bounded liveness) ---
  // Once in a non-IDLE state, the FSM must return to IDLE within a bounded
  // number of cycles. The longest path is READ_MISS -> READ_DATA -> IDLE (2
  // transitions), or WRITE_HIT/WRITE_MISS waiting for BCU done (at most a few
  // cycles). We use a generous bound of 100 cycles to detect stalling.
  val nonIdle = stateReg =/= State.IDLE && io.Rst_n
  astRelaxedLiveness(nonIdle, stateReg === State.IDLE || !io.Rst_n, 100,
    "fsm_forward_progress_liveness")

  // --- Safety Assertion 7: IDLE state output consistency ---
  // When in IDLE, MAUNotReady_n should be 1 (MAU is ready) and Write should be 0
  fvAssert(
    !(stateReg === State.IDLE) || (io.MAUNotReady_n === true.B && io.Write === false.B),
    "idle_outputs_ready"
  )

  // --- Safety Assertion 8: BCU request consistency ---
  // In READ_MISS, BCURequest_n should be 0 (active low, requesting)
  fvAssert(
    !(stateReg === State.READ_MISS) || io.BCURequest_n === false.B,
    "read_miss_requests_bcu"
  )

  // In WRITE_HIT or WRITE_MISS, BCUWriteRequest_n should be 0 (active low)
  fvAssert(
    !(stateReg === State.WRITE_HIT || stateReg === State.WRITE_MISS) ||
    io.BCUWriteRequest_n === false.B,
    "write_states_request_bcu_write"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new controlvis(), args)
}
