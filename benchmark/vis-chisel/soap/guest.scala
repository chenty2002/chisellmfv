package llmverify

import chisel3._
import chisel3.util._

class Guest extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val reset = Input(Bool())
    val start = Input(Bool())
    val reqIn = Input(UInt(4.W))
    val initpred = Input(UInt(3.W))
    val granted = Input(UInt(4.W))
    val shower = Output(Bool())
    val reqOut = Output(UInt(4.W))
    val grant = Output(UInt(4.W))
  })
  
  // Constants
  val N = 0.U(3.W)
  val W = 1.U(3.W)
  val S = 2.U(3.W)
  val E = 3.U(3.W)
  val SELF = 4.U(3.W)
  
  // Condition states
  val clean = 0.U(2.W)
  val dirty = 1.U(2.W)
  val showeringState = 2.U(2.W)
  
  // Activity states
  val idle = 0.U(1.W)
  val busy = 1.U(1.W)
  
  // Registers
  val condition = RegInit(clean)
  val activity = RegInit(idle)
  val predecessor = RegInit(io.initpred)
  val serving = RegInit(SELF)
  val requestReg = RegInit(0.U(5.W))
  
  // Helper functions
  def select(in: UInt, sel: UInt): Bool = {
    MuxCase(false.B, Seq(
      (sel === 0.U) -> in(0),
      (sel === 1.U) -> in(1),
      (sel === 2.U) -> in(2),
      (sel === 3.U) -> in(3),
      (sel === 4.U) -> in(4)
    ))
  }
  
  def incMod5(op: UInt): UInt = {
    Mux(op === 4.U, 0.U, op + 1.U)
  }
  
  def pickRequest(req: UInt, rrobin: UInt): UInt = {
    val startIdx = incMod5(rrobin)
    val idx1 = incMod5(startIdx)
    val idx2 = incMod5(idx1)
    val idx3 = incMod5(idx2)
    val idx4 = incMod5(idx3)
    
    Mux(req === 0.U, rrobin,
      Mux(select(req, startIdx), startIdx,
        Mux(select(req, idx1), idx1,
          Mux(select(req, idx2), idx2,
            Mux(select(req, idx3), idx3, idx4)
          )
        )
      )
    )
  }
  
  // Wires
  val soap = Wire(Bool())
  val toBeServed = Wire(UInt(3.W))
  val requestPending = Wire(Bool())
  val mask = Wire(UInt(4.W))
  val mbar = Wire(UInt(4.W))
  val soapIsComing = Wire(Bool())
  
  // Decoder instances
  val decoder1 = Module(new Decoder())
  decoder1.io.in := serving
  decoder1.io.en := soap
  val grant = decoder1.io.dec
  
  val decoder2 = Module(new Decoder())
  decoder2.io.in := serving
  decoder2.io.en := true.B
  val mbarWire = decoder2.io.dec
  mbar := mbarWire
  
  val decoder3 = Module(new Decoder())
  decoder3.io.in := predecessor
  decoder3.io.en := (activity === busy)
  val reqOutWire = decoder3.io.dec
  
  // Combinational logic
  soap := (predecessor === SELF)
  toBeServed := pickRequest(requestReg, serving)
  requestPending := (requestReg =/= 0.U)
  mask := ~mbar
  soapIsComing := select(Cat(0.U(1.W), io.granted), predecessor)
  
  // Sequential logic
  when(io.reset) {
    condition := clean
    activity := idle
    predecessor := io.initpred
    requestReg := 0.U
    serving := SELF
  }.otherwise {
    // Update request register (disregards further requests from the guest being served)
    requestReg := Cat(requestReg(4), (io.reqIn & mask))
    
    // Condition state machine
    switch(condition) {
      is(clean) {
        when(io.start) {
          condition := dirty
          requestReg := Cat(1.U, requestReg(3,0))
        }
      }
      is(dirty) {
        when(soap && (serving === SELF)) {
          condition := showeringState
          requestReg := Cat(0.U, requestReg(3,0))
        }
      }
      is(showeringState) {
        condition := clean // one cycle to shower
      }
    }
    
    // Activity state machine
    switch(activity) {
      is(idle) {
        when(requestPending && (condition =/= showeringState)) {
          serving := toBeServed
          activity := busy
        }
      }
      is(busy) {
        when(soapIsComing) {
          predecessor := SELF
        }.elsewhen(soap) {
          predecessor := serving
          activity := idle
        }
      }
    }
  }
  
  // Outputs
  io.shower := (condition === showeringState)
  io.reqOut := reqOutWire
  io.grant := grant
}

object Guest extends App {
  emitVerilog(new Guest(), args)
}