package llmverify

import chisel3._

object Opcode {
  //--------------------------------------------------------------------------
  // integer operation opcodes
  //--------------------------------------------------------------------------
  val OP_INTA = "h10".U  // (Opr) integer arithmetic operations
  val OP_INTL = "h11".U  // (Opr) integer logical operations
  val OP_INTS = "h12".U  // (Opr) integer shift operations
  val OP_INTM = "h13".U  // (Opr) integer multiply operations

  //--------------------------------------------------------------------------
  // memory operation opcodes
  //--------------------------------------------------------------------------
  //--- LD_L / ST_C --- not implemented ---
  val OP_LDA = "h08".U  // (Mem) load address
  val OP_LDAH = "h09".U  // (Mem) load address high
  val OP_LDL = "h28".U  // (Mem) load sign-extended longword
  val OP_LDQ = "h29".U  // (Mem) load quadword
  val OP_LDL_L = "h2a".U  // (Mem) load sign-extended longword locked
  val OP_LDQ_L = "h2b".U  // (Mem) load quadword locked
  val OP_LDQ_U = "h0b".U  // (Mem) load quadword unaligned
  val OP_STL = "h2c".U  // (Mem) store longword
  val OP_STQ = "h2d".U  // (Mem) store quadword
  val OP_STL_C = "h2e".U  // (Mem) store longword conditional
  val OP_STQ_C = "h2f".U  // (Mem) store quadword conditional
  val OP_STQ_U = "h0f".U  // (Mem) store quadword unaligned

  //--------------------------------------------------------------------------
  // branch and jump operation opcodes
  //--------------------------------------------------------------------------
  val OP_BLBC = "h38".U  // (Bra) branch if reg low bit is clear
  val OP_BEQ = "h39".U  // (Bra) branch if reg equal to zero
  val OP_BLT = "h3a".U  // (Bra) branch if reg less than zero
  val OP_BLE = "h3b".U  // (Bra) branch if reg less than or equal to zero
  val OP_BLBS = "h3c".U  // (Bra) branch if reg low bit is set
  val OP_BNE = "h3d".U  // (Bra) branch if reg not equal to zero
  val OP_BGE = "h3e".U  // (Bra) branch if greater than or equal to zero
  val OP_BGT = "h3f".U  // (Bra) branch if greater than zero
  val OP_BR = "h30".U  // (Bra) uncond. branch (PC relative)
  val OP_BSR = "h34".U  // (Bra) uncond. branch to subroutine (PC relative)
  val OP_JMP = "h1a".U  // (Mbr) jump register indirect

  //--------------------------------------------------------------------------
  // misc operation opcodes
  //--------------------------------------------------------------------------
  val OP_MISC = "h18".U  // (Mfc) miscellaneous instruction opcodes

  //--------------------------------------------------------------------------
  // integer arithmetic function codes (OP_INTA opcode)
  //--------------------------------------------------------------------------
  val INTA_ADDL = "h00".U  // add longword
  val INTA_ADDLV = "h40".U  // add longword (check overflow)
  val INTA_S4ADDL = "h02".U  // scaled_4 add longword
  val INTA_S8ADDL = "h12".U  // scaled_8 add longword
  val INTA_ADDQ = "h20".U  // add quadword
  val INTA_ADDQV = "h60".U  // add quadword (check overflow)
  val INTA_S4ADDQ = "h22".U  // scaled_4 add quadword
  val INTA_S8ADDQ = "h32".U  // scaled_8 add quadword
  val INTA_CMPBGE = "h0f".U  // compare byte
  val INTA_CMPEQ = "h2d".U  // compare signed quadword equal
  val INTA_CMPLT = "h4d".U  // compare signed quadword less than
  val INTA_CMPLE = "h6d".U  // compare signed quadword less than or equal
  val INTA_CMPULT = "h1d".U  // compare unsigned quadword less than
  val INTA_CMPULE = "h3d".U  // compare unsigned quadword less than or equal
  val INTA_SUBL = "h09".U  // subtract longword
  val INTA_SUBLV = "h49".U  // subtract longword (check overflow)
  val INTA_S4SUBL = "h0b".U  // scaled_4 subtract longword
  val INTA_S8SUBL = "h1b".U  // scaled_8 subtract longword
  val INTA_SUBQ = "h29".U  // subtract quadword
  val INTA_SUBQV = "h69".U  // subtract quadword (check overflow)
  val INTA_S4SUBQ = "h2b".U  // scaled_4 subtract quadword
  val INTA_S8SUBQ = "h3b".U  // scaled_8 subtract quadword

