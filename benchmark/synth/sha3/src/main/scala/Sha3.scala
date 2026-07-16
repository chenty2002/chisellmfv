package withw.sha3

import chisel3._
import chisel3.util.{Cat, Fill, HasBlackBoxInline}
import _root_.circt.stage.ChiselStage

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** One complete numbered Wit-HW SHA3 case.
  *
  * Each flag is a literal transcription of one source mutation. The full
  * keccak design cone is emitted for every variant.
  */
final case class Sha3Variant(
    sourceStem: String,
    latchOutReady: Boolean = false,
    calcStopBit: Int = 22,
    updateOutputOnlyOnAccept: Boolean = false
)

object Sha3Variants {
  val all: Seq[Sha3Variant] = Seq(
    Sha3Variant("sha3"),
    // sha3-1: keccak_buggy_1.v changes out_ready into an incomplete
    // combinational assignment, hence a level-sensitive latch.
    Sha3Variant("sha3_buggy_1", latchOutReady = true),
    // sha3-2: f_permutation_buggy_2.v clears calc from i[21], one round early.
    Sha3Variant("sha3_buggy_2", calcStopBit = 21),
    // sha3-3: f_permutation_buggy_3.v updates out only when accepting input.
    Sha3Variant("sha3_buggy_3", updateOutputOnlyOnAccept = true)
  )
}

private object Sha3Bits {
  def reverseBytesPerWord(value: UInt, words: Int): UInt = {
    val resultBytes = for {
      word <- (words - 1) to 0 by -1
      byte <- 0 until 8
    } yield value(word * 64 + byte * 8 + 7, word * 64 + byte * 8)
    Cat(resultBytes)
  }
}

final class Padder1IO extends Bundle {
  val in = Input(UInt(32.W))
  val byte_num = Input(UInt(2.W))
  val out = Output(UInt(32.W))
}

/** padder1.v, including its no-default latch behavior for X/Z selectors. */
final class Padder1 extends BlackBox with HasBlackBoxInline {
  override def desiredName: String = "padder1"

  val io = IO(new Padder1IO)
  setInline(
    "padder1.sv",
    """module padder1(
      |  input [31:0] in,
      |  input [1:0] byte_num,
      |  output reg [31:0] out
      |);
      |  always @(*) begin
      |    case (byte_num)
      |      2'd0: out = 32'h01000000;
      |      2'd1: out = {in[31:24], 24'h010000};
      |      2'd2: out = {in[31:16], 16'h0100};
      |      2'd3: out = {in[31:8], 8'h01};
      |    endcase
      |  end
      |endmodule
      |""".stripMargin
  )
}

/** padder.v, including its synchronous reset and old-state update priority. */
final class Padder extends Module {
  override def desiredName: String = "padder"

  val in = IO(Input(UInt(32.W)))
  val in_ready = IO(Input(Bool()))
  val is_last = IO(Input(Bool()))
  val byte_num = IO(Input(UInt(2.W)))
  val buffer_full = IO(Output(Bool()))
  val out = IO(Output(UInt(576.W)))
  val out_ready = IO(Output(Bool()))
  val f_ack = IO(Input(Bool()))

  val outReg = Reg(UInt(576.W))
  val length = Reg(UInt(18.W))
  val state = Reg(Bool())
  val done = Reg(Bool())

  buffer_full := length(17)
  out_ready := buffer_full

  val accept = !state && in_ready && !buffer_full
  val update = (accept || (state && !buffer_full)) && !done

  val padding = Module(new Padder1)
  padding.io.in := in
  padding.io.byte_num := byte_num

  val endBit = (length(16).asUInt << 7).asUInt
  val finalWord = padding.io.out | endBit
  val shiftedWord = Mux(state, endBit, Mux(!is_last, in, finalWord))

  when(reset.asBool) {
    outReg := 0.U
  }.elsewhen(update) {
    outReg := Cat(outReg(543, 0), shiftedWord)
  }

  when(reset.asBool) {
    length := 0.U
  }.elsewhen(f_ack || update) {
    length := Cat(length(16, 0), true.B) & Fill(18, !f_ack)
  }

  when(reset.asBool) {
    state := false.B
  }.elsewhen(is_last) {
    state := true.B
  }

  when(reset.asBool) {
    done := false.B
  }.elsewhen(state && out_ready) {
    done := true.B
  }

  out := outReg
}

/** rconst.v. Only the seven Keccak round-constant bit positions can be set. */
final class RoundConstant extends RawModule {
  override def desiredName: String = "rconst"

  val i = IO(Input(UInt(24.W)))
  val rc = IO(Output(UInt(64.W)))

