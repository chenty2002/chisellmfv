package llmverify

import chisel3._

object OpcodeConstants {
  // Integer operation opcodes
  val OP_INTA = "h10".U(6.W)  // (Opr) integer arithmetic operations
  val OP_INTL = "h11".U(6.W)  // (Opr) integer logical operations
  val OP_INTS = "h12".U(6.W)  // (Opr) integer shift operations
  val OP_INTM = "h13".U(6.W)  // (Opr) integer multiply operations

  // Memory operation opcodes
  val OP_LDA = "h08".U(6.W)   // (Mem) load address
  val OP_LDAH = "h09".U(6.W)  // (Mem) load address high
  val OP_LDL = "h28".U(6.W)   // (Mem) load sign-extended longword
  val OP_LDQ = "h29".U(6.W)   // (Mem) load quadword
  val OP_LDL_L = "h2a".U(6.W) // (Mem) load sign-extended longword locked
  val OP_LDQ_L = "h2b".U(6.W) // (Mem) load quadword locked
  val OP_LDQ_U = "h0b".U(6.W) // (Mem) load quadword unaligned
  val OP_STL = "h2c".U(6.W)   // (Mem) store longword
  val OP_STQ = "h2d".U(6.W)   // (Mem) store quadword
  val OP_STL_C = "h2e".U(6.W) // (Mem) store longword conditional
  val OP_STQ_C = "h2f".U(6.W) // (Mem) store quadword conditional
  val OP_STQ_U = "h0f".U(6.W) // (Mem) store quadword unaligned

  // Branch and jump operation opcodes
  val OP_BLBC = "h38".U(6.W)  // (Bra) branch if reg low bit is clear
  val OP_BEQ = "h39".U(6.W)   // (Bra) branch if reg equal to zero
  val OP_BLT = "h3a".U(6.W)   // (Bra) branch if reg less than zero
  val OP_BLE = "h3b".U(6.W)   // (Bra) branch if reg less than or equal to zero
  val OP_BLBS = "h3c".U(6.W)  // (Bra) branch if reg low bit is set
  val OP_BNE = "h3d".U(6.W)   // (Bra) branch if reg not equal to zero
  val OP_BGE = "h3e".U(6.W)   // (Bra) branch if reg greater than or equal to zero
  val OP_BGT = "h3f".U(6.W)   // (Bra) branch if reg greater than zero
  val OP_BR = "h30".U(6.W)    // (Bra) uncond. branch (PC relative)
  val OP_BSR = "h34".U(6.W)   // (Bra) uncond. branch to subroutine (PC relative)
  val OP_JMP = "h1a".U(6.W)   // (Mbr) jump register indirect

  // Misc operation opcodes
  val OP_MISC = "h18".U(6.W)  // (Mfc) miscellaneous instruction opcodes

  // Integer arithmetic function codes (OP_INTA opcode)
  val INTA_ADDL = "h00".U(7.W)   // add longword
  val INTA_ADDLV = "h40".U(7.W)  // add longword (check overflow)
  val INTA_S4ADDL = "h02".U(7.W) // scaled_4 add longword
  val INTA_S8ADDL = "h12".U(7.W) // scaled_8 add longword
  val INTA_ADDQ = "h20".U(7.W)   // add quadword
  val INTA_ADDQV = "h60".U(7.W)  // add quadword (check overflow)
  val INTA_S4ADDQ = "h22".U(7.W) // scaled_4 add quadword
  val INTA_S8ADDQ = "h32".U(7.W) // scaled_8 add quadword
  val INTA_CMPBGE = "h0f".U(7.W) // compare byte
  val INTA_CMPEQ = "h2d".U(7.W)  // compare signed quadword equal
  val INTA_CMPLT = "h4d".U(7.W)  // compare signed quadword less than
  val INTA_CMPLE = "h6d".U(7.W)  // compare signed quadword less than or equal
  val INTA_CMPULT = "h1d".U(7.W) // compare unsigned quadword less than
  val INTA_CMPULE = "h3d".U(7.W) // compare unsigned quadword less than or equal
  val INTA_SUBL = "h09".U(7.W)   // subtract longword
  val INTA_SUBLV = "h49".U(7.W)  // subtract longword (check overflow)
  val INTA_S4SUBL = "h0b".U(7.W) // scaled_4 subtract longword
  val INTA_S8SUBL = "h1b".U(7.W) // scaled_8 subtract longword
  val INTA_SUBQ = "h29".U(7.W)   // subtract quadword
  val INTA_SUBQV = "h69".U(7.W)  // subtract quadword (check overflow)
  val INTA_S4SUBQ = "h2b".U(7.W) // scaled_4 subtract quadword
  val INTA_S8SUBQ = "h3b".U(7.W) // scaled_8 subtract quadword

