package llmverify

import chisel3._
import chisel3.util._

// State enumeration for the b09 state machine
object B09State extends ChiselEnum {
  val INIT, RECEIVE, EXECUTE, LOAD_OLD = Value
}

class b09 extends Module {
  val io = IO(new Bundle {
    val X = Input(Bool())
    val Y = Output(Bool())
    // Additional outputs to preserve internal signals for verification
    val stato = Output(UInt(2.W))
    val d_in = Output(UInt(9.W))
    val d_out = Output(UInt(8.W))
    val old = Output(UInt(8.W))
  })
  
  // Parameters
  val Bit_start = 1.U(1.W)
  val Bit_stop = 0.U(1.W)
  val Bit_idle = 0.U(1.W)
  
  // State register
  val stato = RegInit(B09State.INIT)
  
  // Data registers
  val d_in = RegInit(0.U(9.W))
  val d_out = RegInit(0.U(8.W))
  val old = RegInit(0.U(8.W))
  
  // Output register
  val Y = RegInit(Bit_idle)
  
  // State machine logic
  switch(stato) {
    is(B09State.INIT) {
      stato := B09State.RECEIVE
      d_in := 0.U
      d_out := 0.U
      old := 0.U
      Y := Bit_idle
    }
    
    is(B09State.RECEIVE) {
      when(d_in(0) === Bit_start) {
        old := d_in(8, 1)
        Y := Bit_start
        d_out := d_in(8, 1)
        d_in := Cat(Bit_start, 0.U(8.W))
        stato := B09State.EXECUTE
      }.otherwise {
        d_in := Cat(io.X, d_in(8, 1))
        stato := B09State.RECEIVE
      }
    }
    
    is(B09State.EXECUTE) {
      when(d_in(0) === Bit_start) {
        Y := Bit_stop
        stato := B09State.LOAD_OLD
      }.otherwise {
        Y := d_out(0)
        d_out := Cat(Bit_idle, d_out(7, 1))
        stato := B09State.EXECUTE
      }
      d_in := Cat(io.X, d_in(8, 1))
    }
    
    is(B09State.LOAD_OLD) {
      when(d_in(0) === Bit_start) {
        when(d_in(8, 1) === old) {
          old := d_in(8, 1)
          d_in := 0.U
          Y := Bit_idle
          stato := B09State.LOAD_OLD
        }.otherwise {
          old := d_in(8, 1)
          Y := Bit_start
          d_out := d_in(8, 1)
          d_in := Cat(Bit_start, 0.U(8.W))
          stato := B09State.EXECUTE
        }
      }.otherwise {
        d_in := Cat(io.X, d_in(8, 1))
        Y := Bit_idle
        stato := B09State.LOAD_OLD
      }
    }
  }
  
  // Connect outputs
  io.Y := Y
  io.stato := stato.asUInt  // Convert enum to UInt
  io.d_in := d_in
  io.d_out := d_out
  io.old := old
}

object VerilogGenerator extends App {
  emitVerilog(new b09(), args)
}