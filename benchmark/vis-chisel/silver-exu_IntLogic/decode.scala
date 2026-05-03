package llmverify

import chisel3._

object Decode {
  //--------------------------------------------------------------------------
  // Alpha encoding
  // instruction encoding field positions
  //--------------------------------------------------------------------------
  val POS_OPCODE_HIGH = 31
  val POS_OPCODE_LOW = 26
  val POS_FUNCTION_HIGH = 11
  val POS_FUNCTION_LOW = 5
  val POS_HINT_HIGH = 15
  val POS_HINT_LOW = 14
  val POS_REGA_HIGH = 25
  val POS_REGA_LOW = 21
  val POS_REGB_HIGH = 20
  val POS_REGB_LOW = 16
  val POS_REGC_HIGH = 4
  val POS_REGC_LOW = 0
  val POS_IMMEDIATE_HIGH = 20
  val POS_IMMEDIATE_LOW = 0
  val POS_DISP_HIGH = 15
  val POS_DISP_LOW = 0
  val POS_DISPHI = 15
  val POS_LITERAL_HIGH = 20
  val POS_LITERAL_LOW = 13

  //--------------------------------------------------------------------------
  // processor internal encoding
  // decoded information
  //
  // Always use these constants when querying information on the internal
  // decoded bus. The bus has to be declared with size DEC
  //--------------------------------------------------------------------------
  // vertical encoding (1 bit per category)
  val DEC_FCT_BIT = 0             // decoder function codes (lsb)
  // horizontal encoding (shared bits)
  val DEC_ALU_BIT = 1   // is ALU operation
  val DEC_CTR_BIT = 2   // is control transfer operation
  val DEC_MEM_BIT = 3   // is memory operation

  val DEC_WIDTH = 4        // bus size definition (bits 3:0)

  //--------------------------------------------------------------------------
  // vertically encoded information is only valid when the respective
  // horizontally encoded group is active;
  // ie. DEC_MEM_xx is only valid when DEC_MEM is active;
  //--------------------------------------------------------------------------

  //--------------------------------------------------------------------------
  // function codes for memory operations
  //--------------------------------------------------------------------------
  val DEC_MEM_ST = 0    // store..1 / load..0
  val DEC_MEM_QW = 1    // quadword..1 / longword..0
  val DEC_MEM_ACC = 2   // memory access..1 / addr.computation..0

  //--------------------------------------------------------------------------
  // function codes for control transfer operations
  //--------------------------------------------------------------------------
  val DEC_CTR_COND = 0  // conditional..1 / unconditional..0
  val DEC_CTR_PC = 1    // PCrelative..1 / Reg.Indirect..0

  //--------------------------------------------------------------------------
  // function codes for ALU operations
  //--------------------------------------------------------------------------
  val DEC_ALU_MULT = 0  // multiply..1 / other..0
}