package llmverify

import chisel3._
import chisel3.util._

// Verilog translation of the original b02 circuit from the ITC99
// benchmark set.

// Author: Fabio Somenzi <Fabio@Colorado.EDU>

class b02 extends Module {
  val io = IO(new Bundle {
    val LINEA = Input(Bool())
    val U = Output(Bool())
  })
  
  // State enumeration using UInt
  val StateA = 0.U(3.W)
  val StateB = 1.U(3.W)
  val StateC = 2.U(3.W)
  val StateD = 3.U(3.W)
  val StateE = 4.U(3.W)
  val StateF = 5.U(3.W)
  val StateG = 6.U(3.W)
  
  // State register with initial state A
  val stato = RegInit(StateA)
  
  // Output register U with initial value 0
  val U_reg = RegInit(false.B)
  io.U := U_reg
  
  // State machine logic
  switch(stato) {
    is(StateA) {
      stato := StateB
      U_reg := false.B
    }
    is(StateB) {
      when(io.LINEA === false.B) {
        stato := StateC
      }.otherwise {
        stato := StateF
      }
      U_reg := false.B
    }
    is(StateC) {
      when(io.LINEA === false.B) {
        stato := StateD
      }.otherwise {
        stato := StateG
      }
      U_reg := false.B
    }
    is(StateD) {
      stato := StateE
      U_reg := false.B
    }
    is(StateE) {
      stato := StateB
      U_reg := true.B
    }
    is(StateF) {
      stato := StateG
      U_reg := false.B
    }
    is(StateG) {
      when(io.LINEA === false.B) {
        stato := StateE
      }.otherwise {
        stato := StateA
      }
      U_reg := false.B
    }
  }
}

object VerilogGenerator extends App {
  emitVerilog(new b02(), args)
}