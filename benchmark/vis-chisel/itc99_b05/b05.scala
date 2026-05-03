package llmverify
import chisel3._
import chisel3.util._

class b05 extends Module {
  val io = IO(new Bundle {
    val START = Input(Bool())
    val SIGN = Output(Bool())
    val DISPMAX1 = Output(UInt(7.W))
    val DISPMAX2 = Output(UInt(7.W))
    val DISPMAX3 = Output(UInt(7.W))
    val DISPNUM1 = Output(UInt(7.W))
    val DISPNUM2 = Output(UInt(7.W))
  })

  // State enumeration
  object State extends ChiselEnum {
    val st0, st1, st2, st3, st4 = Value
  }

  // Registers
  val stato = RegInit(State.st0)
  val mar = RegInit(0.U(5.W))
  val num = RegInit(0.U(5.W))
  val temp = RegInit(0.U(9.W))
  val max = RegInit(0.U(9.W))
  val flag = RegInit(false.B)
  val enDisp = RegInit(false.B)
  val resDisp = RegInit(false.B)

  // Memory function
  def mem(address: UInt): UInt = {
    val addr = address
    MuxCase(50.U(9.W), Seq(
      (addr === 0.U) -> 50.U,
      (addr === 1.U) -> 40.U,
      (addr === 2.U) -> 0.U,
      (addr === 3.U) -> 229.U,
      (addr === 4.U) -> 502.U, // -10
      (addr === 5.U) -> 75.U,
      (addr === 6.U) -> 229.U,
      (addr === 7.U) -> 181.U,
      (addr === 8.U) -> 186.U,
      (addr === 9.U) -> 229.U,
      (addr === 10.U) -> 186.U,
      (addr === 11.U) -> 501.U, // -11
      (addr === 12.U) -> 0.U,
      (addr === 13.U) -> 40.U,
      (addr === 14.U) -> 50.U,
      (addr === 15.U) -> 483.U, // -29
      (addr === 16.U) -> 494.U, // -18
      (addr === 17.U) -> 229.U,
      (addr === 18.U) -> 229.U,
      (addr === 19.U) -> 151.U,
      (addr === 20.U) -> 229.U,
      (addr === 21.U) -> 100.U,
      (addr === 22.U) -> 125.U,
      (addr === 23.U) -> 10.U,
      (addr === 24.U) -> 75.U,
      (addr === 25.U) -> 462.U, // -50
      (addr === 26.U) -> 0.U,
      (addr === 27.U) -> 490.U, // -22
      (addr === 28.U) -> 0.U,
      (addr === 29.U) -> 40.U,
      (addr === 30.U) -> 50.U
    ))
  }

  // Wires
  val memMar = Wire(UInt(9.W))
  val ac1 = Wire(UInt(9.W))
  val ac2 = Wire(UInt(9.W))
  val min1 = Wire(Bool())
  val mag1 = Wire(Bool())
  val mag2 = Wire(Bool())

  // Assignments
  memMar := mem(mar)
  ac1 := memMar - temp
  ac2 := memMar - max
  min1 := ac1(8)
  mag1 := !ac1(8) && (ac1(7, 0) =/= 0.U)
  mag2 := ac2(8)

  // Display functions
  def cDISPMAX1(enDisp: Bool, resDisp: Bool, max: UInt): UInt = {
    val tm = Wire(UInt(9.W))
    when(max(8)) {
      tm := Cat(4.U(4.W), (-max(4, 0)).asUInt)
    }.otherwise {
      tm := Cat(0.U(4.W), max(4, 0))
    }
    
    MuxCase(0.U(7.W), Seq(
      enDisp -> 0.U,
      (!resDisp) -> "b1000000".U,
      (!tm(8) && tm > 99.U) -> "b0011000".U,
      true.B -> "b0111111".U
    ))
  }

  def cDISPMAX2(enDisp: Bool, resDisp: Bool, max: UInt): UInt = {
    val tm = Wire(UInt(9.W))
    when(max(8)) {
      tm := Cat(4.U(4.W), (-max(4, 0)).asUInt)
    }.otherwise {
      tm := Cat(0.U(4.W), max(4, 0))
    }
    
    val tmAdj = Wire(UInt(9.W))
    tmAdj := tm
    when(!tm(8) && tm > 99.U) {
      tmAdj := tm - 100.U
    }
    
    MuxCase(0.U(7.W), Seq(
      enDisp -> 0.U,
      (!resDisp) -> "b1000000".U,
      (!tmAdj(8) && tmAdj > 89.U) -> "b1111110".U,
      (!tmAdj(8) && tmAdj > 79.U) -> "b1111111".U,
      (!tmAdj(8) && tmAdj > 69.U) -> "b0011100".U,
      (!tmAdj(8) && tmAdj > 59.U) -> "b1110111".U,
      (!tmAdj(8) && tmAdj > 49.U) -> "b1110110".U,
      (!tmAdj(8) && tmAdj > 39.U) -> "b1011010".U,
      (!tmAdj(8) && tmAdj > 29.U) -> "b1111001".U,
      (!tmAdj(8) && tmAdj > 19.U) -> "b1101100".U,
      (!tmAdj(8) && tmAdj > 9.U) -> "b0011000".U,
      true.B -> "b0111111".U
    ))
  }

