package llmverify

import chisel3._
import chisel3.util._

// Model of connected Huffman encoder and decoder.
// The alphabet consists of the uppercase letters and the space.
class main extends Module {
  val io = IO(new Bundle {
    val addr = Input(UInt(5.W))
    val cipher_out = Output(Bool())
    val character_out = Output(UInt(8.W))
    val plain_out = Output(UInt(8.W))
  })
  
  // Instantiate encoder and decoder
  val encoder = Module(new huffmanEnc())
  val decoder = Module(new huffmanDec())
  
  // Connect encoder
  encoder.io.addr := io.addr
  
  // Connect decoder
  decoder.io.cipher := encoder.io.cipher
  
  // Latch data that we want to refer to in properties
  val ci = RegNext(encoder.io.cipher, false.B)
  val ch = RegNext(encoder.io.character, 0.U)
  
  // Output signals
  io.cipher_out := ci
  io.character_out := ch
  io.plain_out := decoder.io.plain
}

class huffmanEnc extends Module {
  val io = IO(new Bundle {
    val addr = Input(UInt(5.W))
    val cipher = Output(Bool())
    val character = Output(UInt(8.W))
  })
  
  // Function to map ASCII to Huffman codes
  def code(c: UInt): UInt = {
    val result = Wire(UInt(10.W))
    result := 0.U // default value
    switch(c) {
      is(69.U) { result := "b0000001010".U } // E
      is(32.U) { result := "b0000001011".U } // space
      is(83.U) { result := "b0000010100".U } // S
      is(65.U) { result := "b0000011110".U } // A
      is(73.U) { result := "b0000010001".U } // I
      is(79.U) { result := "b0000011001".U } // O
      is(82.U) { result := "b0000010101".U } // R
      is(78.U) { result := "b0000011101".U } // N
      is(84.U) { result := "b0000011111".U } // T
      is(85.U) { result := "b0000100000".U } // U
      is(80.U) { result := "b0000110000".U } // P
      is(70.U) { result := "b0000101000".U } // F
      is(67.U) { result := "b0000111000".U } // C
      is(76.U) { result := "b0000111100".U } // L
      is(72.U) { result := "b0000100110".U } // H
      is(68.U) { result := "b0000100111".U } // D
      is(87.U) { result := "b0001101100".U } // W
      is(71.U) { result := "b0001010110".U } // G
      is(89.U) { result := "b0001110110".U } // Y
      is(77.U) { result := "b0001110111".U } // M
      is(66.U) { result := "b0010010111".U } // B
      is(86.U) { result := "b0011010111".U } // V
      is(81.U) { result := "b0100001100".U } // Q
      is(75.U) { result := "b0101001100".U } // K
      is(88.U) { result := "b0111001100".U } // X
      is(90.U) { result := "b1010001100".U } // Z
      is(74.U) { result := "b1110001100".U } // J
    }
    result
  }
  
  // Function to map address to ASCII characters
  def ROM(address: UInt): UInt = {
    Mux(address < 26.U, 65.U + address, 32.U)
  }
  
  val character = RegInit(ROM(io.addr))
  val shiftreg = RegInit(code(character))
  
  when(shiftreg(9, 1) === 1.U) {
    character := ROM(io.addr)
    shiftreg := code(character) // load a new code
  }.otherwise {
    shiftreg := Cat(0.U(1.W), shiftreg(9, 1)) // shift right
  }
  
  io.character := character
  io.cipher := shiftreg(0)
}

class huffmanDec extends Module {
  val io = IO(new Bundle {
    val cipher = Input(Bool())
    val plain = Output(UInt(8.W))
  })
  
  // Function to map states to characters
  def map(state: UInt): UInt = {
    val result = Wire(UInt(8.W))
    result := 0.U // default value
    switch(state) {
      is(9.U) { result := 69.U }   // E
      is(13.U) { result := 32.U }  // space
      is(17.U) { result := 83.U }  // S
      is(22.U) { result := 65.U }  // A
      is(23.U) { result := 73.U }  // I
      is(24.U) { result := 79.U }  // O
      is(25.U) { result := 82.U }  // R
      is(26.U) { result := 78.U }  // N
      is(30.U) { result := 84.U }  // T
      is(31.U) { result := 85.U }  // U
      is(32.U) { result := 80.U }  // P
      is(33.U) { result := 70.U }  // F
      is(34.U) { result := 67.U }  // C
      is(38.U) { result := 76.U }  // L
      is(43.U) { result := 72.U }  // H
      is(59.U) { result := 68.U }  // D
      is(76.U) { result := 87.U }  // W
      is(89.U) { result := 71.U }  // G
      is(90.U) { result := 89.U }  // Y
      is(122.U) { result := 77.U } // M
      is(243.U) { result := 66.U } // B
      is(244.U) { result := 86.U } // V
      is(303.U) { result := 81.U } // Q
      is(305.U) { result := 75.U } // K
      is(306.U) { result := 88.U } // X
      is(609.U) { result := 90.U } // Z
      is(610.U) { result := 74.U } // J
    }
    result
  }
  
  val state = RegInit(0.U(10.W))
  val character = map(state)
  val leaf = character =/= 0.U
  
  when(leaf) {
    state := 0.U
  }.otherwise {
    state := Cat(state(8, 0), 0.U(1.W)) + Mux(io.cipher, 2.U, 1.U)
  }
  
  io.plain := character
}

object VerilogGenerator extends App {
  emitVerilog(new main(), args)
}