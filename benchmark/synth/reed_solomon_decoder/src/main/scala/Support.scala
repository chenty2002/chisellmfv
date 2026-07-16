package reeds

import chisel3._
import chisel3.util.HasBlackBoxInline
import java.nio.file.{Files, Paths}

final class AsyncResetByteArray(
  private val elements: IndexedSeq[UInt],
  private val name: String
) {
  private var dynamicReadCount = 0

  def apply(index: Int): UInt = {
    require(index > 0 && index < elements.size, s"array index $index is outside 1..${elements.size - 1}")
    elements(index)
  }
  def apply(index: UInt): UInt = {
    val undefined = Module(new UninitializedByte)
    undefined.suggestName(s"${name}_read_${dynamicReadCount}_undefined")
    dynamicReadCount += 1
    VecInit(undefined.io.q +: elements.tail)(index)
  }

  def write(index: Int, value: UInt): Unit = {
    require(index > 0 && index < elements.size, s"array index $index is outside 1..${elements.size - 1}")
    elements(index) := value
  }
  def write(index: UInt, value: UInt): Unit =
    elements.zipWithIndex.drop(1).foreach { case (element, elementIndex) =>
      when(index === elementIndex.U) { element := value }
    }
}

sealed trait RSVariant {
  def bug1: Boolean = false
  def bug2: Boolean = false
  def bug3: Boolean = false
}

object RSVariant {
  case object Reference extends RSVariant
  case object Buggy1 extends RSVariant { override val bug1 = true }
  case object Buggy2 extends RSVariant { override val bug2 = true }
  case object Buggy3 extends RSVariant { override val bug3 = true }

  def fromName(name: String): RSVariant = name match {
    case "reed_solomon_decoder"         => Reference
    case "reed_solomon_decoder_buggy_1" => Buggy1
    case "reed_solomon_decoder_buggy_2" => Buggy2
    case "reed_solomon_decoder_buggy_3" => Buggy3
    case other => throw new IllegalArgumentException(s"unknown variant: $other")
  }
}

trait AsyncRegs { this: Module =>
  protected def aReg[T <: Data](init: T): T =
    withReset(reset.asAsyncReset) { RegInit(init) }

  /** Verilog arrays in this family reset elements 1..N asynchronously. */
  protected def asyncResetByteArray(size: Int, name: String): AsyncResetByteArray = {
    val elements = IndexedSeq.tabulate(size) { index =>
      val element = if (index == 0) 0.U(8.W) else aReg(0.U(8.W))
      element.suggestName(s"${name}_$index")
    }
    new AsyncResetByteArray(elements, name)
  }

  /** Preserve a Verilog declaration initializer in addition to async reset. */
  protected def initialAReg(init: UInt): (UInt, UInt) = {
    require(init.isLit, "initialAReg requires a literal initializer")
    val primitive = Module(new InitialAsyncReg(init.getWidth, init.litValue))
    primitive.io.clock := clock
    primitive.io.reset := reset.asBool
    val next = WireDefault(primitive.io.q)
    primitive.io.d := next
    (primitive.io.q, next)
  }
}

/** Four-state value used for indices outside the source Verilog array range. */
private final class UninitializedByte extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val q = Output(UInt(8.W))
  })

  setInline("UninitializedByte.sv",
    """module UninitializedByte(output reg [7:0] q);
      |endmodule
      |""".stripMargin)
}

private final class InitialAsyncReg(width: Int, init: BigInt)
    extends BlackBox(Map("WIDTH" -> width, "INIT" -> init))
    with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val d = Input(UInt(width.W))
    val q = Output(UInt(width.W))
  })

  setInline("InitialAsyncReg.sv",
    """module InitialAsyncReg #(
      |  parameter integer WIDTH = 1,
      |  parameter [WIDTH-1:0] INIT = {WIDTH{1'b0}}
      |) (
      |  input                  clock,
      |  input                  reset,
      |  input      [WIDTH-1:0] d,
      |  output reg [WIDTH-1:0] q
      |);
      |  initial q = INIT;
      |  always @(posedge clock or posedge reset) begin
      |    if (reset) q <= INIT;
      |    else q <= d;
      |  end
      |endmodule
      |""".stripMargin)
}

