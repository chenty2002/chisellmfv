package llmverify

import chisel3._

object Const {
  // Data sizes
  val B = 8     // byte
  val W = 16    // word
  val L = 32    // longword
  val Q = 32    // quadword (using 32 instead of 64 as in original)
  val C = 21    // constant

  // MSBs
  val BM = 7    // byte msb
  val WM = 15   // word msb
  val LM = 31   // longword msb
  val QM = 31   // quadword msb (using 31 instead of 63 as in original)
  val CM = 20   // constant msb

  // Exception Codes
  // msb clear ... no exception
  // msb set ..... exception type indicated by lower significant bits
  val EXC_NONE = "b0_000".U(4.W)   // no exception
  val EXC_OVFL = "b1_000".U(4.W)   // overflow (from ALU)
  val EXC_PAL = "b1_001".U(4.W)    // pal call (from IDU)
  val EXC_RESV = "b1_010".U(4.W)   // reserved opcode (from IDU)
  val EXC_FP = "b1_011".U(4.W)     // floating point opcode (from IDU)
  val EXC_UDEF = "b1_100".U(4.W)   // undefined function code (from ALU)
  val EXC_LDLSTC = "b1_101".U(4.W) // LDxL / STxC opcode (from IDU)

  // Boolean values
  val FALSE = false.B
  val TRUE = true.B
}