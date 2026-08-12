package chisellmfv.generated

import chisel3._
import chisel3.util._

private object RS204Reference {
  private def gfMulInt(left: Int, right: Int): Int = {
    var a = left
    var b = right
    var result = 0
    while (b != 0) {
      if ((b & 1) != 0) result ^= a
      a = ((a << 1) ^ (if ((a & 0x80) != 0) 0x11d else 0)) & 0xff
      b >>>= 1
    }
    result
  }

  private def alphaPow(power: Int): Int =
    (0 until power).foldLeft(1)((value, _) => gfMulInt(value, 2))

  val generator: IndexedSeq[Int] =
    (1 to 16).foldLeft(IndexedSeq(1)) { (polynomial, power) =>
      val root = alphaPow(power)
      val next = Array.fill(polynomial.size + 1)(0)
      polynomial.indices.foreach { index =>
        next(index) ^= polynomial(index)
        next(index + 1) ^= gfMulInt(polynomial(index), root)
      }
      next.toIndexedSeq
    }

  private def xtime(value: UInt): UInt =
    Cat(value(6, 0), 0.U(1.W)) ^ Mux(value(7), "h1d".U(8.W), 0.U(8.W))

  def gfMul(value: UInt, constant: Int): UInt = {
    var multiple = value
    var result = 0.U(8.W)
    (0 until 8).foreach { bit =>
      if (((constant >> bit) & 1) != 0) result = result ^ multiple
      multiple = xtime(multiple)
    }
    result
  }
}

/** Independent relational checker for the public RS(204,188) property. */
final class ReedSolomonReferenceMonitor extends Module {
  val CE = IO(Input(Bool()))
  val input_byte = IO(Input(UInt(8.W)))
  val Out_byte = IO(Input(UInt(8.W)))
  val CEO = IO(Input(Bool()))
  val Valid_out = IO(Input(Bool()))
  val reference_data = IO(Input(UInt(8.W)))
  val track_frame = IO(Input(Bool()))

  val relation_ok = IO(Output(Bool()))
  val check_valid = IO(Output(Bool()))
  val activation = IO(Output(Bool()))
  val observer = IO(Output(Bool()))
  val premise = IO(Output(Bool()))
  val premise_valid = IO(Output(Bool()))

  val inputSymbol = RegInit(0.U(8.W))
  val inputFrame = RegInit(0.U(16.W))
  val outputSymbol = RegInit(0.U(8.W))
  val outputFrame = RegInit(0.U(16.W))
  val selectedFrame = RegInit(0.U(16.W))
  val selected = RegInit(false.B)
  val reference = Reg(Vec(188, UInt(8.W)))
  val parity = RegInit(VecInit(Seq.fill(16)(0.U(8.W))))
  val errorCount = RegInit(0.U(5.W))
  val frameReady = RegInit(false.B)

  val inputEvent = !reset.asBool && CE
  val startCapture = inputEvent && inputSymbol === 0.U && !selected && track_frame
  val captureEvent = inputEvent && ((selected && !frameReady) || startCapture)
  val dataEvent = captureEvent && inputSymbol < 188.U
  val parityEvent = captureEvent && inputSymbol >= 188.U
  val parityIndex = (inputSymbol - 188.U)(3, 0)
  val mismatch = Mux(
    dataEvent,
    input_byte =/= reference_data,
    parityEvent && input_byte =/= Mux(startCapture, 0.U, parity(parityIndex)),
  )
  val errorAfterInput = Mux(startCapture, 0.U, errorCount) +& mismatch
  val frameDone = captureEvent && inputSymbol === 203.U

  val feedback = reference_data ^ Mux(startCapture, 0.U, parity(0))
  val nextParity = Wire(Vec(16, UInt(8.W)))
  (0 until 15).foreach { index =>
    nextParity(index) := Mux(startCapture, 0.U, parity(index + 1)) ^
      RS204Reference.gfMul(feedback, RS204Reference.generator(index + 1))
  }
  nextParity(15) := RS204Reference.gfMul(feedback, RS204Reference.generator(16))

  val outputEvent = !reset.asBool && Valid_out && CEO
  val selectedOutputEvent = frameReady && outputEvent && outputFrame === selectedFrame

  when(reset.asBool) {
    inputSymbol := 0.U
    inputFrame := 0.U
    outputSymbol := 0.U
    outputFrame := 0.U
    selectedFrame := 0.U
    selected := false.B
    parity.foreach(_ := 0.U)
    errorCount := 0.U
    frameReady := false.B
  }.otherwise {
    when(inputEvent) {
      when(inputSymbol === 203.U) {
        inputSymbol := 0.U
        inputFrame := inputFrame + 1.U
      }.otherwise {
        inputSymbol := inputSymbol + 1.U
      }
    }
    when(startCapture) {
      selected := true.B
      selectedFrame := inputFrame
    }
    when(captureEvent) {
      errorCount := errorAfterInput
      when(dataEvent) {
        reference(inputSymbol) := reference_data
        parity := nextParity
      }
      when(frameDone) {
        frameReady := true.B
      }
    }
    when(outputEvent) {
      when(outputSymbol === 187.U) {
        outputSymbol := 0.U
        outputFrame := outputFrame + 1.U
      }.otherwise {
        outputSymbol := outputSymbol + 1.U
      }
    }
    when(selectedOutputEvent && outputSymbol === 187.U) {
      selected := false.B
      frameReady := false.B
      parity.foreach(_ := 0.U)
      errorCount := 0.U
    }
  }

  premise_valid := frameDone
  premise := errorAfterInput <= 8.U
  check_valid := selectedOutputEvent
  relation_ok := Out_byte === reference(outputSymbol)
  activation := frameReady
  observer := selectedOutputEvent && outputSymbol === 187.U
}