private final class DPRamPrimitive(
  numWords: Int,
  addressWidth: Int,
  dataWidth: Int
) extends BlackBox(Map(
  "NUM_WORDS" -> numWords,
  "ADDRESS_WIDTH" -> addressWidth,
  "DATA_WIDTH" -> dataWidth
)) with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val we = Input(Bool())
    val re = Input(Bool())
    val address_read = Input(UInt(addressWidth.W))
    val address_write = Input(UInt(addressWidth.W))
    val data_in = Input(UInt(dataWidth.W))
    val data_out = Output(UInt(dataWidth.W))
  })

  setInline("DPRamPrimitive.sv",
    """module DPRamPrimitive #(
      |  parameter NUM_WORDS = 205,
      |  parameter ADDRESS_WIDTH = 8,
      |  parameter DATA_WIDTH = 8
      |) (
      |  input clk, we, re,
      |  input [ADDRESS_WIDTH-1:0] address_read, address_write,
      |  input [DATA_WIDTH-1:0] data_in,
      |  output reg [DATA_WIDTH-1:0] data_out
      |);
      |  reg [DATA_WIDTH-1:0] mem [0:NUM_WORDS-1];
      |  integer i;
      |  initial begin
      |    for (i = 0; i < NUM_WORDS; i = i + 1) mem[i] = 0;
      |  end
      |  always @(posedge clk) begin
      |    if (we == 1'b1) mem[address_write] <= data_in;
      |    if (re == 1'b1) data_out <= mem[address_read];
      |  end
      |endmodule
      |""".stripMargin)
}

class DP_RAM(
  val num_words: Int = 205,
  val address_width: Int = 8,
  val data_width: Int = 8
) extends Module {
  val we = IO(Input(Bool()))
  val re = IO(Input(Bool()))
  val address_read = IO(Input(UInt(address_width.W)))
  val address_write = IO(Input(UInt(address_width.W)))
  val data_in = IO(Input(UInt(data_width.W)))
  val data_out = IO(Output(UInt(data_width.W)))

  private val primitive = Module(new DPRamPrimitive(num_words, address_width, data_width))
  primitive.io.clk := clock
  primitive.io.we := we
  primitive.io.re := re
  primitive.io.address_read := address_read
  primitive.io.address_write := address_write
  primitive.io.data_in := data_in
  data_out := primitive.io.data_out
}

private object SourceAsset {
  private val familyRelative = Paths.get(
    "..", "..", "Wit-HW", "buggy_designs", "reed_solomon_decoder")

  def read(name: String): String = {
    val path = familyRelative.resolve(name).normalize()
    require(Files.isRegularFile(path), s"missing immutable ROM source: $path")
    Files.readString(path)
  }
}

private abstract class GFTablePrimitive(fileName: String, sourceModule: String)
    extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val re = Input(Bool())
    val address_read = Input(UInt(8.W))
    val data_out = Output(UInt(8.W))
  })

  private val renamed = SourceAsset.read(fileName).replaceFirst(
    "module\\s+" + sourceModule,
    "module " + desiredName)
  setInline(desiredName + ".sv", renamed)
}

private final class GFDecPrimitive
  extends GFTablePrimitive("GF_matrix_dec.v", "GF_matrix_dec")
private final class GFPowPrimitive
  extends GFTablePrimitive("GF_matrix_ascending_binary.v", "GF_matrix_ascending_binary")

class GF_matrix_dec extends Module {
  val re = IO(Input(Bool()))
  val address_read = IO(Input(UInt(8.W)))
  val data_out = IO(Output(UInt(8.W)))
  private val primitive = Module(new GFDecPrimitive)
  primitive.io.clk := clock
  primitive.io.re := re
  primitive.io.address_read := address_read
  data_out := primitive.io.data_out
}

class GF_matrix_ascending_binary extends Module {
  val re = IO(Input(Bool()))
  val address_read = IO(Input(UInt(8.W)))
  val data_out = IO(Output(UInt(8.W)))
  private val primitive = Module(new GFPowPrimitive)
  primitive.io.clk := clock
  primitive.io.re := re
  primitive.io.address_read := address_read
  data_out := primitive.io.data_out
}
