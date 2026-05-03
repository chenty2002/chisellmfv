package llmverify

import chisel3._
import chisel3.util._

class unidec extends Module {
  val io = IO(new Bundle {
    val sel1 = Input(UInt(3.W))
    val sel2 = Input(UInt(2.W))
    val found = Output(Bool())
  })
  
  // Internal registers and wires
  val word = RegInit(0.U(13.W))
  val found_reg = RegInit(false.B)
  val init = RegInit(true.B)
  
  // Function to return a code word based on selection
  def code(sel: UInt): UInt = {
    MuxLookup(sel, "b0000000000000".U(13.W))(
      Seq(
        0.U -> "b0001010001000".U(13.W), // abc
        1.U -> "b1011010001000".U(13.W), // abcd
        2.U -> "b0000000001100".U(13.W), // e
        3.U -> "b0001000001011".U(13.W), // dba
        4.U -> "b1100010000001".U(13.W), // bace
        5.U -> "b1010000100010".U(13.W), // ceac
        6.U -> "b1001000100010".U(13.W), // ceab
        7.U -> "b1011001000100".U(13.W)  // eabd
      )
    )
  }
  
  // Function to extract a proper prefix of length sel from word
  def prefix(word: UInt, sel: UInt): UInt = {
    MuxLookup(sel, "b0111111111111".U(13.W))(
      Seq(
        0.U -> "b0111111111111".U(13.W),
        1.U -> Mux(word(12,4) === 0.U, "b0111111111111".U(13.W), Cat("b1111111111".U(10.W), word(2,0))),
        2.U -> Mux(word(12,7) === 0.U, "b0111111111111".U(13.W), Cat("b1111111".U(7.W), word(5,0))),
        3.U -> Mux(word(12,10) === 0.U, "b0111111111111".U(13.W), Cat("b1111".U(4.W), word(8,0)))
      )
    )
  }
  
  // Function to return suffix dropping first sel characters
  def suffix(word: UInt, sel: UInt): UInt = {
    MuxLookup(sel, 0.U(13.W))(
      Seq(
        0.U -> 0.U(13.W),
        1.U -> Cat(0.U(3.W), word(12,3)),
        2.U -> Cat(0.U(6.W), word(12,6)),
        3.U -> Cat(0.U(9.W), word(12,9))
      )
    )
  }
  
  // Combinational assignment for other
  val other = code(io.sel1)
  
  // Handle initialization (equivalent to initial block)
  when(reset.asBool) {
    word := code(io.sel1)
    found_reg := false.B
    init := true.B
  }.otherwise {
    // Sequential logic on clock edge
    found_reg := !init && (word === other)
    init := false.B
    
    // Update word based on conditions
    when(other === prefix(word, io.sel2)) {
      // There is a code word that is a prefix of the current word.
      // Make the suffix of the current word the next word.
      word := suffix(word, io.sel2)
    }.elsewhen(prefix(other, io.sel2) === word) {
      // The current word is a prefix of another code word.
      // Make the suffix of the other word the next word.
      word := suffix(other, io.sel2)
    }.otherwise {
      // Neither applies. Go to trap state.
      word := 0.U
    }
  }
  
  // Output assignment
  io.found := found_reg
}

object VerilogGenerator extends App {
  emitVerilog(new unidec(), args)
}