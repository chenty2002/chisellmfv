package llmverify

import chisel3._
import chisel3.util._

// Enum for process states
object ProcPhase {
  def idle = 0.U(2.W)
  def requesting = 1.U(2.W)
  def locking = 2.U(2.W)
}

// Enum for cell states
object CellState {
  def I1 = 0.U(3.W)
  def I2 = 1.U(3.W)
  def R1 = 2.U(3.W)
  def R2 = 3.U(3.W)
  def A1 = 4.U(3.W)
  def A2 = 5.U(3.W)
}

// Process module
class Proc extends Module {
  val io = IO(new Bundle {
    val ack = Input(Bool())
    val choice = Input(Bool())
    val req = Output(Bool())
  })
  
  val state = RegInit(ProcPhase.idle)
  
  // State machine logic
  switch(state) {
    is(ProcPhase.idle) {
      when(io.choice) {
        state := ProcPhase.requesting
      }
    }
    is(ProcPhase.requesting) {
      when(io.ack) {
        state := ProcPhase.locking
      }
    }
    is(ProcPhase.locking) {
      when(io.choice) {
        state := ProcPhase.idle
      }
    }
  }
  
  io.req := (state === ProcPhase.requesting) || (state === ProcPhase.locking)
}

// Cell module
class Cell extends Module {
  val io = IO(new Bundle {
    val ack0 = Input(Bool())
    val req1 = Input(Bool())
    val req2 = Input(Bool())
    val req0 = Output(Bool())
    val ack1 = Output(Bool())
    val ack2 = Output(Bool())
  })
  
  val state = RegInit(CellState.I1)
  
  // State machine logic
  switch(state) {
    is(CellState.I1) {
      when(io.req1) {
        state := CellState.R1
      }.elsewhen(io.req2) {
        state := CellState.R2
      }
    }
    is(CellState.R1) {
      when(io.ack0) {
        state := CellState.A1
      }
    }
    is(CellState.A1) {
      when(!io.req1) {
        state := CellState.I2
      }
    }
    is(CellState.I2) {
      when(io.req2) {
        state := CellState.R2
      }.elsewhen(io.req1) {
        state := CellState.R1
      }
    }
    is(CellState.R2) {
      when(io.ack0) {
        state := CellState.A2
      }
    }
    is(CellState.A2) {
      when(!io.req2) {
        state := CellState.I1
      }
    }
  }
  
  io.req0 := (state === CellState.R1) || (state === CellState.R2) || 
              (state === CellState.A1) || (state === CellState.A2)
  io.ack1 := (state === CellState.A1)
  io.ack2 := (state === CellState.A2)
}

// Arbiter module
class Arbiter extends Module {
  val io = IO(new Bundle {
    val choice = Input(UInt(8.W))
    // Add outputs to preserve internal signals
    val a0 = Output(UInt(8.W))
    val a1 = Output(UInt(4.W))
    val a2 = Output(UInt(2.W))
    val a3 = Output(UInt(1.W))
    val r0 = Output(UInt(8.W))
    val r1 = Output(UInt(4.W))
    val r2 = Output(UInt(2.W))
    val r3 = Output(UInt(1.W))
  })
  
  // Internal wires using Vec for proper bit assignment
  val a3 = Wire(Bool())
  val a2 = Wire(Vec(2, Bool()))
  val a1 = Wire(Vec(4, Bool()))
  val a0 = Wire(Vec(8, Bool()))
  val r3 = Wire(Bool())
  val r2 = Wire(Vec(2, Bool()))
  val r1 = Wire(Vec(4, Bool()))
  val r0 = Wire(Vec(8, Bool()))
  
  // The root's request is always acknowledged
  a3 := r3
  
  // Level 2 cell
  val C2_0 = Module(new Cell())
  C2_0.io.req1 := r2(0)
  C2_0.io.req2 := r2(1)
  a2(0) := C2_0.io.ack1
  a2(1) := C2_0.io.ack2
  C2_0.io.ack0 := a3
  r3 := C2_0.io.req0
  
