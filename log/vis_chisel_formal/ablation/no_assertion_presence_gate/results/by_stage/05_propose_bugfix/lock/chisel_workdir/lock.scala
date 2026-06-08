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

  // ===== Formal Verification Assertions =====

  // Safety: Position is unchanged when neither direction is uniquely pressed
  // (both up/down true or both false). This guards against unintended drift.
  // Use RegNext to snapshot the previous cycle's net-movement signal so that
  // the assertion accounts for the one-cycle register delay between input
  // sampling and position update.
  val prev_net_movement = RegNext(io.up ^ io.down, false.B)
  val prev_position = RegNext(position, 0.U)
  assert(prev_net_movement || prev_position === position, "position_stable_when_no_net_movement")

  // Safety: When up is pressed exclusively, position increments by 1 (mod 32)
  // one cycle later. Catches corruption in the increment path.
  val prev_up = RegNext(io.up && !io.down, false.B)
  assert(!prev_up || position === (prev_position + 1.U), "up_increments_position_by_1")

  // Safety: When down is pressed exclusively, position decrements by 1 (mod 32)
  // one cycle later. Catches corruption in the decrement path.
  val prev_down = RegNext(io.down && !io.up, false.B)
  assert(!prev_down || position === (prev_position - 1.U), "down_decrements_position_by_1")

  // Liveness: From state 0, when both the position target and upReg are set,
  // the FSM enters state 1 within a few cycles. Detects stuck state 0.
  astRelaxedLiveness(state === 0.U && upReg && position === 12.U,
    state === 1.U, 5,
    "progress_state0_to_state1")

  // Liveness: From state 1, when both the position target and downReg are set,
  // the FSM enters state 2 within a few cycles. Detects stuck state 1.
  astRelaxedLiveness(state === 1.U && downReg && position === 21.U,
    state === 2.U, 5,
    "progress_state1_to_state2")

  // Liveness: From state 2, when both the position target and upReg are set,
  // the FSM enters state 3 (open) within a few cycles. Detects stuck state 2.
  astRelaxedLiveness(state === 2.U && upReg && position === 15.U,
    state === 3.U, 5,
    "progress_state2_to_state3")
}

object VerilogGenerator extends App {
  emitVerilog(new lock(), args)
}
