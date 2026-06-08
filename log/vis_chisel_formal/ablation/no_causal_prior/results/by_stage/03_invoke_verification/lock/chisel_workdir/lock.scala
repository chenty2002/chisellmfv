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

  // Safety: upReg and downReg must never both be true simultaneously
  // (they latch mutually exclusive input conditions)
  assertMutex(Seq(upReg, downReg), "up_down_mutex")

  // Safety: open output must exactly match state === 3.U
  fvAssert(io.open === (state === 3.U), "open_eq_state3")

  // Bounded liveness: state machine transitions must complete within 1 cycle
  // State 0 -> State 1: when position reaches 12 with up movement
  astRelaxedLiveness(state === 0.U && position === 12.U && upReg, state === 1.U, 1, "s0_to_s1_within_1")
  // State 1 -> State 2: when position reaches 21 with down movement (and not up)
  astRelaxedLiveness(state === 1.U && position === 21.U && downReg, state === 2.U, 1, "s1_to_s2_within_1")
  // State 2 -> State 3: when position reaches 15 with up movement (and not down)
  astRelaxedLiveness(state === 2.U && position === 15.U && upReg, state === 3.U, 1, "s2_to_s3_within_1")
  // State 3 -> State 0: when any direction is pressed while open
  astRelaxedLiveness(state === 3.U && (upReg || downReg), state === 0.U, 1, "s3_to_s0_within_1")
}

object VerilogGenerator extends App {
  emitVerilog(new lock(), args)
}