  // Level 1 cells
  val C1_0 = Module(new Cell())
  C1_0.io.req1 := r1(0)
  C1_0.io.req2 := r1(1)
  a1(0) := C1_0.io.ack1
  a1(1) := C1_0.io.ack2
  C1_0.io.ack0 := a2(0)
  r2(0) := C1_0.io.req0
  
  val C1_1 = Module(new Cell())
  C1_1.io.req1 := r1(2)
  C1_1.io.req2 := r1(3)
  a1(2) := C1_1.io.ack1
  a1(3) := C1_1.io.ack2
  C1_1.io.ack0 := a2(1)
  r2(1) := C1_1.io.req0
  
  // Level 0 cells
  val C0_0 = Module(new Cell())
  C0_0.io.req1 := r0(0)
  C0_0.io.req2 := r0(1)
  a0(0) := C0_0.io.ack1
  a0(1) := C0_0.io.ack2
  C0_0.io.ack0 := a1(0)
  r1(0) := C0_0.io.req0
  
  val C0_1 = Module(new Cell())
  C0_1.io.req1 := r0(2)
  C0_1.io.req2 := r0(3)
  a0(2) := C0_1.io.ack1
  a0(3) := C0_1.io.ack2
  C0_1.io.ack0 := a1(1)
  r1(1) := C0_1.io.req0
  
  val C0_2 = Module(new Cell())
  C0_2.io.req1 := r0(4)
  C0_2.io.req2 := r0(5)
  a0(4) := C0_2.io.ack1
  a0(5) := C0_2.io.ack2
  C0_2.io.ack0 := a1(2)
  r1(2) := C0_2.io.req0
  
  val C0_3 = Module(new Cell())
  C0_3.io.req1 := r0(6)
  C0_3.io.req2 := r0(7)
  a0(6) := C0_3.io.ack1
  a0(7) := C0_3.io.ack2
  C0_3.io.ack0 := a1(3)
  r1(3) := C0_3.io.req0
  
  // Processes
  val P0 = Module(new Proc())
  P0.io.ack := a0(0)
  P0.io.choice := io.choice(0)
  r0(0) := P0.io.req
  
  val P1 = Module(new Proc())
  P1.io.ack := a0(1)
  P1.io.choice := io.choice(1)
  r0(1) := P1.io.req
  
  val P2 = Module(new Proc())
  P2.io.ack := a0(2)
  P2.io.choice := io.choice(2)
  r0(2) := P2.io.req
  
  val P3 = Module(new Proc())
  P3.io.ack := a0(3)
  P3.io.choice := io.choice(3)
  r0(3) := P3.io.req
  
  val P4 = Module(new Proc())
  P4.io.ack := a0(4)
  P4.io.choice := io.choice(4)
  r0(4) := P4.io.req
  
  val P5 = Module(new Proc())
  P5.io.ack := a0(5)
  P5.io.choice := io.choice(5)
  r0(5) := P5.io.req
  
  val P6 = Module(new Proc())
  P6.io.ack := a0(6)
  P6.io.choice := io.choice(6)
  r0(6) := P6.io.req
  
  val P7 = Module(new Proc())
  P7.io.ack := a0(7)
  P7.io.choice := io.choice(7)
  r0(7) := P7.io.req
  
  // Connect Vec signals to UInt outputs for preservation
  io.a0 := Cat(a0.reverse)
  io.a1 := Cat(a1.reverse)
  io.a2 := Cat(a2.reverse)
  io.a3 := a3
  io.r0 := Cat(r0.reverse)
  io.r1 := Cat(r1.reverse)
  io.r2 := Cat(r2.reverse)
  io.r3 := r3
}

// Main object for Verilog generation
object VerilogGenerator extends App {
  emitVerilog(new Arbiter(), args)
}