  private val taps = Map(
    0 -> Seq(0, 4, 5, 6, 7, 10, 12, 13, 14, 15, 20, 22),
    1 -> Seq(1, 2, 4, 8, 11, 12, 13, 15, 16, 18, 19),
    3 -> Seq(2, 4, 7, 8, 9, 10, 11, 12, 13, 14, 18, 19, 23),
    7 -> Seq(1, 2, 4, 6, 8, 9, 12, 13, 14, 17, 20, 21),
    15 -> Seq(1, 2, 3, 4, 6, 7, 10, 12, 14, 15, 16, 18, 20, 21, 23),
    31 -> Seq(3, 5, 6, 10, 11, 12, 19, 20, 22, 23),
    63 -> Seq(2, 3, 6, 7, 13, 14, 15, 16, 17, 19, 20, 21, 23)
  )

  private def bit(position: Int): Bool =
    taps.get(position).map(_.map(i(_)).reduce(_ || _)).getOrElse(false.B)

  rc := Cat((63 to 0 by -1).map(bit))
}

/** round.v: one fully combinational Keccak-f[1600] round. */
final class KeccakRound extends RawModule {
  override def desiredName: String = "round"

  val in = IO(Input(UInt(1600.W)))
  val round_const = IO(Input(UInt(64.W)))
  val out = IO(Output(UInt(1600.W)))

  private def rotateUp(value: UInt, amount: Int): UInt =
    if (amount == 0) value else Cat(value(63 - amount, 0), value(63, 64 - amount))

  private val a = Seq.tabulate(5, 5) { (x, y) =>
    val high = 1599 - 64 * (5 * y + x)
    in(high, high - 63)
  }
  private val parity = Seq.tabulate(5)(x => (0 until 5).map(y => a(x)(y)).reduce(_ ^ _))
  private val theta = Seq.tabulate(5, 5) { (x, y) =>
    a(x)(y) ^ parity((x + 4) % 5) ^ rotateUp(parity((x + 1) % 5), 1)
  }

  private val rho = Seq(
    Seq(0, 36, 3, 41, 18),
    Seq(1, 44, 10, 45, 2),
    Seq(62, 6, 43, 15, 61),
    Seq(28, 55, 25, 21, 56),
    Seq(27, 20, 39, 8, 14)
  )
  private val rotated = Seq.tabulate(5, 5)((x, y) => rotateUp(theta(x)(y), rho(x)(y)))

  // Explicitly transcribed from the source's 25 pi assignments.
  private val piSource = Seq(
    Seq((0, 0), (3, 0), (1, 0), (4, 0), (2, 0)),
    Seq((1, 1), (4, 1), (2, 1), (0, 1), (3, 1)),
    Seq((2, 2), (0, 2), (3, 2), (1, 2), (4, 2)),
    Seq((3, 3), (1, 3), (4, 3), (2, 3), (0, 3)),
    Seq((4, 4), (2, 4), (0, 4), (3, 4), (1, 4))
  )
  private val pi = Seq.tabulate(5, 5) { (x, y) =>
    val (sourceX, sourceY) = piSource(x)(y)
    rotated(sourceX)(sourceY)
  }
  private val chi = Seq.tabulate(5, 5) { (x, y) =>
    pi(x)(y) ^ ((~pi((x + 1) % 5)(y)) & pi((x + 2) % 5)(y))
  }

  private val iotaMask = "h800000008000808b".U(64.W)
  private val lanes = for {
    y <- 0 until 5
    x <- 0 until 5
  } yield if (x == 0 && y == 0) chi(x)(y) ^ (round_const & iotaMask) else chi(x)(y)

  out := Cat(lanes)
}

/** f_permutation.v plus the two numbered submodule mutations. */
final class FPermutation(variant: Sha3Variant) extends Module {
  override def desiredName: String = "f_permutation"

  val in = IO(Input(UInt(576.W)))
  val in_ready = IO(Input(Bool()))
  val ack = IO(Output(Bool()))
  val out = IO(Output(UInt(1600.W)))
  val out_ready = IO(Output(Bool()))

  val roundIndex = Reg(UInt(23.W))
  val calc = Reg(Bool())
  val outReg = Reg(UInt(1600.W))
  val outReadyReg = Reg(Bool())

  val accept = in_ready && !calc
  val update = calc || accept

  val roundInput = Mux(
    accept,
    Cat(in ^ outReg(1599, 1024), outReg(1023, 0)),
    outReg
  )
  val constant = Module(new RoundConstant)
  constant.i := Cat(roundIndex, accept)
  val round = Module(new KeccakRound)
  round.in := roundInput
  round.round_const := constant.rc

