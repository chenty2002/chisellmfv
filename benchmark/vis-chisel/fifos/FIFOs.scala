package llmverify

import chisel3._
import chisel3.util._

// Model to check the equivalence of two different FIFO implementations.
// This example is taken from Ken McMillan's "A Conjunctively Decomposed
// Boolean Representation for Symbolic Model Checking" (CAV'96), though
// the details are probably different.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDI>

class compareFIFOs(val MSBD: Int = 3, val LAST: Int = 15, val MSBA: Int = 3) extends Module {
  val io = IO(new Bundle {
    val dataIn = Input(UInt((MSBD + 1).W))
    val push = Input(Bool())
    val pop = Input(Bool())
    val equal = Output(Bool())
    
    // Additional outputs to preserve internal signals
    val srDataOut = Output(UInt((MSBD + 1).W))
    val srFull = Output(Bool())
    val srEmpty = Output(Bool())
    val rbDataOut = Output(UInt((MSBD + 1).W))
    val rbFull = Output(Bool())
    val rbEmpty = Output(Bool())
  })

  // Instantiate shift register FIFO
  val sr = Module(new srFIFO(MSBD, LAST, MSBA))
  sr.io.dataIn := io.dataIn
  sr.io.push := io.push
  sr.io.pop := io.pop
  
  // Instantiate ring buffer FIFO
  val rb = Module(new rbFIFO(MSBD, LAST, MSBA))
  rb.io.dataIn := io.dataIn
  rb.io.push := io.push
  rb.io.pop := io.pop
  
  // Connect internal signals to outputs for preservation
  io.srDataOut := sr.io.dataOut
  io.srFull := sr.io.full
  io.srEmpty := sr.io.empty
  io.rbDataOut := rb.io.dataOut
  io.rbFull := rb.io.full
  io.rbEmpty := rb.io.empty
  
  // The outputs of the two FIFOs are not specified when the
  // buffers are empty.
  io.equal := (sr.io.full === rb.io.full) && (sr.io.empty === rb.io.empty) &&
    (sr.io.empty || (sr.io.dataOut === rb.io.dataOut))
}

// Shift register FIFO.
// tail points to the first element of the queue unless the buffer is empty.
// the new data is always inserted in position 0 of the buffer, after
// shifting the contents up by one position.
// A push on a full buffer is a NOOP.
// A pop from an empty buffer is a NOOP.
// If both push and pop are asserted at the same clock cycle, only the push
// operation is performed.
// dataOut gives the first element of the queue unless the buffer is empty,
// in which case its value is arbitrary.
class srFIFO(val MSBD: Int = 3, val LAST: Int = 15, val MSBA: Int = 3) extends Module {
  val io = IO(new Bundle {
    val dataIn = Input(UInt((MSBD + 1).W))
    val push = Input(Bool())
    val pop = Input(Bool())
    val dataOut = Output(UInt((MSBD + 1).W))
    val full = Output(Bool())
    val empty = Output(Bool())
  })

  // Memory array
  val mem = RegInit(VecInit(Seq.fill(LAST + 1)(0.U((MSBD + 1).W))))
  val tail = RegInit(0.U((MSBA + 1).W))
  val empty = RegInit(true.B)
  
  // Sequential logic
  when(io.push && !io.full) {
    // Shift data up by one position
    for(i <- LAST to 1 by -1) {
      mem(i) := mem(i - 1)
    }
    mem(0) := io.dataIn
    
    when(!empty) {
      tail := tail + 1.U
    }
    empty := false.B
  }.elsewhen(io.pop && !empty) {
    when(tail === 0.U) {
      empty := true.B
    }.otherwise {
      tail := tail - 1.U
    }
  }
  
  // Outputs
  io.dataOut := mem(tail)
  io.full := (tail === LAST.U)
  io.empty := empty
}

// Ring buffer FIFO.
// head points to the insertion point unless the buffer is full.
// tail points to the first element in the queue unless the buffer is empty.
// A push on a full buffer is a NOOP.
// A pop from an empty buffer is a NOOP.
// If both push and pop are asserted at the same clock cycle, only the push
// operation is performed.
// dataOut gives the first element of the queue unless the buffer is empty,
// in which case its value is arbitrary.
class rbFIFO(val MSBD: Int = 3, val LAST: Int = 15, val MSBA: Int = 3) extends Module {
  val io = IO(new Bundle {
    val dataIn = Input(UInt((MSBD + 1).W))
    val push = Input(Bool())
    val pop = Input(Bool())
    val dataOut = Output(UInt((MSBD + 1).W))
    val full = Output(Bool())
    val empty = Output(Bool())
  })

  // Memory array
  val mem = RegInit(VecInit(Seq.fill(LAST + 1)(0.U((MSBD + 1).W))))
  val head = RegInit(0.U((MSBA + 1).W))
  val tail = RegInit(0.U((MSBA + 1).W))
  val empty = RegInit(true.B)
  
  // Sequential logic
  when(io.push && !io.full) {
    mem(head) := io.dataIn
    head := head + 1.U
    empty := false.B
  }.elsewhen(io.pop && !empty) {
    tail := tail + 1.U
    when(tail + 1.U === head) {
      empty := true.B
    }
  }
  
  // Outputs
  io.dataOut := mem(tail)
  io.full := (tail === head) && !empty
  io.empty := empty
}

object VerilogGenerator extends App {
  emitVerilog(new compareFIFOs(), args)
}