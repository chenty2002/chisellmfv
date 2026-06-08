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

  // Position update logic
  when(io.up && !io.down) {
    position := position + 1.U
  }.elsewhen(io.down && !io.up) {
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
      when(upReg) {
        state := 0.U
      }.elsewhen(position === 21.U && downReg) {
        state := 2.U
      }
    }
    is(2.U) {
      when(downReg) {
        state := 0.U
      }.elsewhen(position === 15.U && upReg) {
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

  // ========== Formal Verification Assertions ==========

  // Safety: up and down should not be asserted simultaneously
  assertMutex(Seq(io.up, io.down), "up_down_mutex")

  // Safety: position must not overflow when moving up (would wrap 31 -> 0)
  fvAssert(!(io.up && !io.down) || position < 31.U, "no_position_overflow")

  // Safety: position must not underflow when moving down (would wrap 0 -> 31)
  fvAssert(!(io.down && !io.up) || position > 0.U, "no_position_underflow")

  // Safety: state must always be a valid value (0-3)
  fvAssert(state <= 3.U, "valid_state")

  // Bounded liveness: when state-0 transition conditions are met,
  // state should become 1 within 2 cycles
  astRelaxedLiveness(
    state === 0.U && position === 12.U && upReg,
    state === 1.U,
    2,
    "progress_state_0_to_1"
  )

  // Bounded liveness: when state-1 transition conditions are met,
  // state should become 2 within 2 cycles
  astRelaxedLiveness(
    state === 1.U && position === 21.U && downReg,
    state === 2.U,
    2,
    "progress_state_1_to_2"
  )

  // Bounded liveness: when state-2 transition conditions are met,
  // state should become 3 (open) within 2 cycles
  astRelaxedLiveness(
    state === 2.U && position === 15.U && upReg,
    state === 3.U,
    2,
    "progress_state_2_to_3"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new lock(), args)
}
