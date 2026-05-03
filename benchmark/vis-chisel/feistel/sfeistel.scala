package llmverify

import chisel3._
import chisel3.util._

class SimpleFeistel extends Module {
  val io = IO(new Bundle {
    val Din = Input(UInt(64.W))
    val Dout = Output(UInt(64.W))
    val Encrypt = Input(Bool())
    val Decrypt = Input(Bool())
    val Loadkey = Input(Bool())
    val Reset_n = Input(Bool())
  })

  // State definitions - fix enum syntax
  val sIDLE :: sBUSY_KEY :: sBUSY_ENC :: sBUSY_DEC :: Nil = Enum(4)
  
  // Internal state registers
  val state = RegInit(sIDLE)
  val phase = RegInit(0.U(1.W))
  val round = RegInit(0.U(2.W))
  
  // Working registers
  val left = RegInit(0.U(32.W))
  val right = RegInit(0.U(32.W))
  val temp = RegInit(0.U(32.W))
  
  // Output register
  val dout = RegInit(0.U(64.W))
  
  // Key storage
  val key = RegInit(VecInit(Seq.fill(4)(0.U(32.W))))
  
  // F function
  val fval = key(round) + left
  
  // Connect output
  io.Dout := dout
  
  // State machine
  when (!io.Reset_n) {
    // Reset logic
    dout := 0.U
    state := sIDLE
    phase := 0.U
    round := 0.U
    temp := 0.U
    left := 0.U
    right := 0.U
    for (i <- 0 until 4) {
      key(i) := 0.U
    }
  }.otherwise {
    switch (state) {
      is (sIDLE) {
        switch (Cat(io.Encrypt, io.Decrypt, io.Loadkey)) {
          is ("b100".U) { // Start encryption
            state := sBUSY_ENC
            left := io.Din(63, 32)
            right := io.Din(31, 0)
            round := 0.U
            phase := 0.U
          }
          is ("b010".U) { // Start decryption
            state := sBUSY_DEC
            left := io.Din(63, 32)
            right := io.Din(31, 0)
            round := 3.U
            phase := 0.U
          }
          is ("b001".U) { // Start loading key
            state := sBUSY_KEY
            key(0) := io.Din(63, 32)
            key(1) := io.Din(31, 0)
          }
        }
      }
      is (sBUSY_KEY) {
        state := sIDLE
        key(2) := io.Din(63, 32)
        key(3) := io.Din(31, 0)
      }
      is (sBUSY_ENC) {
        switch (phase) {
          is (0.U) {
            temp := left
            left := fval ^ right
          }
          is (1.U) {
            when (round === 3.U) {
              dout := Cat(temp, left)
              state := sIDLE
            }.otherwise {
              round := round + 1.U
              right := temp
            }
          }
        }
        phase := ~phase
      }
      is (sBUSY_DEC) {
        switch (phase) {
          is (0.U) {
            temp := left
            left := fval ^ right
          }
          is (1.U) {
            when (round === 0.U) {
              dout := Cat(temp, left)
              state := sIDLE
            }.otherwise {
              round := round - 1.U
              right := temp
            }
          }
        }
        phase := ~phase
      }
    }
  }
}

object VerilogGenerator extends App {
  emitVerilog(new SimpleFeistel(), args)
}