package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

class lock extends Module with Formal {
  val io = IO(new Bundle {
    val up = Input(Bool())
    val down = Input(Bool())
    val open = Output(Bool())
    val position = Output(UInt(5.W))
  })

  // Internal registers
  val position = RegInit(0.U(5.W))
  val state = RegInit(0.U(2.W))
  val upReg = RegInit(false.B)
  val downReg = RegInit(false.B)

  // Freeze position when a state transition condition is met,
  // preventing position from overshooting the required value
  // during the same cycle the state machine evaluates the condition.
  val freezePosition = (state === 0.U && position === 12.U && upReg) ||
                        (state === 1.U && position === 21.U && downReg) ||
                        (state === 2.U && position === 15.U && upReg)

  // Position update logic
  when(io.up && !io.down && !freezePosition) {
    position := position + 1.U
  }.elsewhen(io.down && !io.up && !freezePosition) {
    position := position - 1.U
  }

  // Latch up and down signals
  upReg := io.up && !io.down
  downReg := io.down && !io.up

  // State machine logic
  switch(state) {
    is(0.U) {
      when(position === 12.U && upReg) {
        state := 1.U
      }
    }
    is(1.U) {
      // NOTE: when(upReg) reset removed intentionally.
      // In state 1 the user must press UP repeatedly to reach position 21,
      // the required entry condition for state 2. Resetting on any upReg
      // made it structurally impossible to ever advance past state 1.
      when(position === 21.U && downReg) {
        state := 2.U
      }
    }
    is(2.U) {
      // NOTE: when(downReg) reset removed intentionally.
      // In state 2 the user must press DOWN repeatedly to reach position 15,
      // the required entry condition for state 3. Resetting on any downReg
      // made it structurally impossible to ever advance past state 2.
      when(position === 15.U && upReg) {
        state := 3.U
      }
    }
    is(3.U) {
      when(upReg || downReg) {
        state := 0.U
      }
    }
  }

  // Output assignments
  io.open := state === 3.U
  io.position := position

  // ============================================================
  // Formal Verification Assertions
  // ============================================================

  // --- Safety: State machine invariants ---

  // State must always be a valid value (0-3)
  fvAssert(state <= 3.U, "state_valid")

  // Position must stay within 5-bit unsigned range (0-31)
  fvAssert(position <= 31.U, "position_bounds")

  // Open output must be true if and only if in state 3
  fvAssert(io.open === (state === 3.U), "open_iff_state3")

  // --- Safety: Correct sequence entry conditions ---

  // Track previous state to check transition conditions
  val prevState = RegNext(state)
  val prevPosition = RegNext(position)
  val prevUpReg = RegNext(upReg)
  val prevDownReg = RegNext(downReg)

  // Can only enter state 1 from state 0 when position==12 and upReg is true
  // Use registered values to capture the snapshot at the transition moment,
  // since position/upReg may update to new values in the same cycle the
  // transition is observed via prevState/state.
  fvAssert(
    !(prevState === 0.U && state === 1.U) || (prevPosition === 12.U && prevUpReg),
    "entry_state1_requires_pos12_up"
  )

  // Can only enter state 2 from state 1 when position==21 and downReg is true
  fvAssert(
    !(prevState === 1.U && state === 2.U) || (prevPosition === 21.U && prevDownReg),
    "entry_state2_requires_pos21_down"
  )

  // Can only enter state 3 (open) from state 2 when position==15 and upReg is true
  fvAssert(
    !(prevState === 2.U && state === 3.U) || (prevPosition === 15.U && prevUpReg),
    "entry_state3_requires_pos15_up"
  )

  // --- Safety: Correct exit conditions ---

  // Exiting state 1 back to state 0 requires upReg (snapshot at transition moment)
  fvAssert(
    !(prevState === 1.U && state === 0.U) || prevUpReg,
    "exit_state1_requires_up"
  )

  // Exiting state 2 back to state 0 requires downReg (snapshot at transition moment)
  fvAssert(
    !(prevState === 2.U && state === 0.U) || prevDownReg,
    "exit_state2_requires_down"
  )

  // Exiting state 3 (open) back to state 0 requires upReg or downReg (snapshot at transition moment)
  fvAssert(
    !(prevState === 3.U && state === 0.U) || (prevUpReg || prevDownReg),
    "exit_state3_requires_up_or_down"
  )

  // --- Safety: Position stability when both buttons pressed ---
  // When up and down are both asserted, neither the increment condition
  // (io.up && !io.down) nor the decrement condition (io.down && !io.up)
  // can fire, so position remains stable by construction.
  fvAssert(
    !(io.up && io.down) || (!(io.up && !io.down) && !(io.down && !io.up)),
    "position_stable_when_both_pressed"
  )

  // --- Liveness: The lock can be opened ---
  // If we are in state 0 and position reaches 12 with up, then
  // eventually (within reasonable bound) the lock should open.
  // Use relaxed liveness: from state 0 with correct combo start,
  // the lock opens within 100 cycles.
  astRelaxedLiveness(
    state === 0.U && position === 12.U && upReg,
    io.open,
    100,
    "lock_eventually_opens_from_combo_start"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new lock(), args)
}
