package llmverify

import chisel3._
import chisel3.util._

// Enum for the state machine states
object B11State extends ChiselEnum {
  val s_reset   = Value
  val s_datain  = Value
  val s_spazio  = Value
  val s_mul     = Value
  val s_somma   = Value
  val s_rsum    = Value
  val s_rsot    = Value
  val s_compl   = Value
  val s_dataout = Value
}

class b11 extends Module {
  val io = IO(new Bundle {
    val x_in  = Input(UInt(6.W))
    val stbi  = Input(Bool())
    val x_out = Output(UInt(6.W))
  })

  // State register
  val stato = RegInit(B11State.s_reset)
  
  // Internal registers
  val r_in  = RegInit(0.U(6.W))
  val cont  = RegInit(0.U(6.W))
  val cont1 = RegInit(0.U(9.W))
  
  // Output register
  val x_out = RegInit(0.U(6.W))
  
  // Connect output
  io.x_out := x_out

  // State machine logic
  switch(stato) {
    is(B11State.s_reset) {
      cont := 0.U
      r_in := io.x_in
      x_out := 0.U
      stato := B11State.s_datain
    }
    
    is(B11State.s_datain) {
      r_in := io.x_in
      when(io.stbi) {
        stato := B11State.s_datain
      }.otherwise {
        stato := B11State.s_spazio
      }
    }
    
    is(B11State.s_spazio) {
      when(r_in === 0.U || r_in === 63.U) {
        when(cont < 25.U) {
          cont := cont + 1.U
        }.otherwise {
          cont := 0.U
        }
        cont1 := Cat(0.U(3.W), r_in)
        stato := B11State.s_dataout
      }.elsewhen(r_in <= 26.U) {
        stato := B11State.s_mul
      }.otherwise {
        stato := B11State.s_datain
      }
    }
    
    is(B11State.s_mul) {
      when(r_in(0)) {
        // mult by 2 and extend
        cont1 := Cat(0.U(2.W), cont, 0.U(1.W))
      }.otherwise {
        cont1 := Cat(0.U(3.W), cont)
      }
      stato := B11State.s_somma
    }
    
    is(B11State.s_somma) {
      when(r_in(1)) {
        cont1 := Cat(0.U(3.W), r_in) + cont1
        stato := B11State.s_rsum
      }.otherwise {
        cont1 := Cat(0.U(3.W), r_in) - cont1
        stato := B11State.s_rsot
      }
    }
    
    is(B11State.s_rsum) {
      when(!cont1(8) && cont1 > 26.U) {
        cont1 := cont1 - 26.U
        stato := B11State.s_rsum
      }.otherwise {
        stato := B11State.s_compl
      }
    }
    
    is(B11State.s_rsot) {
      when(!cont1(8) && cont1 > 63.U) {
        cont1 := cont1 + 26.U
        stato := B11State.s_rsot
      }.otherwise {
        stato := B11State.s_compl
      }
    }
    
    is(B11State.s_compl) {
      when(r_in(3,2) === 0.U) {
        cont1 := cont1 - 21.U
      }.elsewhen(r_in(3,2) === 1.U) {
        cont1 := cont1 - 42.U
      }.elsewhen(r_in(3,2) === 2.U) {
        cont1 := cont1 + 7.U
      }.otherwise {
        cont1 := cont1 + 28.U
      }
      stato := B11State.s_dataout
    }
    
    is(B11State.s_dataout) {
      when(cont1(8)) {
        // Two's complement for negative numbers
        x_out := (~cont1(5,0) + 1.U)
      }.otherwise {
        x_out := cont1(5,0)
      }
      stato := B11State.s_datain
    }
  }
}

object VerilogGenerator extends App {
  emitVerilog(new b11(), args)
}