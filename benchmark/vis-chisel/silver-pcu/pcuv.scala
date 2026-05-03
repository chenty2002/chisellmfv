package llmverify

import chisel3._
import chisel3.util._

/*
// ----------------- // Pipeline Control Unit // -----------------
// Authors:
//   Shawn Morrison
//   Vipul Gandhi
//
// The PCU has the following responsibilities:
//
// - instruction cache miss pipeline stall
// - read data cache miss pipeline stall
// - store buffer full pipeline stall
//
// - IDU exception handling
// - EXU exception handling
//
// - force PC in IFU on exception
//
// - pipeling bubble insertion, on exceptions and load followed by ALU
//   with data dependency
//
//-----------------------------------------------------------------------*/

class PCUIO extends Bundle {
  // Step signals - control when stages latch inputs
  val StepIDU = Output(Bool())
  val StepEXU = Output(Bool())
  val StepMAU = Output(Bool())
  val StepWB = Output(Bool())
  
  // Work signals - control when stages process data
  val WorkIDU = Output(Bool())
  val WorkEXU = Output(Bool())
  val WorkMAU = Output(Bool())
  val WorkWB = Output(Bool())
  val WorkIFU = Output(Bool())
  val WkMAU = Output(Bool()) // Work signal for FRU unit
  
  // Control inputs
  val nReset = Input(Bool())
  val nFlushPipe = Input(Bool())
  val nIFUNotReady = Input(Bool())
  val EXUMultiply = Input(Bool())
  val nMAUNotReady = Input(Bool())
  val EXUMemory = Input(Bool())
  val DataDep = Input(Bool())
  
  // Exception inputs (4 bits each)
  val ExceptIDU = Input(UInt(4.W))
  val ExceptEXU = Input(UInt(4.W))
}

class PCU extends Module {
  val io = IO(new PCUIO)
  
  // Constants
  val FALSE = false.B
  val TRUE = true.B
  
  // Internal registers for input signals (delayed by one clock)
  val I_nReset = RegNext(io.nReset, false.B)
  val I_nFlushPipe = RegNext(io.nFlushPipe, false.B)
  val I_nIFUNotReady = RegNext(io.nIFUNotReady, false.B)
  val I_EXUMultiply = RegNext(io.EXUMultiply, false.B)
  val I_nMAUNotReady = RegNext(io.nMAUNotReady, false.B)
  val I_EXUMemory = RegNext(io.EXUMemory, false.B)
  val I_DataDep = RegNext(io.DataDep, false.B)
  val I_ExceptIDU = RegNext(io.ExceptIDU, 0.U)
  val I_ExceptEXU = RegNext(io.ExceptEXU, 0.U)
  
  // Internal registers for step and work functions
  val R_WorkIFU = RegInit(FALSE)
  val R_WorkIDU = RegInit(FALSE)
  val R_WorkEXU = RegInit(FALSE)
  val R_WorkMAU = RegInit(FALSE)
  val R_StepIDU = RegInit(FALSE)
  val R_StepEXU = RegInit(FALSE)
  val R_StepMAU = RegInit(FALSE)
  val R_EXUMultiply = RegInit(FALSE)
  val R_ExceptIDU = RegInit(0.U(4.W))
  val R_ExceptEXU = RegInit(0.U(4.W))
  
  // Multiply register for multiple-cycle operations
  val MultiplyReg = RegInit(0.U(5.W))
  
  // Internal wires for work and step signals (combinational)
  val I_WWorkIFU = Wire(Bool())
  val I_WWorkIDU = Wire(Bool())
  val I_WWorkEXU = Wire(Bool())
  val I_WWorkMAU = Wire(Bool())
  val I_WWorkWB = Wire(Bool())
  val I_SStepIDU = Wire(Bool())
  val I_SStepEXU = Wire(Bool())
  val I_SStepMAU = Wire(Bool())
  val I_SStepWB = Wire(Bool())
  
  // Exception handling wires
  val I_EExceptMAU = Wire(UInt(4.W))
  val I_EExceptIDU = Wire(UInt(4.W))
  val I_EExceptEXU = Wire(UInt(4.W))
  