  def cDISPMAX3(enDisp: Bool, resDisp: Bool, max: UInt): UInt = {
    val tm = Wire(UInt(9.W))
    when(max(8)) {
      tm := Cat(4.U(4.W), (-max(4, 0)).asUInt)
    }.otherwise {
      tm := Cat(0.U(4.W), max(4, 0))
    }
    
    val tmAdj = Wire(UInt(9.W))
    tmAdj := tm
    
    when(!tm(8) && tm > 99.U) { tmAdj := tm - 100.U }
    .elsewhen(!tm(8) && tm > 89.U) { tmAdj := tm - 90.U }
    .elsewhen(!tm(8) && tm > 79.U) { tmAdj := tm - 80.U }
    .elsewhen(!tm(8) && tm > 69.U) { tmAdj := tm - 70.U }
    .elsewhen(!tm(8) && tm > 59.U) { tmAdj := tm - 60.U }
    .elsewhen(!tm(8) && tm > 49.U) { tmAdj := tm - 50.U }
    .elsewhen(!tm(8) && tm > 39.U) { tmAdj := tm - 40.U }
    .elsewhen(!tm(8) && tm > 29.U) { tmAdj := tm - 30.U }
    .elsewhen(!tm(8) && tm > 19.U) { tmAdj := tm - 20.U }
    .elsewhen(!tm(8) && tm > 9.U) { tmAdj := tm - 10.U }
    
    MuxCase(0.U(7.W), Seq(
      enDisp -> 0.U,
      (!resDisp) -> "b1000000".U,
      (!tmAdj(8) && tmAdj > 8.U) -> "b1111110".U,
      (!tmAdj(8) && tmAdj > 7.U) -> "b1111111".U,
      (!tmAdj(8) && tmAdj > 6.U) -> "b0011100".U,
      (!tmAdj(8) && tmAdj > 5.U) -> "b1110111".U,
      (!tmAdj(8) && tmAdj > 4.U) -> "b1110110".U,
      (!tmAdj(8) && tmAdj > 3.U) -> "b1011010".U,
      (!tmAdj(8) && tmAdj > 2.U) -> "b1111001".U,
      (!tmAdj(8) && tmAdj > 1.U) -> "b1101100".U,
      (!tmAdj(8) && tmAdj > 0.U) -> "b0011000".U,
      true.B -> "b0111111".U
    ))
  }

  def cDISPNUM1(enDisp: Bool, resDisp: Bool, num: UInt): UInt = {
    MuxCase(0.U(7.W), Seq(
      enDisp -> 0.U,
      (!resDisp) -> "b1000000".U,
      (num > 9.U) -> "b0011000".U,
      true.B -> "b0111111".U
    ))
  }

  def cDISPNUM2(enDisp: Bool, resDisp: Bool, num: UInt): UInt = {
    val tn = Wire(UInt(5.W))
    when(num > 9.U) {
      tn := num - 10.U
    }.otherwise {
      tn := num
    }
    
    MuxCase(0.U(7.W), Seq(
      enDisp -> 0.U,
      (!resDisp) -> "b1000000".U,
      (tn > 8.U) -> "b1111110".U,
      (tn > 7.U) -> "b1111111".U,
      (tn > 6.U) -> "b0011100".U,
      (tn > 5.U) -> "b1110111".U,
      (tn > 4.U) -> "b1110110".U,
      (tn > 3.U) -> "b1011010".U,
      (tn > 2.U) -> "b1111001".U,
      (tn > 1.U) -> "b1101100".U,
      (tn > 0.U) -> "b0011000".U,
      true.B -> "b0111111".U
    ))
  }

  // Output assignments
  io.SIGN := !enDisp && (!resDisp || max(8))
  io.DISPMAX1 := cDISPMAX1(enDisp, resDisp, max)
  io.DISPMAX2 := cDISPMAX2(enDisp, resDisp, max)
  io.DISPMAX3 := cDISPMAX3(enDisp, resDisp, max)
  io.DISPNUM1 := cDISPNUM1(enDisp, resDisp, num)
  io.DISPNUM2 := cDISPNUM2(enDisp, resDisp, num)

  // State machine
  switch(stato) {
    is(State.st0) {
      resDisp := false.B
      enDisp := false.B
      stato := State.st1
    }
    is(State.st1) {
      when(io.START) {
        num := 0.U
        mar := 0.U
        flag := false.B
        enDisp := true.B
        resDisp := true.B
        stato := State.st2
      }.otherwise {
        stato := State.st1
      }
    }
    is(State.st2) {
      max := memMar
      temp := memMar
      stato := State.st3
    }
    is(State.st3) {
      when(min1) {
        when(flag) {
          flag := false.B
          num := num + 1.U
        }
      }.otherwise {
        when(mag1) {
          when(mag2) {
            max := memMar
          }
          flag := true.B
        }
      }
      temp := memMar
      stato := State.st4
    }
    is(State.st4) {
      when(mar === 31.U) {
        when(io.START) {
          stato := State.st4
        }.otherwise {
          stato := State.st1
        }
        enDisp := false.B
      }.otherwise {
        mar := mar + 1.U
        stato := State.st3
      }
    }
  }
}

object VerilogGenerator extends App {
  emitVerilog(new b05(), args)
}