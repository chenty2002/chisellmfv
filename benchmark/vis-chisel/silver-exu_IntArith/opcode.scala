package llmverify

import chisel3._

/**
 * Opcode definitions
 * Converted from opcode.v
 */
object opcode {
  // Helper function to create UInt from hex
  def h(hex: String): UInt = {
    val value = Integer.parseInt(hex, 16)
    value.U
  }

  // Integer operation opcodes
  val OP_INTA = h("10")  // integer arithmetic operations
  val OP_INTL = h("11")  // integer logical operations
  val OP_INTS = h("12")  // integer shift operations
  val OP_INTM = h("13")  // integer multiply operations

  // Memory operation opcodes
  val OP_LDA = h("08")   // load address
  val OP_LDAH = h("09")  // load address high
  val OP_LDL = h("28")   // load sign-extended longword
  val OP_LDQ = h("29")   // load quadword
  val OP_LDL_L = h("2a") // load sign-extended longword locked
  val OP_LDQ_L = h("2b") // load quadword locked
  val OP_LDQ_U = h("0b") // load quadword unaligned
  val OP_STL = h("2c")   // store longword
  val OP_STQ = h("2d")   // store quadword
  val OP_STL_C = h("2e") // store longword conditional
  val OP_STQ_C = h("2f") // store quadword conditional
  val OP_STQ_U = h("0f") // store quadword unaligned

  // Branch and jump operation opcodes
  val OP_BLBC = h("38")  // branch if reg low bit is clear
  val OP_BEQ = h("39")   // branch if reg equal to zero
  val OP_BLT = h("3a")   // branch if reg less than zero
  val OP_BLE = h("3b")   // branch if reg less than or equal to zero
  val OP_BLBS = h("3c")  // branch if reg low bit is set
  val OP_BNE = h("3d")   // branch if reg not equal to zero
  val OP_BGE = h("3e")   // branch if reg greater than or equal to zero
  val OP_BGT = h("3f")   // branch if reg greater than zero
  val OP_BR = h("30")    // uncond. branch (PC relative)
  val OP_BSR = h("34")   // uncond. branch to subroutine (PC relative)
  val OP_JMP = h("1a")   // jump register indirect

  // Misc operation opcodes
  val OP_MISC = h("18")  // miscellaneous instruction opcodes

  // Integer arithmetic function codes (OP_INTA opcode)
  val INTA_ADDL = h("00")   // add longword
  val INTA_ADDLV = h("40")  // add longword (check overflow)
  val INTA_S4ADDL = h("02") // scaled_4 add longword
  val INTA_S8ADDL = h("12") // scaled_8 add longword
  val INTA_ADDQ = h("20")   // add quadword
  val INTA_ADDQV = h("60")  // add quadword (check overflow)
  val INTA_S4ADDQ = h("22") // scaled_4 add quadword
  val INTA_S8ADDQ = h("32") // scaled_8 add quadword
  val INTA_CMPBGE = h("0f") // compare byte
  val INTA_CMPEQ = h("2d")  // compare signed quadword equal
  val INTA_CMPLT = h("4d")  // compare signed quadword less than
  val INTA_CMPLE = h("6d")  // compare signed quadword less than or equal
  val INTA_CMPULT = h("1d") // compare unsigned quadword less than
  val INTA_CMPULE = h("3d") // compare unsigned quadword less than or equal
  val INTA_SUBL = h("09")   // subtract longword
  val INTA_SUBLV = h("49")  // subtract longword (check overflow)
  val INTA_S4SUBL = h("0b") // scaled_4 subtract longword
  val INTA_S8SUBL = h("1b") // scaled_8 subtract longword
  val INTA_SUBQ = h("29")   // subtract quadword
  val INTA_SUBQV = h("69")  // subtract quadword (check overflow)
  val INTA_S4SUBQ = h("2b") // scaled_4 subtract quadword
  val INTA_S8SUBQ = h("3b") // scaled_8 subtract quadword