  // Exception logic
  I_EExceptMAU := Cat(R_ExceptEXU(3) & I_nFlushPipe, R_ExceptEXU(2,0))
  I_EExceptIDU := Cat(I_ExceptIDU(3) & I_nFlushPipe, I_ExceptIDU(2,0))
  I_EExceptEXU := Cat(
    (I_ExceptEXU(3) & I_nFlushPipe) | R_ExceptIDU(3),
    (I_ExceptEXU(3) & I_ExceptEXU(2)) | (~I_ExceptEXU(3) & R_ExceptIDU(2)),
    (I_ExceptEXU(3) & I_ExceptEXU(1)) | (~I_ExceptEXU(3) & R_ExceptIDU(1)),
    (I_ExceptEXU(3) & I_ExceptEXU(0)) | (~I_ExceptEXU(3) & R_ExceptIDU(0))
  )
  
  // Function for shifting multiply register
  def funShift(a: UInt): UInt = {
    MuxCase(0.U(5.W), Seq(
      (a === 0.U) -> 0.U,
      (a === 1.U) -> 2.U,
      (a === 2.U) -> 4.U,
      (a === 3.U) -> 6.U,
      (a === 4.U) -> 8.U,
      (a === 5.U) -> 10.U,
      (a === 6.U) -> 12.U,
      (a === 7.U) -> 14.U,
      (a === 8.U) -> 16.U,
      (a === 9.U) -> 18.U,
      (a === 10.U) -> 20.U,
      (a === 11.U) -> 22.U,
      (a === 12.U) -> 24.U,
      (a === 13.U) -> 26.U,
      (a === 14.U) -> 28.U,
      (a === 15.U) -> 30.U,
      (a === 16.U) -> 0.U,
      (a === 17.U) -> 2.U,
      (a === 18.U) -> 4.U,
      (a === 19.U) -> 6.U,
      (a === 20.U) -> 8.U,
      (a === 21.U) -> 10.U,
      (a === 22.U) -> 12.U,
      (a === 23.U) -> 14.U,
      (a === 24.U) -> 16.U,
      (a === 25.U) -> 18.U,
      (a === 26.U) -> 20.U,
      (a === 27.U) -> 22.U,
      (a === 28.U) -> 24.U,
      (a === 29.U) -> 26.U,
      (a === 30.U) -> 28.U,
      (a === 31.U) -> 30.U
    ))
  }
  
  // Default assignments for work and step signals
  I_WWorkIFU := FALSE
  I_WWorkIDU := FALSE
  I_WWorkEXU := FALSE
  I_WWorkMAU := FALSE
  I_WWorkWB := FALSE
  I_SStepIDU := FALSE
  I_SStepEXU := FALSE
  I_SStepMAU := FALSE
  I_SStepWB := FALSE
  
  // Main combinational logic for pipeline control
  // Note: We need to break the combinational cycle by using previous cycle's I_WWorkEXU
  val prev_I_WWorkEXU = RegNext(I_WWorkEXU, FALSE)
  val controlCase = Cat(I_EExceptIDU(3), I_EExceptEXU(3), I_nMAUNotReady, (R_EXUMultiply & ~MultiplyReg(4) & prev_I_WWorkEXU))
  
