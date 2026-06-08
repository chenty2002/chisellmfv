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
    val bit31 = x(5) ^ c(23)
    val bit30 = x(4) ^ x(7) ^ c(22)
    val bit29 = x(3) ^ x(6) ^ x(7) ^ c(21)
    val bit28 = x(2) ^ x(5) ^ x(6) ^ c(20)
    val bit27 = x(7) ^ x(1) ^ x(4) ^ x(5) ^ c(19)
    val bit26 = x(6) ^ x(0) ^ x(3) ^ x(4) ^ c(18)
    val bit25 = x(2) ^ x(3) ^ c(17)
    val bit24 = x(7) ^ x(1) ^ x(2) ^ c(16)
    val bit23 = x(6) ^ x(0) ^ x(1) ^ c(15)
    val bit22 = x(0) ^ c(14)
    val bit21 = x(5) ^ c(13)
    val bit20 = x(4) ^ c(12)
    val bit19 = x(3) ^ x(7) ^ c(11)
    val bit18 = x(2) ^ x(6) ^ x(7) ^ c(10)
    val bit17 = x(1) ^ x(5) ^ x(6) ^ c(9)
    val bit16 = x(0) ^ x(4) ^ x(5) ^ c(8)
    val bit15 = x(3) ^ x(4) ^ x(5) ^ x(7) ^ c(7)
    val bit14 = x(2) ^ x(3) ^ x(4) ^ x(6) ^ x(7) ^ c(6)
    val bit13 = x(7) ^ x(1) ^ x(2) ^ x(3) ^ x(5) ^ x(6) ^ c(5)
    val bit12 = x(6) ^ x(0) ^ x(1) ^ x(2) ^ x(4) ^ x(5) ^ c(4)
    val bit11 = x(0) ^ x(1) ^ x(3) ^ x(4) ^ c(3)
    val bit10 = x(0) ^ x(2) ^ x(3) ^ x(5) ^ c(2)
    val bit9  = x(1) ^ x(2) ^ x(4) ^ x(5) ^ c(1)
    val bit8  = x(0) ^ x(1) ^ x(3) ^ x(4) ^ c(0)
    val bit7  = x(0) ^ x(2) ^ x(3) ^ x(5) ^ x(7)
    val bit6  = x(1) ^ x(2) ^ x(4) ^ x(5) ^ x(6) ^ x(7)
    val bit5  = x(7) ^ x(6) ^ x(5) ^ x(4) ^ x(3) ^ x(1) ^ x(0)
    val bit4  = x(6) ^ x(4) ^ x(3) ^ x(2) ^ x(0)
    val bit3  = x(7) ^ x(1) ^ x(2) ^ x(3)
    val bit2  = x(6) ^ x(0) ^ x(7) ^ x(1) ^ x(2)
    val bit1  = x(6) ^ x(0) ^ x(7) ^ x(1)
    val bit0  = x(6) ^ x(0)
    
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

  // ========== FORMAL ASSERTIONS ==========

  // Safety 1: Mutex — reset, load, and compute are mutually exclusive.
  // The priority encoding means simultaneous assertions silently ignore lower‑priority requests,
  // which is almost certainly a bug.
  assertMutex(Seq(io.reset, io.load, io.compute), "ctrl_signals_mutex")

  // Safety 2: CRC register stability when clock enable is de‑asserted.
  // Under gated clocks the register must hold its value.
  assertStableWhen(!io.clken, crcReg, "crc_stable_when_clken_low")

  // Safety 3: Combinational output consistency.
  // io.data_out must always be the bitwise inverse of the high CRC byte.
  fvAssert(io.data_out === ~crcReg(31, 24), "data_out_eq_inv_crc_high_byte")

  // Safety 4: io.crc_ok must match the comparator result.
  fvAssert(io.crc_ok === (crcReg === CRC_REMAINDER), "crc_ok_eq_comparator")

  // Safety 5: Reset correctness.
  // When reset fires with clken high, the CRC register must become the initial value next cycle.
  assertNextStepWhen(io.reset && io.clken, crcReg === CRC_INITIAL_VALUE, "reset_takes_effect")

  // Safety 6: Load operation correctness.
  // When load fires with clken high, crcReg must become {old_crc[23:0], data_in} next cycle.
  val prevCrc   = RegNext(crcReg)
  val prevData  = RegNext(io.data_in)
  assertNextStepWhen(io.load && io.clken,
    crcReg === Cat(prevCrc(23, 0), prevData),
    "load_shifts_in_data")

  // Safety 7: Compute operation correctness.
  // When compute fires with clken high, crcReg must become parallel_crc(old_crc, data_in) next cycle.
  assertNextStepWhen(io.compute && io.clken,
    crcReg === parallel_crc(prevCrc, prevData),
    "compute_crc_updated")

  // Liveness 8: Bounded progress for compute.
  // When compute is asserted with clken high, the CRC must change within 1-2 cycles.
  astRelaxedLiveness(io.compute && io.clken, crcReg =/= prevCrc, 2,
    "compute_progress_crc_changes")

  // Liveness 9: Bounded progress toward crc_ok.
  // After a load or compute with clken, crc_ok should eventually become true.
  // We bound it with a generous window to let the CRC accumulate.
  astRelaxedLiveness((io.load || io.compute) && io.clken, io.crc_ok, 10,
    "crc_eventually_ok")
}

object VerilogGenerator extends App {
  emitVerilog(new vcrc32_8(), args)
}