  // Integer logical function codes (OP_INTL opcode)
  val INTL_AND = "h00".U(7.W)    // and
  val INTL_BIC = "h08".U(7.W)    // and not
  val INTL_BIS = "h20".U(7.W)    // or
  val INTL_EQU = "h48".U(7.W)    // xnor
  val INTL_ORNOT = "h28".U(7.W)  // or not
  val INTL_XOR = "h40".U(7.W)    // xor
  val INTL_CMOVEQ = "h24".U(7.W) // cmove if reg equal to zero
  val INTL_CMOVGE = "h46".U(7.W) // cmove if reg greater than or equal to zero
  val INTL_CMOVGT = "h66".U(7.W) // cmove if reg greater than zero
  val INTL_CMOVLBC = "h16".U(7.W) // cmove low bit clear
  val INTL_CMOVLBS = "h14".U(7.W) // cmove low bit set
  val INTL_CMOVLE = "h64".U(7.W) // cmove if reg less than or equal to zero
  val INTL_CMOVLT = "h44".U(7.W) // cmove if reg less than zero
  val INTL_CMOVNE = "h26".U(7.W) // cmove if reg not equal to zero
  val INTL_AMASK = "h61".U(7.W)  // ??? found in alpha/inst.h
  val INTL_IMPLVER = "h6c".U(7.W) // ??? found in alpha/inst.h

  // Integer shift function codes (OP_INTS opcode)
  val INTS_EXTBL = "h06".U(7.W)  // extract byte low
  val INTS_EXTWL = "h16".U(7.W)  // extract word low
  val INTS_EXTLL = "h26".U(7.W)  // extract longword low
  val INTS_EXTQL = "h36".U(7.W)  // extract quadword low
  val INTS_EXTWH = "h5a".U(7.W)  // extract word high
  val INTS_EXTLH = "h6a".U(7.W)  // extract longword high
  val INTS_EXTQH = "h7a".U(7.W)  // extract quadword high
  val INTS_INSBL = "h0b".U(7.W)  // insert byte low
  val INTS_INSWL = "h1b".U(7.W)  // insert word low
  val INTS_INSLL = "h2b".U(7.W)  // insert longword low
  val INTS_INSQL = "h3b".U(7.W)  // insert quadword low
  val INTS_INSWH = "h57".U(7.W)  // insert word high
  val INTS_INSLH = "h67".U(7.W)  // insert longword high
  val INTS_INSQH = "h77".U(7.W)  // insert quadword high
  val INTS_MSKBL = "h02".U(7.W)  // mask byte low
  val INTS_MSKWL = "h12".U(7.W)  // mask word low
  val INTS_MSKLL = "h22".U(7.W)  // mask longword low
  val INTS_MSKQL = "h32".U(7.W)  // mask quadword low
  val INTS_MSKWH = "h52".U(7.W)  // mask word high
  val INTS_MSKLH = "h62".U(7.W)  // mask longword high
  val INTS_MSKQH = "h72".U(7.W)  // mask quadword high
  val INTS_ZAP = "h30".U(7.W)    // zero bytes
  val INTS_ZAPNOT = "h31".U(7.W) // zero bytes not
  val INTS_SLL = "h39".U(7.W)    // shift left logical
  val INTS_SRA = "h3c".U(7.W)    // shift right arithmetic
  val INTS_SRL = "h34".U(7.W)    // shift right logical

  // Integer multiply function codes (OP_INTM opcode)
  val INTM_MULL = "h00".U(7.W)   // multiply longword
  val INTM_MULLV = "h40".U(7.W)  // multiply longword (check overflow)
  val INTM_MULQ = "h20".U(7.W)   // multiply quadword
  val INTM_MULQV = "h60".U(7.W)  // multiply quadword (check overflow)
  val INTM_UMULH = "h30".U(7.W)  // unsigned quadword multiply high

  // Jump function codes (OP_JMP opcode)
  val JMP_JMP = 0.U(2.W)           // jump
  val JMP_JSR = 1.U(2.W)           // jump subroutine
  val JMP_JSR_COROUTINE = 3.U(2.W) // jump coroutine
  val JMP_RET = 2.U(2.W)           // return

  // Miscellaneous function codes (OP_MISC opcode)
  val MISC_MB = "h4000".U(16.W)    // memory barrier
  val MISC_WMB = "h4400".U(16.W)   // write memory barrier
  val MISC_EXCB = "h0400".U(16.W)  // exception barrier
  val MISC_TRAPB = "h0000".U(16.W) // trap barrier
  val MISC_RC = "he000".U(16.W)    // read and clear
  val MISC_RS = "hf000".U(16.W)    // read and set
  val MISC_SEXTB = "h0000".U(16.W) // ??? found in alpha/inst.h
  val MISC_SEXTW = "h0001".U(16.W) // ??? found in alpha/inst.h
  val MISC_FETCH = "h8000".U(16.W) // prefetch data
  val MISC_FETCH_M = "ha000".U(16.W) // prefetch data, modify intent
  val MISC_RPCC = "hc000".U(16.W)  // read process cycle counter
}