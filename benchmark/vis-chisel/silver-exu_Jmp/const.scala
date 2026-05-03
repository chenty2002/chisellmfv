package llmverify

import chisel3._

object ConstConstants {
  // Data sizes
  val B = 8     // byte
  val W = 16    // word
  val L = 32    // longword
  val Q = 32    // quadword (changed from 64 to 32)
  val C = 21    // constant

  // MSBs
  val BM = 7    // byte msb
  val WM = 15   // word msb
  val LM = 31   // longword msb
  val QM = 31   // quadword msb (changed from 63 to 31)
  val CM = 20   // constant msb

  // Exception Codes
  // msb clear ... no exception
  // msb set ..... exception type indicated by lower significant bits
  val EXC_NONE = "b0000".U(4.W)    // no exception
  val EXC_OVFL = "b1000".U(4.W)    // overflow (from ALU)
  val EXC_PAL = "b1001".U(4.W)     // pal call (from IDU)
  val EXC_RESV = "b1010".U(4.W)    // reserved opcode (from IDU)
  val EXC_FP = "b1011".U(4.W)      // floating point opcode (from IDU)
  val EXC_UDEF = "b1100".U(4.W)    // undefined function code (from ALU)
  val EXC_LDLSTC = "b1101".U(4.W)  // LDxL / STxC opcode (from IDU)

  // Boolean values
  val FALSE = 0.U(1.W)  // boolean false
  val TRUE = 1.U(1.W)   // boolean true

  val DELTA = 1  // delay for behavioral sequential modelling
}