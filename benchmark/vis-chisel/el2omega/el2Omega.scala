package llmverify
import chisel3._
import chisel3.util._

class el2Omega extends Module {
  val io = IO(new Bundle {
    val pause = Input(Bool())
    val rchoice = Input(UInt(2.W))  // ROWMSB+1 = 2 bits
    val cchoice = Input(UInt(4.W))  // COLMSB+1 = 4 bits
    val dchoice = Input(UInt(2.W))  // DIGMSB+1 = 2 bits
    val colmsb = Output(Bool())
    val collsb = Output(Bool())
  })
  
  // Parameters
  val ROWMSB = 1
  val ROWBITS = ROWMSB + 1
  val COLMSB = ROWMSB + 2
  val COLBITS = COLMSB + 1
  val DIGMSB = 1
  
  // Internal registers
  val row = RegInit(io.rchoice)
  val col = RegInit(0.U(COLBITS.W))
  val digit = RegInit(0.U((DIGMSB + 1).W))
  
  // Output assignments
  io.colmsb := col(COLMSB)
  io.collsb := col(0)
  
  // State transition logic
  when((col + Cat(0.U(1.W), row, 0.U(1.W))) =/= Fill(COLBITS, 1.U)) {
    // Not a sink
    when(col <= Cat(0.U(1.W), Fill(COLMSB, 1.U))) {
      // An "x" state. Go to any "x" state to the right or first "o" state on the same row
      when(io.cchoice > col && io.cchoice <= Cat(1.U(1.W), Fill(COLMSB, 0.U))) {
        col := io.cchoice
      }.otherwise {
        col := col + 1.U
      }
    }.otherwise {
      // An "o" (col[0]==0) or digit (col[0]==1) state
      when(col(0) === 0.U || !io.pause) {
        // Move forward without overshooting the end of the row
        when(io.cchoice > col && Cat(0.U(1.W), io.cchoice) + Cat(0.U(2.W), row, 0.U(1.W)) <= Cat(0.U(1.W), Fill(COLBITS, 1.U))) {
          col := io.cchoice
        }.otherwise {
          col := col + 1.U
        }
        // If new state is a digit state, choose the digit
        when(col(0) === 1.U) {
          digit := io.dchoice
        }.otherwise {
          digit := 0.U
        }
      }
    }
  }
}

object VerilogGenerator extends App {
  emitVerilog(new el2Omega(), args)
}