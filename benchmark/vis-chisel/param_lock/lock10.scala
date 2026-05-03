package llmverify

import chisel3._
import chisel3.util._

class lock10 extends Module {
  val io = IO(new Bundle {
    val up = Input(Bool())
    val down = Input(Bool())
    val open = Output(Bool())
    val position = Output(UInt(10.W)) // MSB = 9, so 10 bits total
  })
  
  // Constants
  val MSB = 9
  
  // Registers
  val position = RegInit(0.U(10.W))
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
  
  // State machine
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
  
  // Output assignment
  io.open := state === 3.U
  io.position := position
}

object VerilogGenerator extends App {
  emitVerilog(new lock10(), args)
}