  //--------------------------------------------------------------------------
  // integer logical function codes (OP_INTL opcode)
  //--------------------------------------------------------------------------
  val INTL_AND = "h00".U  // and
  val INTL_BIC = "h08".U  // and not
  val INTL_BIS = "h20".U  // or
  val INTL_EQU = "h48".U  // xnor
  val INTL_ORNOT = "h28".U  // or not
  val INTL_XOR = "h40".U  // xor
  val INTL_CMOVEQ = "h24".U  // cmove if reg equal to zero
  val INTL_CMOVGE = "h46".U  // cmove if reg greater than or equal to zero
  val INTL_CMOVGT = "h66".U  // cmove if reg greater than zero
  val INTL_CMOVLBC = "h16".U  // cmove low bit clear
  val INTL_CMOVLBS = "h14".U  // cmove low bit set
  val INTL_CMOVLE = "h64".U  // cmove if reg less than or equal to zero
  val INTL_CMOVLT = "h44".U  // cmove if reg less than zero
  val INTL_CMOVNE = "h26".U  // cmove if reg not equal to zero
  val INTL_AMASK = "h61".U  // ??? found in alpha/inst.h
  val INTL_IMPLVER = "h6c".U  // ??? found in alpha/inst.h

  //--------------------------------------------------------------------------
  // integer shift function codes (OP_INTS opcode)
  //--------------------------------------------------------------------------
  val INTS_EXTBL = "h06".U  // extract byte low
  val INTS_EXTWL = "h16".U  // extract word low
  val INTS_EXTLL = "h26".U  // extract longword low
  val INTS_EXTQL = "h36".U  // extract quadword low
  val INTS_EXTWH = "h5a".U  // extract word high
  val INTS_EXTLH = "h6a".U  // extract longword high
  val INTS_EXTQH = "h7a".U  // extract quadword high
  val INTS_INSBL = "h0b".U  // insert byte low
  val INTS_INSWL = "h1b".U  // insert word low
  val INTS_INSLL = "h2b".U  // insert longword low
  val INTS_INSQL = "h3b".U  // insert quadword low
  val INTS_INSWH = "h57".U  // insert word high
  val INTS_INSLH = "h67".U  // insert longword high
  val INTS_INSQH = "h77".U  // insert quadword high
  val INTS_MSKBL = "h02".U  // mask byte low
  val INTS_MSKWL = "h12".U  // mask word low
  val INTS_MSKLL = "h22".U  // mask longword low
  val INTS_MSKQL = "h32".U  // mask quadword low
  val INTS_MSKWH = "h52".U  // mask word high
  val INTS_MSKLH = "h62".U  // mask longword high
  val INTS_MSKQH = "h72".U  // mask quadword high
  val INTS_ZAP = "h30".U  // zero bytes
  val INTS_ZAPNOT = "h31".U  // zero bytes not
  val INTS_SLL = "h39".U  // shift left logical
  val INTS_SRA = "h3c".U  // shift right arithmetic
  val INTS_SRL = "h34".U  // shift right logical

  //--------------------------------------------------------------------------
  // integer multiply function codes (OP_INTM opcode)
  //--------------------------------------------------------------------------
  val INTM_MULL = "h00".U  // multiply longword
  val INTM_MULLV = "h40".U  // multiply longword (check overflow)
  val INTM_MULQ = "h20".U  // multiply quadword
  val INTM_MULQV = "h60".U  // multiply quadword (check overflow)
  val INTM_UMULH = "h30".U  // unsigned quadword multiply high

  //--------------------------------------------------------------------------
  // jump function codes (OP_JMP opcode)
  //--------------------------------------------------------------------------
  val JMP_JMP = "h0".U  // jump
  val JMP_JSR = "h1".U  // jump subroutine
  val JMP_JSR_COROUTINE = "h3".U  // jump coroutine
  val JMP_RET = "h2".U  // return

  //--------------------------------------------------------------------------
  // miscellaneous function codes (OP_MISC opcode)
  //--------------------------------------------------------------------------
  //--- not implemented ---
  val MISC_MB = "h4000".U  // memory barrier
  val MISC_WMB = "h4400".U  // write memory barrier
  val MISC_EXCB = "h0400".U  // exception barrier
  val MISC_TRAPB = "h0000".U  // trap barrier
  val MISC_RC = "he000".U  // read and clear
  val MISC_RS = "hf000".U  // read and set
  val MISC_SEXTB = "h0000".U  // ??? found in alpha/inst.h
  val MISC_SEXTW = "h0001".U  // ??? found in alpha/inst.h
  //--- correct implementation optional ---
  val MISC_FETCH = "h8000".U  // prefetch data
  val MISC_FETCH_M = "ha000".U  // prefetch data, modify intent
  val MISC_RPCC = "hc000".U  // read process cycle counter
}