package llmverify

import chisel3._

object Constants {
  //-----------------------------------------------------------------------
  // data sizes
  //-----------------------------------------------------------------------
  val B = 8     // byte
  val W = 16    // word
  val L = 32    // longword
  val Q = 32    // quadword (using 32 instead of 64 as in original)
  val C = 21    // constant

  //-----------------------------------------------------------------------
  // MSBs
  //-----------------------------------------------------------------------
  val BM = 7    // byte msb
  val WM = 15   // word msb
  val LM = 31   // longword msb
  val QM = 31   // quadword msb (using 31 instead of 63 as in original)
  val CM = 20   // constant msb

  //-----------------------------------------------------------------------
  // bus width definitions (for Chisel usage)
  //-----------------------------------------------------------------------
  val DataXWidth = Q + 1     // extended data busses (include a carry bit)
  val DataQWidth = QM + 1    // quadword data busses
  val DataLWidth = LM + 1    // longword data busses
  val DataWWidth = WM + 1    // word data busses
  val DataBWidth = BM + 1    // byte data busses
  val InsnWidth = LM + 1     // instruction
  val ConstWidth = C + 1     // valid bit + constants and displacements
  val ConstNWidth = CM + 1   // immediate constants and displacements
  val ExcWidth = 4           // exceptions
  val OpcWidth = 6           // opcode
  val FctWidth = 7           // function code
  val RegWidth = 6           // valid bit + register numbers
  val RegNWidth = 5          // register numbers
  val PCAdrWidth = QM + 1    // program counter

  //-----------------------------------------------------------------------
  // misc
  //-----------------------------------------------------------------------
  val FALSE = false.B  // boolean false
  val TRUE = true.B   // boolean true

  val DELTA = 1       // delay for behavioral sequential modelling

  //-----------------------------------------------------------------------
  // Exception Codes
  // msb clear ... no exception
  // msb set ..... exception type indicated by lower significant bits
  //-----------------------------------------------------------------------
  val EXC_NONE = "b0000".U   // no exception
  val EXC_OVFL = "b1000".U   // overflow (from ALU)
  val EXC_PAL = "b1001".U    // pal call (from IDU)
  val EXC_RESV = "b1010".U   // reserved opcode (from IDU)
  val EXC_FP = "b1011".U     // floating point opcode (from IDU)
  val EXC_UDEF = "b1100".U   // undefined function code (from ALU)
  val EXC_LDLSTC = "b1101".U // LDxL / STxC opcode (from IDU)
}