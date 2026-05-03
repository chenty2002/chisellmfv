package llmverify

/**
 * Decoding definitions
 * Converted from decode.v
 */
object decode {
  // Processor internal encoding
  // decoded information
  // Always use these constants when querying information on the internal
  // decoded bus. The bus has to be declared with size `DEC

  // vertical encoding (1 bit per category)
  val DEC_FCT = 2             // decoder function codes (msb)
  // horizontal encoding (shared bits)
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
}