package llmverify

import chisel3._
import chisel3.util._

class lunc extends Module {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val dataIn = Input(UInt(8.W))
    val dataOut = Output(UInt(8.W))
    // Additional outputs to preserve internal signals
    val regIn = Output(UInt(8.W))
    val transformed = Output(UInt(8.W))
    val Lcmd = Output(Bool())
    val Ucmd = Output(Bool())
    val Ncmd = Output(Bool())
    val Ccmd = Output(Bool())
  })
  
  val regIn = RegInit(0.U(8.W))
  val dataOut = RegInit(0.U(8.W))
  
  // Instantiate control module
  val control_inst = Module(new control())
  control_inst.io.clock := io.clock
  control_inst.io.reset := io.reset
  control_inst.io.in := regIn
  
  // Instantiate transform module
  val transform_inst = Module(new transform())
  transform_inst.io.in := regIn
  transform_inst.io.Lcmd := control_inst.io.Lcmd
  transform_inst.io.Ucmd := control_inst.io.Ucmd
  transform_inst.io.Ncmd := control_inst.io.Ncmd
  transform_inst.io.Ccmd := control_inst.io.Ccmd
  
  // Sequential logic
  when(io.reset) {
    dataOut := 0.U
    regIn := 0.U
  }.otherwise {
    dataOut := transform_inst.io.out
    regIn := io.dataIn
  }
  
  // Connect outputs
  io.dataOut := dataOut
  io.regIn := regIn
  io.transformed := transform_inst.io.out
  io.Lcmd := control_inst.io.Lcmd
  io.Ucmd := control_inst.io.Ucmd
  io.Ncmd := control_inst.io.Ncmd
  io.Ccmd := control_inst.io.Ccmd
}

class control extends Module {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val in = Input(UInt(8.W))
    val Lcmd = Output(Bool())
    val Ucmd = Output(Bool())
    val Ncmd = Output(Bool())
    val Ccmd = Output(Bool())
  })
  
  val Lcmd = RegInit(false.B)
  val Ucmd = RegInit(false.B)
  val Ncmd = RegInit(true.B)
  val Ccmd = RegInit(false.B)
  val prev = RegInit(0.U(8.W))
  
  val load = (prev === "h1b".U) // escape
  
  // Update previous value
  when(io.reset) {
    prev := 0.U
  }.otherwise {
    prev := io.in
  }
  
  // Command decoding logic
  when(io.reset) {
    Ncmd := true.B
    Lcmd := false.B
    Ucmd := false.B
    Ccmd := false.B
  }.elsewhen(load) {
    switch(io.in) {
      is("h4c".U) { // L
        Lcmd := true.B
        Ucmd := false.B
        Ncmd := false.B
        Ccmd := false.B
      }
      is("h55".U) { // U
        Lcmd := false.B
        Ucmd := true.B
        Ncmd := false.B
        Ccmd := false.B
      }
      is("h4e".U) { // N
        Lcmd := false.B
        Ucmd := false.B
        Ncmd := true.B
        Ccmd := false.B
      }
      is("h43".U) { // C
        Lcmd := false.B
        Ucmd := false.B
        Ncmd := false.B
        Ccmd := true.B
      }
    }
  }
  
  // Connect outputs
  io.Lcmd := Lcmd
  io.Ucmd := Ucmd
  io.Ncmd := Ncmd
  io.Ccmd := Ccmd
}

class transform extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(8.W))
    val Lcmd = Input(Bool())
    val Ucmd = Input(Bool())
    val Ncmd = Input(Bool())
    val Ccmd = Input(Bool())
    val out = Output(UInt(8.W))
  })
  
  // Helper function to check if character is uppercase
  def isUpper(in: UInt): Bool = !in(5)
  
  // Helper function to convert to lowercase
  def toLower(in: UInt): UInt = {
    Mux(isUpper(in), in + "h20".U, in)
  }
  
  // Helper function to convert to uppercase
  def toUpper(in: UInt): UInt = {
    Mux(!isUpper(in), in - "h20".U, in)
  }
  
  // Helper function to change case
  def changeCase(in: UInt): UInt = {
    Mux(isUpper(in), in + "h20".U, in - "h20".U)
  }
  
  // Combinational logic for transformation
  // Use 0.U as default instead of "hxx".U which is invalid in Chisel
  io.out := Mux(io.Lcmd, toLower(io.in),
           Mux(io.Ucmd, toUpper(io.in),
           Mux(io.Ncmd, io.in,
           Mux(io.Ccmd, changeCase(io.in), 0.U))))
}

object VerilogGenerator extends App {
  emitVerilog(new lunc(), args)
}