  // Integer logical function codes (OP_INTL opcode)
  val INTL_AND = h("00")     // and
  val INTL_BIC = h("08")     // and not
  val INTL_BIS = h("20")     // or
  val INTL_EQU = h("48")     // xnor
  val INTL_ORNOT = h("28")   // or not
  val INTL_XOR = h("40")     // xor
  val INTL_CMOVEQ = h("24")  // cmove if reg equal to zero
  val INTL_CMOVGE = h("46")  // cmove if reg greater than or equal to zero
  val INTL_CMOVGT = h("66")  // cmove if reg greater than zero
  val INTL_CMOVLBC = h("16") // cmove low bit clear
  val INTL_CMOVLBS = h("14") // cmove low bit set
  val INTL_CMOVLE = h("64")  // cmove if reg less than or equal to zero
  val INTL_CMOVLT = h("44")  // cmove if reg less than zero
  val INTL_CMOVNE = h("26")  // cmove if reg not equal to zero
  val INTL_AMASK = h("61")   // ??? found in alpha/inst.h
  val INTL_IMPLVER = h("6c") // ??? found in alpha/inst.h

  // Integer shift function codes (OP_INTS opcode)
  val INTS_EXTBL = h("06")   // extract byte low
  val INTS_EXTWL = h("16")   // extract word low
  val INTS_EXTLL = h("26")   // extract longword low
  val INTS_EXTQL = h("36")   // extract quadword low
  val INTS_EXTWH = h("5a")   // extract word high
  val INTS_EXTLH = h("6a")   // extract longword high
  val INTS_EXTQH = h("7a")   // extract quadword high
  val INTS_INSBL = h("0b")   // insert byte low
  val INTS_INSWL = h("1b")   // insert word low
  val INTS_INSLL = h("2b")   // insert longword low
  val INTS_INSQL = h("3b")   // insert quadword low
  val INTS_INSWH = h("57")   // insert word high
  val INTS_INSLH = h("67")   // insert longword high
  val INTS_INSQH = h("77")   // insert quadword high
  val INTS_MSKBL = h("02")   // mask byte low
  val INTS_MSKWL = h("12")   // mask word low
  val INTS_MSKLL = h("22")   // mask longword low
  val INTS_MSKQL = h("32")   // mask quadword low
  val INTS_MSKWH = h("52")   // mask word high
  val INTS_MSKLH = h("62")   // mask longword high
  val INTS_MSKQH = h("72")   // mask quadword high
  val INTS_ZAP = h("30")     // zero bytes
  val INTS_ZAPNOT = h("31")  // zero bytes not
  val INTS_SLL = h("39")     // shift left logical
  val INTS_SRA = h("3c")     // shift right arithmetic
  val INTS_SRL = h("34")     // shift right logical

  // Integer multiply function codes (OP_INTM opcode)
  val INTM_MULL = h("00")    // multiply longword
  val INTM_MULLV = h("40")   // multiply longword (check overflow)
  val INTM_MULQ = h("20")    // multiply quadword
  val INTM_MULQV = h("60")   // multiply quadword (check overflow)
  val INTM_UMULH = h("30")   // unsigned quadword multiply high

  // Jump function codes (OP_JMP opcode)
  val JMP_JMP = h("0")       // jump
  val JMP_JSR = h("1")       // jump subroutine
  val JMP_JSR_COROUTINE = h("3") // jump coroutine
  val JMP_RET = h("2")       // return

  // Miscellaneous function codes (OP_MISC opcode)
  val MISC_MB = h("4000")    // memory barrier
  val MISC_WMB = h("4400")   // write memory barrier
  val MISC_EXCB = h("0400")  // exception barrier
  val MISC_TRAPB = h("0000") // trap barrier
  val MISC_RC = h("e000")    // read and clear
  val MISC_RS = h("f000")    // read and set
  val MISC_SEXTB = h("0000") // ??? found in alpha/inst.h
  val MISC_SEXTW = h("0001") // ??? found in alpha/inst.h
  val MISC_FETCH = h("8000") // prefetch data
  val MISC_FETCH_M = h("a000") // prefetch data, modify intent
  val MISC_RPCC = h("c000")   // read process cycle counter
}