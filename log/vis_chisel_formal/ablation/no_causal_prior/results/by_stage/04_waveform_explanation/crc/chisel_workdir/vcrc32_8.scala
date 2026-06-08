package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

class vcrc32_8 extends Module with Formal {
  val io = IO(new Bundle {
    val clken = Input(Bool())
    val reset = Input(Bool())
    val load = Input(Bool())
    val compute = Input(Bool())
    val data_in = Input(UInt(8.W))
    val data_out = Output(UInt(8.W))
    val crc_ok = Output(Bool())
    val crc = Output(UInt(32.W))
  })

  // Constants
  val CRC_INITIAL_VALUE = "hFFFFFFFF".U(32.W)
  val CRC_REMAINDER = "hC704DD7B".U(32.W)

  // CRC register
  val crcReg = RegInit(CRC_INITIAL_VALUE)
  
  // Parallel CRC function
  def parallel_crc(c: UInt, d: UInt): UInt = {
    val x = c(31, 24) ^ d
    
    // Calculate each bit of the parallel CRC
    // Note: x[31] maps to x(7), x[30] maps to x(6), x[29] maps to x(5), etc.
    val bit31 = x(5) ^ c(23)   // x[29] ^ c[23]
    val bit30 = x(4) ^ x(7) ^ c(22)   // x[28] ^ x[31] ^ c[22]
    val bit29 = x(3) ^ x(6) ^ x(7) ^ c(21)   // x[27] ^ x[30] ^ x[31] ^ c[21]
    val bit28 = x(2) ^ x(5) ^ x(6) ^ c(20)   // x[26] ^ x[29] ^ x[30] ^ c[20]
    val bit27 = x(7) ^ x(1) ^ x(4) ^ x(5) ^ c(19)   // x[31] ^ x[25] ^ x[28] ^ x[29] ^ c[19]
    val bit26 = x(6) ^ x(0) ^ x(3) ^ x(4) ^ c(18)   // x[30] ^ x[24] ^ x[27] ^ x[28] ^ c[18]
    val bit25 = x(2) ^ x(3) ^ c(17)   // x[26] ^ x[27] ^ c[17]
    val bit24 = x(7) ^ x(1) ^ x(2) ^ c(16)   // x[31] ^ x[25] ^ x[26] ^ c[16]
    val bit23 = x(6) ^ x(0) ^ x(1) ^ c(15)   // x[30] ^ x[24] ^ x[25] ^ c[15]
    val bit22 = x(0) ^ c(14)   // x[24] ^ c[14]
    val bit21 = x(5) ^ c(13)   // x[29] ^ c[13]
    val bit20 = x(4) ^ c(12)   // x[28] ^ c[12]
    val bit19 = x(3) ^ x(7) ^ c(11)   // x[27] ^ x[31] ^ c[11]
    val bit18 = x(2) ^ x(6) ^ x(7) ^ c(10)   // x[26] ^ x[30] ^ x[31] ^ c[10]
    val bit17 = x(1) ^ x(5) ^ x(6) ^ c(9)   // x[25] ^ x[29] ^ x[30] ^ c[9]
    val bit16 = x(0) ^ x(4) ^ x(5) ^ c(8)   // x[24] ^ x[28] ^ x[29] ^ c[8]
    val bit15 = x(3) ^ x(4) ^ x(5) ^ x(7) ^ c(7)   // x[27] ^ x[28] ^ x[29] ^ x[31] ^ c[7]
    val bit14 = x(2) ^ x(3) ^ x(4) ^ x(6) ^ x(7) ^ c(6)   // x[26] ^ x[27] ^ x[28] ^ x[30] ^ x[31] ^ c[6]
    val bit13 = x(7) ^ x(1) ^ x(2) ^ x(3) ^ x(5) ^ x(6) ^ c(5)   // x[31] ^ x[25] ^ x[26] ^ x[27] ^ x[29] ^ x[30] ^ c[5]
    val bit12 = x(6) ^ x(0) ^ x(1) ^ x(2) ^ x(4) ^ x(5) ^ c(4)   // x[30] ^ x[24] ^ x[25] ^ x[26] ^ x[28] ^ x[29] ^ c[4]
    val bit11 = x(0) ^ x(1) ^ x(3) ^ x(4) ^ c(3)   // x[24] ^ x[25] ^ x[27] ^ x[28] ^ c[3]
    val bit10 = x(0) ^ x(2) ^ x(3) ^ x(5) ^ c(2)   // x[24] ^ x[26] ^ x[27] ^ x[29] ^ c[2]
    val bit9  = x(1) ^ x(2) ^ x(4) ^ x(5) ^ c(1)   // x[25] ^ x[26] ^ x[28] ^ x[29] ^ c[1]
    val bit8  = x(0) ^ x(1) ^ x(3) ^ x(4) ^ c(0)   // x[24] ^ x[25] ^ x[27] ^ x[28] ^ c[0]
    val bit7  = x(0) ^ x(2) ^ x(3) ^ x(5) ^ x(7)   // x[24] ^ x[26] ^ x[27] ^ x[29] ^ x[31]
    val bit6  = x(1) ^ x(2) ^ x(4) ^ x(5) ^ x(6) ^ x(7)   // x[25] ^ x[26] ^ x[28] ^ x[29] ^ x[30] ^ x[31]
    val bit5  = x(7) ^ x(6) ^ x(5) ^ x(4) ^ x(3) ^ x(1) ^ x(0)   // x[31] ^ x[30] ^ x[29] ^ x[28] ^ x[27] ^ x[25] ^ x[24]
    val bit4  = x(6) ^ x(4) ^ x(3) ^ x(2) ^ x(0)   // x[30] ^ x[28] ^ x[27] ^ x[26] ^ x[24]
    val bit3  = x(7) ^ x(1) ^ x(2) ^ x(3)   // x[31] ^ x[25] ^ x[26] ^ x[27]
    val bit2  = x(6) ^ x(0) ^ x(7) ^ x(1) ^ x(2)   // x[30] ^ x[24] ^ x[31] ^ x[25] ^ x[26]
    val bit1  = x(6) ^ x(0) ^ x(7) ^ x(1)   // x[30] ^ x[24] ^ x[31] ^ x[25]
    val bit0  = x(6) ^ x(0)   // x[30] ^ x[24]
    
    Cat(bit31, bit30, bit29, bit28, bit27, bit26, bit25, bit24,
        bit23, bit22, bit21, bit20, bit19, bit18, bit17, bit16,
        bit15, bit14, bit13, bit12, bit11, bit10, bit9, bit8,
        bit7, bit6, bit5, bit4, bit3, bit2, bit1, bit0)
  }
  
