package llmverify

import chisel3._

object ConstConstants {
  
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
  
  // Bus width definitions (for reference)
  val DATAX_WIDTH = Q + 1     // extended data busses (include a carry bit)
  val DATAQ_WIDTH = QM + 1    // quadword data busses
  val DATAL_WIDTH = LM + 1    // longword data busses
  val DATAW_WIDTH = WM + 1    // word data busses
  val DATAB_WIDTH = BM + 1    // byte data busses
  val INSN_WIDTH = LM + 1     // instruction
  val CONST_WIDTH = C + 1     // valid bit + constants and displacements
  val CONSTN_WIDTH = CM + 1   // immediate constants and displacements
  val EXC_WIDTH = 4           // exceptions
  val OPC_WIDTH = 6           // opcode
  val FCT_WIDTH = 7           // function code
  val REG_WIDTH = 6           // valid bit + register numbers
  val REGN_WIDTH = 5          // register numbers
  val PCADR_WIDTH = QM + 1    // program counter
  
  // Misc
  val FALSE = 0.U(1.W)  // boolean false
  val TRUE = 1.U(1.W)   // boolean true
  
  val DELTA = 1         // delay for behavioral sequential modelling
  
  // Exception Codes
  // msb clear ... no exception
  // msb set ..... exception type indicated by lower significant bits
  val EXC_NONE = "b0_000".U(4.W)  // no exception
  val EXC_OVFL = "b1_000".U(4.W)  // overflow (from ALU)
  val EXC_PAL = "b1_001".U(4.W)   // pal call (from IDU)
  val EXC_RESV = "b1_010".U(4.W)  // reserved opcode (from IDU)
  val EXC_FP = "b1_011".U(4.W)    // floating point opcode (from IDU)
  val EXC_UDEF = "b1_100".U(4.W)  // undefined function code (from ALU)
  val EXC_LDLSTC = "b1_101".U(4.W) // LDxL / STxC opcode (from IDU)
}