package llmverify

import chisel3._
import chisel3.util._

class BitMux2_1 extends Module {
  val io = IO(new Bundle {
    val a = Input(Bool())
    val b = Input(Bool())
    val select = Input(Bool())
    val out = Output(Bool())
  })
  
  io.out := Mux(io.select, io.b, io.a)
}

class BusMux2_1(width: Int = 16) extends Module {
  val io = IO(new Bundle {
    val data0 = Input(UInt(width.W))
    val data1 = Input(UInt(width.W))
    val select = Input(Bool())
    val out = Output(UInt(width.W))
  })
  
  io.out := Mux(io.select, io.data1, io.data0)
}

class RCA(width: Int = 16) extends Module {
  val io = IO(new Bundle {
    val A = Input(UInt(width.W))
    val B = Input(UInt(width.W))
    val Cin = Input(Bool())
    val S = Output(UInt(width.W))
    val Cout = Output(Bool())
  })
  
  val sum = io.A +& io.B + io.Cin
  io.S := sum(width-1, 0)
  io.Cout := sum(width)
}

class complement(width: Int = 16) extends Module {
  val io = IO(new Bundle {
    val datain = Input(UInt(width.W))
    val enable = Input(Bool())
    val dataout = Output(UInt(width.W))
  })
  
  val A = Mux(io.enable, ~io.datain, io.datain)
  
  // Generate carry chain
  val Co = Wire(Vec(width, Bool()))
  Co(0) := A(0) & io.enable
  for (i <- 1 until width) {
    Co(i) := A(i) & Co(i-1)
  }
  
  val dataout = Wire(Vec(width, Bool()))
  dataout(0) := A(0) ^ io.enable
  for (i <- 1 until width) {
    dataout(i) := A(i) ^ Co(i-1)
  }
  
  io.dataout := dataout.asUInt
}

class Adder(width: Int = 16) extends Module {
  val io = IO(new Bundle {
    val A = Input(UInt(width.W))
    val B = Input(UInt(width.W))
    val Asign = Input(Bool())
    val AS = Input(Bool())
    val S = Output(UInt(width.W))
    val sign = Output(Bool())
  })
  
  val Y_1 = (!io.AS) & io.Asign
  
  val mux0 = Module(new BusMux2_1(width))
  mux0.io.data0 := io.A
  mux0.io.data1 := io.B
  mux0.io.select := Y_1
  val Atemp = mux0.io.out
  
  val mux1 = Module(new BusMux2_1(width))
  mux1.io.data0 := io.B
  mux1.io.data1 := io.A
  mux1.io.select := Y_1
  val Btemp = mux1.io.out
  
  val Y_2 = io.Asign ^ io.AS
  val Btemp1 = Mux(Y_2, ~Btemp, Btemp)
  
  val rca = Module(new RCA(width))
  rca.io.A := Atemp
  rca.io.B := Btemp1
  rca.io.Cin := Y_2
  val Stemp = rca.io.S
  val Y_3 = rca.io.Cout
  
  val Y_4 = (!Y_3) & (io.Asign ^ io.AS)
  
  val compl = Module(new complement(width))
  compl.io.datain := Stemp
  compl.io.enable := Y_4
  io.S := compl.io.dataout
  
  io.sign := (!Y_3 & io.AS) | (!Y_3 & io.Asign) | (io.AS & io.Asign)
}

class shifter(width: Int = 16) extends Module {
  val io = IO(new Bundle {
    val datain = Input(UInt(width.W))
    val shift = Input(UInt(4.W))
    val dataout = Output(UInt(width.W))
  })
  
  val shiftMappings = Seq(
    (io.shift === 0.U) -> io.datain,
    (io.shift === 1.U) -> Cat(0.U(1.W), io.datain(width-1, 1)),
    (io.shift === 2.U) -> Cat(0.U(2.W), io.datain(width-1, 2)),
    (io.shift === 3.U) -> Cat(0.U(3.W), io.datain(width-1, 3)),
    (io.shift === 4.U) -> Cat(0.U(4.W), io.datain(width-1, 4)),
    (io.shift === 5.U) -> Cat(0.U(5.W), io.datain(width-1, 5)),
    (io.shift === 6.U) -> Cat(0.U(6.W), io.datain(width-1, 6)),
    (io.shift === 7.U) -> Cat(0.U(7.W), io.datain(width-1, 7)),
    (io.shift === 8.U) -> Cat(0.U(8.W), io.datain(width-1, 8)),
    (io.shift === 9.U) -> Cat(0.U(9.W), io.datain(width-1, 9)),
    (io.shift === 10.U) -> Cat(0.U(10.W), io.datain(width-1, 10)),
    (io.shift === 11.U) -> Cat(0.U(11.W), io.datain(width-1, 11)),
    (io.shift === 12.U) -> Cat(0.U(12.W), io.datain(width-1, 12)),
    (io.shift === 13.U) -> Cat(0.U(13.W), io.datain(width-1, 13)),
    (io.shift === 14.U) -> Cat(0.U(14.W), io.datain(width-1, 14)),
    (io.shift === 15.U) -> Cat(0.U(15.W), io.datain(width-1, 15))
  )
  