  // Next CRC value calculation
  val newCrc = Wire(UInt(32.W))
  
  when(io.reset) {
    newCrc := CRC_INITIAL_VALUE
  }.elsewhen(io.load) {
    newCrc := Cat(crcReg(23, 0), io.data_in)
  }.elsewhen(io.compute) {
    newCrc := parallel_crc(crcReg, io.data_in)
  }.otherwise {
    newCrc := crcReg
  }
  
  // Register update with clock enable
  when(io.clken) {
    crcReg := newCrc
  }
  
  // Output assignments
  io.data_out := ~crcReg(31, 24)
  io.crc_ok := crcReg === CRC_REMAINDER
  io.crc := crcReg

  // ---------------------------------------------------------------------------
  // Formal Verification Assertions
  // ---------------------------------------------------------------------------

  // Assertion 1: Mutex on control signals
  // At most one of reset, load, compute may be active at a time.
  // Simultaneous assertion is a design error in the driving logic.
  fvAssert(
    PopCount(Seq(io.reset, io.load, io.compute)) <= 1.U,
    "control_mutex"
  )

  // Assertion 2: Register stability when clock enable is low
  // crcReg must not change when clken is de-asserted.
  assertStableWhen(!io.clken, crcReg, "crcReg_stable_when_clken_low")

  // Assertion 3: Reset behavior
  // When reset && clken fire, crcReg must equal CRC_INITIAL_VALUE in the next cycle.
  assertImpliesDelay(
    io.reset && io.clken,
    crcReg === CRC_INITIAL_VALUE,
    1,
    "reset_sets_initial_value_next_cycle"
  )

  // Assertion 4: Load behavior
  // When load && clken fire, crcReg must equal Cat(old_crcReg(23,0), data_in)
  // in the next cycle.  We save the old value via a dedicated register.
  val crcRegPrev = RegNext(crcReg)
  val loadFired = io.load && io.clken
  assertImpliesDelay(
    loadFired,
    crcReg === Cat(crcRegPrev(23, 0), io.data_in),
    1,
    "load_updates_crc_correctly"
  )

  // Assertion 5: Compute bounded forward progress
  // Once compute && clken fires, crcReg changes from its previous value within
  // 1 cycle (i.e. the next cycle after compute).  This guards against a stuck
  // compute path where newCrc equals crcReg by accident.
  val computeFired = io.compute && io.clken
  assertImpliesDelay(
    computeFired,
    crcReg =/= crcRegPrev,
    1,
    "compute_changes_crc"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new vcrc32_8(), args)
}
