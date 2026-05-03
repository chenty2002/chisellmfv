package llmverify

object DecodeConstants {
  // Alpha encoding - instruction encoding field positions
  // These represent bit ranges, stored as (high, low) tuples
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

  // Processor internal encoding - decoded information
  // Always use these constants when querying information on the internal
  // decoded bus. The bus has to be declared with size DEC
  
  // vertical encoding (1 bit per category)
  val DEC_FCT = 2             // decoder function codes (msb)
  
  // horizontal encoding (shared bits)
  val DEC_ALU = DEC_FCT + 1   // is ALU operation
  val DEC_CTR = DEC_ALU + 1   // is control transfer operation
  val DEC_MEM = DEC_CTR + 1   // is memory operation

  // For bus size definition
  val DEC_HOR_HIGH = DEC_MEM
  val DEC_HOR_LOW = DEC_ALU
  val DEC_VERT_HIGH = DEC_FCT
  val DEC_VERT_LOW = 0

  val DEC = DEC_MEM // bus size definition (highest bit position)

  // Vertically encoded information is only valid when the respective
  // horizontally encoded group is active;
  // ie. DEC_MEM_xx is only valid when DEC_MEM is active;

  // Function codes for memory operations
  val DEC_MEM_ST = 0    // store..1 / load..0
  val DEC_MEM_QW = 1    // quadword..1 / longword..0
  val DEC_MEM_ACC = 2   // memory access..1 / addr.computation..0

  // Function codes for control transfer operations
  val DEC_CTR_COND = 0  // conditional..1 / unconditional..0
  val DEC_CTR_PC = 1    // PCrelative..1 / Reg.Indirect..0

  // Function codes for ALU operations
  val DEC_ALU_MULT = 0  // multiply..1 / other..0
}