  io.dataout := MuxCase(io.datain, shiftMappings)
}

class cordic extends Module {
  val io = IO(new Bundle {
    val theta = Input(UInt(16.W))
    val Sign = Input(Bool())
    val reset = Input(Bool())
    val CosX = Output(UInt(16.W))
    val SinX = Output(UInt(16.W))
    // Add debug outputs to preserve internal signals
    val debug_X = Output(UInt(16.W))
    val debug_Y = Output(UInt(16.W))
    val debug_Angle = Output(UInt(16.W))
    val debug_iteration = Output(UInt(4.W))
  })
  
  val REG_MSB = 15
  val width = 16
  
  // Internal registers
  val AngleCin = RegInit(false.B)
  val Xsign = RegInit(false.B)
  val Ysign = RegInit(false.B)
  val X = RegInit("b1001101110000000".U(width.W)) // 0.6072
  val Y = RegInit(0.U(width.W))
  val Angle = RegInit(0.U(width.W))
  val iteration = RegInit(0.U(4.W))
  
  // Tan lookup table
  def tan(index: UInt): UInt = {
    val tanMappings = Seq(
      (index === 0.U) -> "b00101101_00000000".U(width.W), //  1.000000 |45.000000
      (index === 1.U) -> "b00011010_11111111".U(width.W), //  0.500000 |26.565051
      (index === 2.U) -> "b00001110_00001111".U(width.W), //  0.250000 |14.036243
      (index === 3.U) -> "b00000111_00111111".U(width.W), //  0.125000 |7.125016
      (index === 4.U) -> "b00000011_11111111".U(width.W), //  0.062500 |3.576334
      (index === 5.U) -> "b00000001_11111111".U(width.W), //  0.031250 |1.789911
      (index === 6.U) -> "b00000000_11111111".U(width.W), //  0.015625 |0.895174
      (index === 7.U) -> "b00000000_01111111".U(width.W), //  0.007812 |0.447614
      (index === 8.U) -> "b00000000_00111111".U(width.W), //  0.003906 |0.223811
      (index === 9.U) -> "b00000000_00011111".U(width.W), //  0.001953 |0.111906
      (index === 10.U) -> "b00000000_00001111".U(width.W), //  0.000977 |0.055953
      (index === 11.U) -> "b00000000_00000111".U(width.W), //  0.000488 |0.027976
      (index === 12.U) -> "b00000000_00000011".U(width.W), //  0.000244 |0.013988
      (index === 13.U) -> "b00000000_00000001".U(width.W), //  0.000122 |0.006994
      (index === 14.U) -> "b00000000_00000000".U(width.W), //  0.000061 |0.003497
      (index === 15.U) -> "b00000000_00000000".U(width.W)  //  0.000031 |0.001749
    )
    MuxCase(0.U(width.W), tanMappings)
  }
  
  val tanangle = tan(iteration)
  
  // Data Path
  val sh1 = Module(new shifter(width))
  sh1.io.datain := Y
  sh1.io.shift := iteration
  val BS1 = sh1.io.dataout
  
  val addX = Module(new Adder(width))
  addX.io.A := X
  addX.io.B := BS1
  addX.io.Asign := Xsign
  addX.io.AS := !AngleCin
  val SumX = addX.io.S
  val CarryX = addX.io.sign
  
  val sh2 = Module(new shifter(width))
  sh2.io.datain := X
  sh2.io.shift := iteration
  val BS2 = sh2.io.dataout
  
  val addY = Module(new Adder(width))
  addY.io.A := Y
  addY.io.B := BS2
  addY.io.Asign := Ysign
  addY.io.AS := AngleCin
  val SumY = addY.io.S
  val CarryY = addY.io.sign
  
  val add0 = Module(new Adder(width))
  add0.io.A := Angle
  add0.io.B := tanangle
  add0.io.Asign := AngleCin
  add0.io.AS := !AngleCin
  val SumAngle = add0.io.S
  val AngleCout = add0.io.sign
  
  io.CosX := Cat(CarryX, SumX(width-2, 0))
  io.SinX := Cat(CarryY, SumY(width-2, 0))
  
  // Debug outputs to preserve signals
  io.debug_X := X
  io.debug_Y := Y
  io.debug_Angle := Angle
  io.debug_iteration := iteration
  
  // System FSM
  when(io.reset) {
    iteration := 0.U
    Angle := io.theta
    X := "b1001101110000000".U(width.W) // 0.6072
    Y := 0.U(width.W)
    Xsign := false.B
    Ysign := false.B
    AngleCin := io.Sign
  }.elsewhen(iteration =/= 15.U) {
    iteration := iteration + 1.U
    Angle := SumAngle
    X := SumX
    Y := SumY
    Xsign := CarryX
    Ysign := CarryY
    AngleCin := AngleCout
  }
}

object VerilogGenerator extends App {
  emitVerilog(new cordic(), args)
}