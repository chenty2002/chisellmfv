package llmverify

import chisel3._
import chisel3.util._

class microwave extends Module {
  val io = IO(new Bundle {
    val reset = Input(Bool())
    val openDoor = Input(Bool())
    val closeDoor = Input(Bool())
    val done = Input(Bool())
    // Add outputs to preserve internal state
    val Start = Output(Bool())
    val Close = Output(Bool())
    val Heat = Output(Bool())
    val Error = Output(Bool())
  })
  
  // State registers
  val Start = RegInit(false.B)
  val Close = RegInit(false.B)
  val Heat = RegInit(false.B)
  val Error = RegInit(false.B)
  
  // State machine implementation
  val nextStateError = Wire(Bool())
  val nextStateHeat = Wire(Bool())
  val nextStateClose = Wire(Bool())
  val nextStateStart = Wire(Bool())
  
  // Default assignments
  nextStateError := Error
  nextStateHeat := Heat
  nextStateClose := Close
  nextStateStart := Start
  
  // State machine logic
  switch(Cat(Error, Heat, Close, Start)) {
    is("b0000".U) {
      when(io.closeDoor) {
        nextStateError := false.B
        nextStateHeat := false.B
        nextStateClose := true.B
        nextStateStart := false.B
      }.otherwise {
        nextStateError := true.B
        nextStateHeat := false.B
        nextStateClose := false.B
        nextStateStart := true.B
      }
    }
    is("b1001".U) {
      nextStateError := true.B
      nextStateHeat := false.B
      nextStateClose := true.B
      nextStateStart := true.B
    }
    is("b1011".U) {
      when(io.reset) {
        nextStateError := false.B
        nextStateHeat := false.B
        nextStateClose := true.B
        nextStateStart := false.B
      }.otherwise {
        nextStateError := true.B
        nextStateHeat := false.B
        nextStateClose := false.B
        nextStateStart := true.B
      }
    }
    is("b0010".U) {
      when(io.openDoor) {
        nextStateError := false.B
        nextStateHeat := false.B
        nextStateClose := false.B
        nextStateStart := false.B
      }.otherwise {
        nextStateError := false.B
        nextStateHeat := false.B
        nextStateClose := true.B
        nextStateStart := true.B
      }
    }
    is("b0011".U) {
      nextStateError := false.B
      nextStateHeat := true.B
      nextStateClose := true.B
      nextStateStart := true.B
    }
    is("b0111".U) {
      nextStateError := false.B
      nextStateHeat := true.B
      nextStateClose := true.B
      nextStateStart := false.B
    }
    is("b0110".U) {
      when(io.openDoor) {
        nextStateError := false.B
        nextStateHeat := false.B
        nextStateClose := false.B
        nextStateStart := false.B
      }.elsewhen(io.done) {
        nextStateError := false.B
        nextStateHeat := false.B
        nextStateClose := true.B
        nextStateStart := false.B
      }
    }
  }
  
  // Register updates
  Start := nextStateStart
  Close := nextStateClose
  Heat := nextStateHeat
  Error := nextStateError
  
  // Connect outputs
  io.Start := Start
  io.Close := Close
  io.Heat := Heat
  io.Error := Error
}

object VerilogGenerator extends App {
  emitVerilog(new microwave(), args)
}