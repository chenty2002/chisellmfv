package llmverify

import chisel3._

/**
 * Constants definitions
 * Converted from const.v
 */
object const {
  // Data sizes
  val B = 8     // byte
  val W = 16    // word
  val L = 32    // longword
  val Q = 32    // quadword (32-bit implementation)
  val C = 21    // constant

  // MSBs
  val BM = 7    // byte msb
  val WM = 15   // word msb
  val LM = 31   // longword msb
  val QM = 31   // quadword msb (32-bit implementation)
  val CM = 20   // constant msb

  // Exception Codes - use hex literals for UInt compatibility
  val EXC_NONE = 0x0.U  // no exception
  val EXC_OVFL = 0x8.U  // overflow (from ALU)
  val EXC_PAL = 0x9.U   // pal call (from IDU)
  val EXC_RESV = 0xa.U  // reserved opcode (from IDU)
  val EXC_FP = 0xb.U    // floating point opcode (from IDU)
  val EXC_UDEF = 0xc.U  // undefined function code (from ALU)
  val EXC_LDLSTC = 0xd.U // LDxL / STxC opcode (from IDU)

  // Boolean constants - use regular Scala booleans
  val FALSE = false
  val TRUE = true

  // Field positions for Alpha encoding - use tuples for ranges
  val POS_OPCODE = (31, 26)
  val POS_FUNCTION = (11, 5)
  val POS_HINT = (15, 14)
  val POS_REGA = (25, 21)
  val POS_REGB = (20, 16)
  val POS_REGC = (4, 0)
  val POS_IMMEDIATE = (20, 0)
  val POS_DISP = (15, 0)
  val POS_DISPHI = 15
  val POS_LITERAL = (20, 13)

  // Processor internal encoding
  val DEC_FCT = 2             // decoder function codes (msb)
  val DEC_ALU = DEC_FCT + 1   // is ALU operation
  val DEC_CTR = DEC_ALU + 1   // is control transfer operation
  val DEC_MEM = DEC_CTR + 1   // is memory operation

  // Function codes for memory operations
  val DEC_MEM_ST = 0        // store..1 / load..0
  val DEC_MEM_QW = 1        // quadword..1 / longword..0
  val DEC_MEM_ACC = 2       // memory access..1 / addr.computation..0

  // Function codes for control transfer operations
  val DEC_CTR_COND = 0      // conditional..1 / unconditional..0
  val DEC_CTR_PC = 1        // PCrelative..1 / Reg.Indirect..0

  // Function codes for ALU operations
  val DEC_ALU_MULT = 0      // multiply..1 / other..0

  // Helper function to create UInt from hex in object context
  def hexToUInt(hex: String): UInt = {
    val value = Integer.parseInt(hex.replace("h", ""), 16)
    value.U
  }

  // Helper function to create UInt from binary in object context
  def binToUInt(bin: String): UInt = {
    val value = Integer.parseInt(bin.replace("b", ""), 2)
    value.U
  }
}