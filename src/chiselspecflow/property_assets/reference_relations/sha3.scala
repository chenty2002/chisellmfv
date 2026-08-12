package chisellmfv.generated

import chisel3._
import chisel3.util._

private object Keccak512Reference {
  private val rotation = Seq(
    Seq(0, 36, 3, 41, 18),
    Seq(1, 44, 10, 45, 2),
    Seq(62, 6, 43, 15, 61),
    Seq(28, 55, 25, 21, 56),
    Seq(27, 20, 39, 8, 14),
  )
  private val roundConstants = Seq(
    "0000000000000001", "0000000000008082", "800000000000808a",
    "8000000080008000", "000000000000808b", "0000000080000001",
    "8000000080008081", "8000000000008009", "000000000000008a",
    "0000000000000088", "0000000080008009", "000000008000000a",
    "000000008000808b", "800000000000008b", "8000000000008089",
    "8000000000008003", "8000000000008002", "8000000000000080",
    "000000000000800a", "800000008000000a", "8000000080008081",
    "8000000000008080", "0000000080000001", "8000000080008008",
  ).map(value => BigInt(value, 16).U(64.W))

  private def rotateLeft(value: UInt, amount: Int): UInt =
    if (amount == 0) value else Cat(value(63 - amount, 0), value(63, 64 - amount))

  def permute(input: Seq[UInt]): Seq[UInt] =
    roundConstants.foldLeft(input) { (a, roundConstant) =>
      val c = Seq.tabulate(5)(x => (0 until 5).map(y => a(x + 5 * y)).reduce(_ ^ _))
      val d = Seq.tabulate(5)(x => c((x + 4) % 5) ^ rotateLeft(c((x + 1) % 5), 1))
      val b = Array.fill[UInt](25)(0.U(64.W))
      for (x <- 0 until 5; y <- 0 until 5)
        b(y + 5 * ((2 * x + 3 * y) % 5)) = rotateLeft(a(x + 5 * y) ^ d(x), rotation(x)(y))
      Seq.tabulate(25) { index =>
        val x = index % 5
        val y = index / 5
        val chi = b(index) ^ ((~b((x + 1) % 5 + 5 * y)) & b((x + 2) % 5 + 5 * y))
        if (index == 0) chi ^ roundConstant else chi
      }
    }

  def absorb(state: Seq[UInt], block: Seq[UInt]): Seq[UInt] = {
    val lanes = Seq.tabulate(9) { lane =>
      Cat((0 until 8).reverse.map(byte => block(8 * lane + byte)))
    }
    permute(Seq.tabulate(25)(lane => state(lane) ^ (if (lane < 9) lanes(lane) else 0.U)))
  }

  def digest(state: Seq[UInt]): UInt =
    Cat((0 until 64).map(index => state(index / 8)(8 * (index % 8) + 7, 8 * (index % 8))))
}

/** Independent multi-cycle legacy Keccak-512 reference relation. */
final class Sha3ReferenceMonitor extends Module {
  val in = IO(Input(UInt(32.W)))
  val in_ready = IO(Input(Bool()))
  val is_last = IO(Input(Bool()))
  val byte_num = IO(Input(UInt(2.W)))
  val buffer_full = IO(Input(Bool()))
  val out = IO(Input(UInt(512.W)))
  val out_ready = IO(Input(Bool()))

  val relation_ok = IO(Output(Bool()))
  val check_valid = IO(Output(Bool()))
  val activation = IO(Output(Bool()))
  val observer = IO(Output(Bool()))

  val sponge = RegInit(VecInit(Seq.fill(25)(0.U(64.W))))
  val block = Reg(Vec(72, UInt(8.W)))
  val byteCount = RegInit(0.U(7.W))
  val finalSeen = RegInit(false.B)
  val expectedDigest = RegInit(0.U(512.W))

  val accept = !reset.asBool && in_ready && !buffer_full && !finalSeen
  val wordBlock = Wire(Vec(72, UInt(8.W)))
  wordBlock := block
  wordBlock(byteCount) := in(31, 24)
  wordBlock(byteCount + 1.U) := in(23, 16)
  wordBlock(byteCount + 2.U) := in(15, 8)
  wordBlock(byteCount + 3.U) := in(7, 0)

  val finalLength = byteCount + byte_num
  val finalBlock = Wire(Vec(72, UInt(8.W)))
  for (index <- 0 until 72)
    finalBlock(index) := Mux(index.U < byteCount, block(index), 0.U)
  when(byte_num > 0.U) { finalBlock(byteCount) := in(31, 24) }
  when(byte_num > 1.U) { finalBlock(byteCount + 1.U) := in(23, 16) }
  when(byte_num > 2.U) { finalBlock(byteCount + 2.U) := in(15, 8) }
  finalBlock(finalLength) := Mux(finalLength === 71.U, "h81".U, "h01".U)
  when(finalLength =/= 71.U) { finalBlock(71) := "h80".U }

  val absorbedWord = Keccak512Reference.absorb(sponge.toSeq, wordBlock.toSeq)
  val absorbedFinal = Keccak512Reference.absorb(sponge.toSeq, finalBlock.toSeq)

  when(reset.asBool) {
    sponge.foreach(_ := 0.U)
    byteCount := 0.U
    finalSeen := false.B
    expectedDigest := 0.U
  }.elsewhen(accept) {
    when(is_last) {
      sponge := VecInit(absorbedFinal)
      expectedDigest := Keccak512Reference.digest(absorbedFinal)
      finalSeen := true.B
    }.otherwise {
      block := wordBlock
      when(byteCount === 68.U) {
        sponge := VecInit(absorbedWord)
        byteCount := 0.U
      }.otherwise {
        byteCount := byteCount + 4.U
      }
    }
  }

  check_valid := finalSeen && out_ready
  relation_ok := out === expectedDigest
  activation := finalSeen
  observer := check_valid
}