  switch(controlCase) {
    // IFU Not Ready - No Stalls Default
    is("b0010".U) {
      I_WWorkIFU := (~I_DataDep) & ~(I_EExceptMAU(3) | I_EExceptIDU(3) | I_EExceptEXU(3))
      I_WWorkIDU := I_DataDep | (I_nIFUNotReady & R_WorkIFU)
      I_WWorkEXU := I_nFlushPipe & (~I_DataDep) & R_WorkIDU
      I_WWorkMAU := I_nFlushPipe & R_WorkEXU
      I_WWorkWB := R_WorkMAU
      I_SStepIDU := ~I_DataDep & I_nIFUNotReady & R_WorkIFU
      I_SStepEXU := ~I_DataDep & R_StepIDU
      I_SStepMAU := R_StepEXU
      I_SStepWB := R_StepMAU
    }
    // MAU not ready with exception or MAU not ready
    is("b1000".U, "b1001".U, "b1100".U, "b1101".U, "b0100".U, "b0101".U, "b0000".U, "b0001".U) {
      I_WWorkIFU := FALSE
      I_WWorkIDU := R_WorkIDU
      I_WWorkEXU := R_WorkEXU
      I_WWorkMAU := R_WorkMAU
      I_WWorkWB := FALSE
      I_SStepIDU := FALSE
      I_SStepEXU := FALSE
      I_SStepMAU := FALSE
      I_SStepWB := FALSE
    }
    // EXU Multiply & IDU Exception
    is("b1011".U, "b0011".U) {
      I_WWorkIFU := FALSE
      I_WWorkIDU := R_WorkIDU
      I_WWorkEXU := R_WorkEXU
      I_WWorkMAU := FALSE
      I_WWorkWB := R_WorkMAU
      I_SStepIDU := FALSE
      I_SStepEXU := FALSE
      I_SStepMAU := FALSE
      I_SStepWB := R_StepMAU
    }
    // EXU Exception
    is("b0110".U, "b1110".U, "b1111".U, "b0111".U) {
      I_WWorkIFU := FALSE
      I_WWorkIDU := FALSE
      I_WWorkEXU := FALSE
      I_WWorkMAU := FALSE
      I_WWorkWB := R_WorkMAU
      I_SStepIDU := ~I_DataDep & I_nIFUNotReady & R_WorkIFU
      I_SStepEXU := ~I_DataDep & R_StepIDU
      I_SStepMAU := R_StepEXU
      I_SStepWB := R_StepMAU
    }
    // IDU exception and MAU is ready and No Multiply
    is("b1010".U) {
      I_WWorkIFU := FALSE
      I_WWorkIDU := FALSE
      I_WWorkEXU := FALSE
      I_WWorkMAU := R_WorkEXU
      I_WWorkWB := R_WorkMAU
      I_SStepIDU := ~I_DataDep & I_nIFUNotReady & R_WorkIFU
      I_SStepEXU := ~I_DataDep & R_StepIDU
      I_SStepMAU := R_StepEXU
      I_SStepWB := R_StepMAU
    }
  }
  
  // Sequential logic with proper register updates
  when(I_nReset) {
    R_WorkIDU := I_WWorkIDU
    R_WorkEXU := I_WWorkEXU
    R_WorkMAU := I_WWorkMAU
    
    // Multiple-cycle multiply logic
    when(I_nMAUNotReady && (MultiplyReg(4) || I_EExceptEXU(3))) {
      MultiplyReg := 0.U
    }.elsewhen(R_EXUMultiply) {
      val shiftedValue = funShift(MultiplyReg)
      MultiplyReg := Cat(shiftedValue(4,1), I_WWorkEXU)
    }
    
    // Exception handling
    when(I_nMAUNotReady) {
      when(!(R_EXUMultiply & ~MultiplyReg(4))) {
        R_ExceptIDU := I_EExceptIDU
      }
      R_ExceptEXU := I_EExceptEXU
    }
    
    // Step signals and IFU work signal
    when(!I_nMAUNotReady) {
      // Do nothing
    }.elsewhen(R_EXUMultiply & ~MultiplyReg(4)) {
      R_StepMAU := I_SStepMAU
    }.otherwise {
      R_WorkIFU := I_WWorkIFU | I_DataDep
      R_StepIDU := I_SStepIDU | I_DataDep
      R_StepEXU := I_SStepEXU
      R_EXUMultiply := (I_EXUMultiply & ~I_DataDep)
      R_StepMAU := I_SStepMAU
    }
  }.otherwise {
    // Reset all registers
    R_WorkIFU := FALSE
    R_WorkIDU := FALSE
    R_WorkEXU := FALSE
    R_WorkMAU := FALSE
    R_StepIDU := FALSE
    R_StepEXU := FALSE
    R_StepMAU := FALSE
    R_EXUMultiply := FALSE
    MultiplyReg := 0.U
    R_ExceptIDU := 0.U
    R_ExceptEXU := 0.U
  }
  
  // Output assignments
  io.StepIDU := I_SStepIDU
  io.StepEXU := I_SStepEXU
  io.StepMAU := I_SStepMAU
  io.StepWB := I_SStepWB
  io.WorkIFU := I_WWorkIFU
  io.WorkIDU := I_WWorkIDU
  io.WorkEXU := I_WWorkEXU
  io.WorkMAU := I_WWorkMAU & I_EXUMemory
  io.WorkWB := I_WWorkWB
  io.WkMAU := I_WWorkMAU
}

object VerilogGenerator extends App {
  emitVerilog(new PCU(), args)
}