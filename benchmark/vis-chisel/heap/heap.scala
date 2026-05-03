package llmverify

import chisel3._
import chisel3.util._

// Model of a heap.
//
// The heap holds WORDS keys, each of BITS bits ordered in ascending order.
// Keys may repeat.  The key in first position is always a minimum key.
//
// The heap supports 4 operations:
//
// - NOOP: remain idle
// - PUSH: add a key to the heap if it is not full
// - POP : remove the first element from the heap if it is not empty
// - TEST: check the heap property
//
// When ready is asserted, dout gives the minimum value of the keys held
// in the heap.  Commands are accepted only when ready is asserted.
//
// The number of bits in a key is the logarithm of the number
// of slots in the heap, so that all keys may be distinct.

class Heap(val BITS: Int = 2, val WORDS: Int = 4) extends Module {
  val MSW = WORDS - 1
  val MSB = BITS - 1
  
  // Define enums
  object Op extends ChiselEnum {
    val NOOP = Value(0.U)
    val PUSH = Value(1.U)
    val POP  = Value(2.U)
    val TEST = Value(3.U)
  }
  
  object State extends ChiselEnum {
    val IDLE  = Value(0.U)
    val PUSH1 = Value(1.U)
    val PUSH2 = Value(2.U)
    val POP1  = Value(3.U)
    val POP2  = Value(4.U)
    val POP3  = Value(5.U)
    val TEST1 = Value(6.U)
    val TEST2 = Value(7.U)
  }
  
  val io = IO(new Bundle {
    val cmd   = Input(Op())
    val din   = Input(UInt(BITS.W))
    val dout  = Output(UInt(BITS.W))
    val ready = Output(Bool())
    val full  = Output(Bool())
    val empty = Output(Bool())
    val error = Output(Bool())
  })
  
  // Internal registers
  val nitems = RegInit(0.U((BITS + 1).W))
  val posn   = RegInit(0.U((BITS + 1).W))
  val h0     = RegInit(0.U(BITS.W))
  val h1     = RegInit(0.U(BITS.W))
  val h2     = RegInit(0.U(BITS.W))
  val state  = RegInit(State.IDLE)
  val error  = RegInit(false.B)
  
  // Heap array
  val h = RegInit(VecInit(Seq.fill(WORDS)(0.U(BITS.W))))
  
  // Helper functions
  def parent(i: UInt): UInt = {
    val tmp = i - 1.U
    Cat(0.U(1.W), tmp(BITS, 1))
  }
  
  def left(i: UInt): UInt = {
    Cat(i(BITS - 1, 0), 0.U(1.W)) + 1.U
  }
  
  def right(i: UInt): UInt = {
    val tmp = i + 1.U
    Cat(tmp(BITS - 1, 0), 0.U(1.W))
  }
  
  // Combinational outputs
  io.dout  := h(0)
  io.ready := (state === State.IDLE)
  io.full  := (nitems === WORDS.U)
  io.empty := (nitems === 0.U)
  io.error := error
  
  // Calculate parent, left, right for current position
  val prnt = parent(posn)
  val lft  = left(posn)
  val rght = right(posn)
  
  // State machine
  switch(state) {
    is(State.IDLE) {
      switch(io.cmd) {
        is(Op.PUSH) {
          when(!io.full) {
            posn := nitems
            h0 := io.din
            nitems := nitems + 1.U
            state := State.PUSH1
          }
        }
        is(Op.POP) {
          when(!io.empty) {
            nitems := nitems - 1.U
            posn := 0.U
            h0 := h(nitems)
            h(0) := h0
            state := State.POP1
          }
        }
        is(Op.TEST) {
          posn := 1.U
          error := false.B
          state := State.TEST1
        }
        is(Op.NOOP) {
          // Do nothing
        }
      }
    }
    
    is(State.PUSH1) {
      h1 := h(prnt)
      state := State.PUSH2
    }
    
    is(State.PUSH2) {
      when(posn === 0.U || h1 <= h0) {
        h(posn) := h0
        state := State.IDLE
      }.otherwise {
        h(posn) := h1
        posn := prnt
        state := State.PUSH1
      }
    }
    
    is(State.POP1) {
      h1 := h(lft)
      state := State.POP2
    }
    
    is(State.POP2) {
      h2 := h(rght)
      state := State.POP3
    }
    
    is(State.POP3) {
      when(lft < nitems && h1 < h0 && (rght >= nitems || h1 <= h2)) {
        h(posn) := h1
        posn := lft
        state := State.POP1
      }.elsewhen(rght < nitems && h2 < h0) {
        h(posn) := h2
        posn := rght
        state := State.POP1
      }.otherwise {
        h(posn) := h0
        state := State.IDLE
      }
    }
    
    is(State.TEST1) {
      when(posn >= nitems) {
        state := State.IDLE
      }.otherwise {
        h1 := h(prnt)
        state := State.TEST2
      }
    }
    
    is(State.TEST2) {
      when(h(posn) < h1) {
        error := true.B
        state := State.IDLE
      }.otherwise {
        posn := posn + 1.U
        state := State.TEST1
      }
    }
  }
}

object VerilogGenerator extends App {
  emitVerilog(new Heap(), args)
}