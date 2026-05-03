package llmverify

import chisel3._
import chisel3.util._

class Nim(LOGCOL: Int = 2, LOGCNT: Int = 4) extends Module {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val col = Input(UInt(LOGCOL.W))
    val num = Input(UInt(LOGCNT.W))
    val win = Output(Bool())
    val lose = Output(Bool())
    val winning = Output(Bool())
  })
  
  // Parameters
  val NCOL = 1 << LOGCOL
  val MSBCOL = LOGCOL - 1
  val MSBCNT = LOGCNT - 1
  
  // Registers
  val pile = RegInit(VecInit(Seq.fill(NCOL)(0.U(LOGCNT.W))))
  val load = RegInit(NCOL.U((LOGCOL + 2).W))
  val valid = RegInit(false.B)
  val winReg = RegInit(false.B)
  val loseReg = RegInit(false.B)
  val value = RegInit(0.U(LOGCNT.W))
  val turn = RegInit(false.B)
  val temp = RegInit(0.U(LOGCNT.W))
  val found = RegInit(false.B)
  
  // Combinational output for winning
  io.winning := (load === 0.U) && (value =/= 0.U)
  
  // Connect outputs
  io.win := winReg
  io.lose := loseReg
  
  // Sequential logic
  when (load > 0.U) {
    load := load - 1.U
    pile(load) := io.num
    value := value ^ io.num
  }.elsewhen (turn === 0.U) {
    // Environment's turn
    loseReg := ~winReg
    // Check if all piles are empty
    val allEmpty = pile.map(_ === 0.U).reduce(_ && _)
    loseReg := loseReg && allEmpty
    
    valid := (io.col <= (NCOL - 1).U) && (io.num > 0.U) && (io.num <= pile(io.col))
    when (valid) {
      pile(io.col) := pile(io.col) - io.num
      turn := 1.U
    }
  }.otherwise {
    // System's turn
    winReg := ~loseReg
    // Check if all piles are empty
    val allEmpty = pile.map(_ === 0.U).reduce(_ && _)
    winReg := winReg && allEmpty
    
    when (winReg === 0.U) {
      found := 0.U
      when (value === 0.U) {
        // Losing position: Remove one counter from first non-empty pile
        for (i <- 0 until NCOL) {
          when (found === 0.U) {
            when (pile(i) > 0.U) {
              found := 1.U
              pile(i) := pile(i) - 1.U
            }
          }
        }
      }.otherwise {
        // Winning position: remove counters from first pile with enough counters
        for (i <- 0 until NCOL) {
          when (found === 0.U) {
            temp := value ^ pile(i)
            when (temp < pile(i)) {
              found := 1.U
              pile(i) := temp
            }
          }
        }
      }
    }
    turn := 0.U
  }
  
  // Update value (always happens at the end of the cycle)
  val newValue = pile.reduce(_ ^ _)
  value := newValue
}

object VerilogGenerator extends App {
  emitVerilog(new Nim(), args)
}