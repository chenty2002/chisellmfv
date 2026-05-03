package llmverify

import chisel3._
import chisel3.util._

class unidec extends Module {
  val io = IO(new Bundle {
    val sel1 = Input(UInt(3.W))
    val sel2 = Input(UInt(2.W))
    val found = Output(Bool())
  })
  
  // Function to return a code word
  def code(sel: UInt): UInt = {
    MuxCase("b0000000000001000".U, Array(
      (sel === 0.U) -> "b0000000000001000".U, // a
      (sel === 1.U) -> "b0000000000001010".U, // c
      (sel === 2.U) -> "b0000000001011000".U, // ad
      (sel === 3.U) -> "b0000001001001000".U, // abb
      (sel === 4.U) -> "b0000001011000001".U, // bad
      (sel === 5.U) -> "b0000001001100011".U, // deb
      (sel === 6.U) -> "b1100011010001001".U, // bbcde
      (sel === 7.U) -> "b0000000000001000".U  // a
    ))
  }
  
  // Function to extract a proper prefix of length sel+1 from word
  def prefix(word: UInt, sel: UInt): UInt = {
    MuxCase("b0111111111111111".U, Array(
      (sel === 0.U) -> Mux(word(15, 4) === 0.U, 
                          "b0111111111111111".U,
                          Cat("b1111111111111".U, word(2, 0))),
      (sel === 1.U) -> Mux(word(15, 7) === 0.U,
                          "b0111111111111111".U,
                          Cat("b1111111111".U, word(5, 0))),
      (sel === 2.U) -> Mux(word(15, 10) === 0.U,
                          "b0111111111111111".U,
                          Cat("b1111111".U, word(8, 0))),
      (sel === 3.U) -> Mux(word(15, 13) === 0.U,
                          "b0111111111111111".U,
                          Cat("b1111".U, word(11, 0)))
    ))
  }
  
  // Function to return suffix dropping first sel+1 characters
  def suffix(word: UInt, sel: UInt): UInt = {
    MuxCase(0.U(16.W), Array(
      (sel === 0.U) -> Cat(0.U(3.W), word(15, 3)),
      (sel === 1.U) -> Cat(0.U(6.W), word(15, 6)),
      (sel === 2.U) -> Cat(0.U(9.W), word(15, 9)),
      (sel === 3.U) -> Cat(0.U(12.W), word(15, 12))
    ))
  }
  
  // Registers
  val word = RegInit(0.U(16.W))
  val init = RegInit(true.B)
  
  // Initialize word on reset
  when(init) {
    word := code(io.sel1)
  }
  
  // Combinational logic
  val other = code(io.sel1)
  
  // Sequential logic
  io.found := !init && (word === other)
  
  // Update logic
  val nextWord = Wire(UInt(16.W))
  
  when(other === prefix(word, io.sel2)) {
    // There is a code word that is a prefix of the current word.
    // Make the suffix of the current word the next word.
    nextWord := suffix(word, io.sel2)
  }.elsewhen(prefix(other, io.sel2) === word) {
    // The current word is a prefix of another code word.
    // Make the suffix of the other word the next word.
    nextWord := suffix(other, io.sel2)
  }.otherwise {
    // Neither applies. Go to trap state.
    nextWord := 0.U
  }
  
  // Register updates
  word := nextWord
  init := false.B
}

object VerilogGenerator extends App {
  emitVerilog(new unidec(), args)
}