package llmverify

import chisel3._
import chisel3.util._

// State enumeration for the b08 module
object B08State {
  val start_st :: init :: loop_st :: the_end :: Nil = Enum(4)
}

class b08 extends Module {
  val io = IO(new Bundle {
    val START = Input(Bool())
    val I = Input(UInt(8.W))
    val O = Output(UInt(4.W))
    // Additional outputs to preserve internal signals
    val STATO = Output(UInt(2.W))
    val IN_R = Output(UInt(8.W))
    val MAR = Output(UInt(3.W))
    val OUT_R = Output(UInt(4.W))
    val ROM_1 = Output(UInt(8.W))
    val ROM_2 = Output(UInt(8.W))
    val ROM_OR = Output(UInt(4.W))
  })

  // State register
  val statoReg = RegInit(B08State.start_st)
  
  // Internal registers with initial values
  val inRReg = RegInit(0.U(8.W))
  val marReg = RegInit(0.U(3.W))
  val outRReg = RegInit(0.U(4.W))
  val oReg = RegInit(0.U(4.W))
  
  // ROM implementation as a lookup table
  def rom(addr: UInt): UInt = {
    val romData = VecInit(
      "b01111111100101111010".U, // address 0
      "b00111001110101100010".U, // address 1
      "b10101000111111111111".U, // address 2
      "b11111111011010111010".U, // address 3
      "b11111111111101101110".U, // address 4
      "b11111111101110101000".U, // address 5
      "b11001010011101011011".U, // address 6
      "b00101111111111110100".U  // address 7
    )
    romData(addr)
  }
  
  // ROM outputs
  val romOut = rom(marReg)
  val rom1 = romOut(19, 12) // ROM_1 = ROM_OUT[19:12]
  val rom2 = romOut(11, 4)  // ROM_2 = ROM_OUT[11:4]
  val romOr = romOut(3, 0)  // ROM_OR = ROM_OUT[3:0]
  
  // State machine logic
  switch(statoReg) {
    is(B08State.start_st) {
      when(io.START) {
        statoReg := B08State.init
      }
    }
    is(B08State.init) {
      inRReg := io.I
      outRReg := 0.U
      marReg := 0.U
      statoReg := B08State.loop_st
    }
    is(B08State.loop_st) {
      val condition = ((rom2 & ~inRReg) | (rom1 & inRReg) | (rom2 & rom1)) === "b11111111".U
      when(condition) {
        outRReg := outRReg | romOr
      }
      statoReg := B08State.the_end
    }
    is(B08State.the_end) {
      when(marReg =/= 7.U) {
        marReg := marReg + 1.U
        statoReg := B08State.loop_st
      }.elsewhen(!io.START) {
        oReg := outRReg
        statoReg := B08State.start_st
      }
    }
  }
  
  // Connect outputs
  io.O := oReg
  io.STATO := statoReg
  io.IN_R := inRReg
  io.MAR := marReg
  io.OUT_R := outRReg
  io.ROM_1 := rom1
  io.ROM_2 := rom2
  io.ROM_OR := romOr
}

object VerilogGenerator extends App {
  emitVerilog(new b08(), args)
}