  when(reset.asBool) {
    roundIndex := 0.U
  }.otherwise {
    roundIndex := Cat(roundIndex(21, 0), accept)
  }

  when(reset.asBool) {
    calc := false.B
  }.otherwise {
    calc := (calc && !roundIndex(variant.calcStopBit)) || accept
  }

  when(reset.asBool) {
    outReadyReg := false.B
  }.elsewhen(accept) {
    outReadyReg := false.B
  }.elsewhen(roundIndex(22)) {
    outReadyReg := true.B
  }

  val writeOutput = if (variant.updateOutputOnlyOnAccept) accept else update
  when(reset.asBool) {
    outReg := 0.U
  }.elsewhen(writeOutput) {
    outReg := round.out
  }

  ack := accept
  out := outReg
  out_ready := outReadyReg
}

/** Exact synthesizable shell for keccak_buggy_1.v's inferred latch. */
final class OutReadyLatchIO extends Bundle {
  val reset = Input(Bool())
  val set = Input(Bool())
  val out = Output(Bool())
}

final class OutReadyLatch extends BlackBox with HasBlackBoxInline {
  override def desiredName: String = "sha3_out_ready_latch"

  val io = IO(new OutReadyLatchIO)

  setInline(
    "sha3_out_ready_latch.sv",
    """module sha3_out_ready_latch(
      |  input reset,
      |  input set,
      |  output reg out
      |);
      |  always @(*) begin
      |    if (reset)
      |      out = 1'b0;
      |    else if (set)
      |      out = 1'b1;
      |  end
      |endmodule
      |""".stripMargin
  )
}

/** Complete low-throughput keccak top. */
final class Keccak(variant: Sha3Variant) extends Module {
  override def desiredName: String = "keccak"

  val in = IO(Input(UInt(32.W)))
  val in_ready = IO(Input(Bool()))
  val is_last = IO(Input(Bool()))
  val byte_num = IO(Input(UInt(2.W)))
  val buffer_full = IO(Output(Bool()))
  val out = IO(Output(UInt(512.W)))
  val out_ready = IO(Output(Bool()))

  val state = Reg(Bool())
  val delay = Reg(UInt(23.W))

  val padder = Module(new Padder)
  padder.in := in
  padder.in_ready := in_ready
  padder.is_last := is_last
  padder.byte_num := byte_num

  val permutation = Module(new FPermutation(variant))
  permutation.in := Sha3Bits.reverseBytesPerWord(padder.out, 9)
  permutation.in_ready := padder.out_ready
  padder.f_ack := permutation.ack

  when(reset.asBool) {
    delay := 0.U
  }.otherwise {
    delay := Cat(delay(21, 0), state && permutation.ack)
  }

  when(reset.asBool) {
    state := false.B
  }.elsewhen(is_last) {
    state := true.B
  }

  if (variant.latchOutReady) {
    val latch = Module(new OutReadyLatch)
    latch.io.reset := reset.asBool
    latch.io.set := delay(22)
    out_ready := latch.io.out
  } else {
    val ready = Reg(Bool())
    when(reset.asBool) {
      ready := false.B
    }.elsewhen(delay(22)) {
      ready := true.B
    }
    out_ready := ready
  }

  buffer_full := padder.buffer_full
  out := Sha3Bits.reverseBytesPerWord(permutation.out(1599, 1088), 8)
}

object EmitSha3Variants extends App {
  val outputRoot = Paths.get(args.headOption.getOrElse("generated")).toAbsolutePath
  val fileListMarker =
    "\n// ----- 8< ----- FILE \"firrtl_black_box_resource_files.f\" ----- 8< -----"

  Sha3Variants.all.foreach { variant =>
    val targetDir = outputRoot.resolve(variant.sourceStem).toString
    ChiselStage.emitSystemVerilogFile(
      new Keccak(variant),
      args = Array("--target-dir", targetDir),
      firtoolOpts = Array(
        "--disable-all-randomization",
        "--strip-debug-info",
        "--lowering-options=disallowLocalVariables,disallowPackedArrays"
      )
    )

    // Chisel 6.7/firtool 1.62 can concatenate the inline helper correctly and
    // then append the body of its .f resource list as raw, invalid SV. Remove
    // only that identified trailer; the helper module itself remains in-cone.
    val emitted = outputRoot.resolve(variant.sourceStem).resolve("keccak.sv")
    val text = Files.readString(emitted, StandardCharsets.UTF_8)
    val markerIndex = text.indexOf(fileListMarker)
    if (markerIndex >= 0) {
      Files.writeString(
        emitted,
        text.substring(0, markerIndex) + "\n",
        StandardCharsets.UTF_8
      )
    }
  }
}
