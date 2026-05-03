package llmverify

import chisel3._
import chisel3.util._

// Enum for the state machine states
object B07State extends ChiselEnum {
  val S_RESET, S_START, S_LOAD_X, S_UPDATE_MAR, S_LOAD_Y, S_CALC_RETTA, S_INCREMENTA = Value
}

class b07 extends Module {
  val io = IO(new Bundle {
    val PUNTI_RETTA = Output(UInt(8.W))
    val START = Input(Bool())
  })

  // State register
  val stato = RegInit(B07State.S_RESET)
  
  // Internal registers
  val cont = RegInit(0.U(8.W))
  val x = RegInit(0.U(8.W))
  val y = RegInit(0.U(7.W))
  val t = RegInit(0.U(7.W))
  val mar = RegInit(0.U(4.W))
  
  // Memory function implemented as combinational logic
  def mem(addr: UInt): UInt = {
    val result = Wire(UInt(8.W))
    result := 2.U // default value
    
    switch(addr) {
      is(0.U) { result := 1.U }
      is(1.U) { result := 255.U }
      is(2.U) { result := 0.U }
      is(3.U) { result := 0.U }
      is(4.U) { result := 0.U }
      is(5.U) { result := 2.U }
      is(6.U) { result := 0.U }
      is(7.U) { result := 0.U }
      is(8.U) { result := 0.U }
      is(9.U) { result := 2.U }
      is(10.U) { result := 255.U }
      is(11.U) { result := 5.U }
      is(12.U) { result := 0.U }
      is(13.U) { result := 2.U }
      is(14.U) { result := 0.U }
      is(15.U) { result := 2.U }
    }
    result
  }
  
  // Memory read
  val mem_mar = mem(mar)
  
  // Output register
  val punti_retta_reg = RegInit(0.U(8.W))
  io.PUNTI_RETTA := punti_retta_reg
  
  // State machine
  switch(stato) {
    is(B07State.S_RESET) {
      stato := B07State.S_START
    }
    
    is(B07State.S_START) {
      when(io.START) {
        cont := 0.U
        mar := 0.U
        stato := B07State.S_LOAD_X
      }.otherwise {
        stato := B07State.S_START
        punti_retta_reg := 0.U
      }
    }
    
    is(B07State.S_LOAD_X) {
      x := mem_mar
      stato := B07State.S_UPDATE_MAR
    }
    
    is(B07State.S_UPDATE_MAR) {
      mar := mar + 1.U
      t := Cat(x(5, 0), 0.U(1.W))
      stato := B07State.S_LOAD_Y
    }
    
    is(B07State.S_LOAD_Y) {
      y := mem_mar(6, 0)
      x := Cat(0.U(1.W), x(6, 0)) + Cat(0.U(1.W), t)
      stato := B07State.S_CALC_RETTA
    }
    
    is(B07State.S_CALC_RETTA) {
      x := Cat(0.U(1.W), x(6, 0)) + Cat(0.U(1.W), y)
      stato := B07State.S_INCREMENTA
    }
    
    is(B07State.S_INCREMENTA) {
      when(mar =/= 15.U) {
        when(x === 2.U) {
          cont := cont + 1.U
        }
        mar := mar + 1.U
        stato := B07State.S_LOAD_X
      }.otherwise {
        when(!io.START) {
          when(x === 2.U) {
            punti_retta_reg := cont + 1.U
          }.otherwise {
            punti_retta_reg := cont
          }
          stato := B07State.S_START
        }.otherwise {
          stato := B07State.S_INCREMENTA
        }
      }
    }
  }
}

object VerilogGenerator extends App {
  emitVerilog(new b07(), args)
}