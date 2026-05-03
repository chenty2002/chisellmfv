package llmverify

import chisel3._
import chisel3.util._

object BallState extends ChiselEnum {
  val WaitVS, IncD, IncCoord, LoadY = Value
}

class Ball(WIDTH_VIDEO: Int = 10, StartX16: Int = 20, StartY16: Int = 15) extends Module {
  val io = IO(new Bundle {
    val HSync = Input(Bool())
    val VSync = Input(Bool())
    val RasterCollision = Input(Bool())
    val BitRaster = Output(Bool())
    val BitRasterShade = Output(Bool())
    val BRLftBall = Output(Bool())
    val BRRgtBall = Output(Bool())
    
    // Debug outputs to preserve internal signals
    val BallPosX = Output(UInt(WIDTH_VIDEO.W))
    val BallPosY = Output(UInt(WIDTH_VIDEO.W))
    val BallDX = Output(UInt(WIDTH_VIDEO.W))
    val BallDY = Output(UInt(WIDTH_VIDEO.W))
    val qDX = Output(UInt(WIDTH_VIDEO.W))
    val qDY = Output(UInt(WIDTH_VIDEO.W))
    val SSMoveBall = Output(UInt(2.W))
    val TopBall = Output(Bool())
    val BotBall = Output(Bool())
    val LftBall = Output(Bool())
    val RgtBall = Output(Bool())
    val MTopBall = Output(Bool())
    val MBotBall = Output(Bool())
    val MLftBall = Output(Bool())
    val MRgtBall = Output(Bool())
    val CollisionTop = Output(Bool())
    val CollisionBot = Output(Bool())
    val CollisionLft = Output(Bool())
    val CollisionRgt = Output(Bool())
    val CollisionMTop = Output(Bool())
    val CollisionMBot = Output(Bool())
    val CollisionMLft = Output(Bool())
    val CollisionMRgt = Output(Bool())
    val HorzShade = Output(Bool())
    val VertShade = Output(Bool())
  })
  
  val StartX = StartX16 * 16
  val StartY = StartY16 * 16
  
  // Registers
  val BallPosX = RegInit(0.U(WIDTH_VIDEO.W))
  val BallPosY = RegInit(0.U(WIDTH_VIDEO.W))
  val BallDX = RegInit(0.U(WIDTH_VIDEO.W))
  val BallDY = RegInit(0.U(WIDTH_VIDEO.W))
  val qDX = RegInit(0.U(WIDTH_VIDEO.W))
  val qDY = RegInit(0.U(WIDTH_VIDEO.W))
  val SSMoveBall = RegInit(BallState.WaitVS)
  
  val BitRaster = Wire(Bool())
  val BitRasterShade = Wire(Bool())
  
  val TopBall = RegInit(false.B)
  val BotBall = RegInit(false.B)
  val LftBall = RegInit(false.B)
  val RgtBall = RegInit(false.B)
  val MTopBall = RegInit(false.B)
  val MBotBall = RegInit(false.B)
  val MLftBall = RegInit(false.B)
  val MRgtBall = RegInit(false.B)
  
  val CollisionTop = RegInit(false.B)
  val CollisionBot = RegInit(false.B)
  val CollisionLft = RegInit(false.B)
  val CollisionRgt = RegInit(false.B)
  val CollisionMTop = RegInit(false.B)
  val CollisionMBot = RegInit(false.B)
  val CollisionMLft = RegInit(false.B)
  val CollisionMRgt = RegInit(false.B)
  
  val HorzShade = RegInit(false.B)
  val VertShade = RegInit(false.B)
  
  // Simple 2-bit counter for random number generation
  val randomCounter = RegInit(0.U(2.W))
  randomCounter := randomCounter + 1.U
  
  // Random4to7: bit 2 is always 1, bits 1:0 cycle through 0,1,2,3
  val Random4to7 = Cat(1.U(1.W), randomCounter)
  
  // Max3 function - limit input value to -3..-1, 1..3
  def Max3(ValueIn: UInt): UInt = {
    val result = Wire(UInt(WIDTH_VIDEO.W))
    val signBit = ValueIn(WIDTH_VIDEO - 1)
    val lowerBits = ValueIn(1, 0)
    
    // If lower bits are 0, set to 1 (avoid 0), otherwise keep as is
    val constrainedLower = Mux(lowerBits === 0.U, 1.U(2.W), lowerBits)
    
    // Sign extend the constrained lower bits
    result := Cat(Fill(WIDTH_VIDEO - 2, signBit), constrainedLower)
    result
  }
  
  // State machine
  switch(SSMoveBall) {
    is(BallState.WaitVS) {
      when(io.VSync) {
        SSMoveBall := BallState.IncD
      }
    }
    is(BallState.IncD) {
      when(CollisionLft) {
        BallDX := Max3(BallDX + Random4to7)
      }.elsewhen(CollisionRgt) {
        BallDX := Max3(BallDX - Random4to7)
      }.otherwise {
        BallDX := Max3(BallDX)
      }
      
      when(CollisionTop) {
        BallDY := Max3(BallDY + Random4to7)
      }.elsewhen(CollisionBot) {
        BallDY := Max3(BallDY - Random4to7)
      }.otherwise {
        BallDY := Max3(BallDY)
      }
      
      SSMoveBall := BallState.IncCoord
    }
    is(BallState.IncCoord) {
      when(!(CollisionLft && CollisionRgt)) {
        BallPosX := BallPosX + BallDX
      }
      when(!(CollisionTop && CollisionBot)) {
        BallPosY := BallPosY + BallDY
      }
      SSMoveBall := BallState.LoadY
    }
    is(BallState.LoadY) {
      SSMoveBall := BallState.WaitVS
    }
  }
  
  // Collision logic
  when(SSMoveBall === BallState.LoadY) {
    CollisionTop := false.B
    CollisionBot := false.B
    CollisionLft := false.B
    CollisionRgt := false.B
  }.elsewhen(CollisionLft && CollisionRgt && CollisionTop && CollisionBot) {
    CollisionTop := CollisionMTop
    CollisionBot := CollisionMBot
    CollisionLft := CollisionMLft
    CollisionRgt := CollisionMRgt
  }.otherwise {
    CollisionTop := CollisionTop || (TopBall && io.RasterCollision)
    CollisionBot := CollisionBot || (BotBall && io.RasterCollision)
    CollisionLft := CollisionLft || (LftBall && io.RasterCollision)
    CollisionRgt := CollisionRgt || (RgtBall && io.RasterCollision)
  }
  
  when(SSMoveBall === BallState.LoadY) {
    CollisionMTop := false.B
    CollisionMBot := false.B
    CollisionMLft := false.B
    CollisionMRgt := false.B
  }.otherwise {
    CollisionMTop := CollisionMTop || (MTopBall && io.RasterCollision)
    CollisionMBot := CollisionMBot || (MBotBall && io.RasterCollision)
    CollisionMLft := CollisionMLft || (MLftBall && io.RasterCollision)
    CollisionMRgt := CollisionMRgt || (MRgtBall && io.RasterCollision)
  }
  
  // Detection of ball edges for collision logic
  // Calculate the comparison values
  val maxX = ((1.U << WIDTH_VIDEO.U) - 1.U)
  val horizCompare = (maxX - StartX16.U)(WIDTH_VIDEO - 1, 4)
  val vertCompare = (maxX - StartY16.U)(WIDTH_VIDEO - 1, 4)
  
  val HorzBall = (qDX(WIDTH_VIDEO - 1, 4) === horizCompare)
  val VertBall = (qDY(WIDTH_VIDEO - 1, 4) === vertCompare)
  val MiddleBallX = (qDY === (maxX - 8.U - StartY.U))
  val MiddleBallY = (qDX === (maxX - 8.U - StartX.U))
  
  // Full sides and middle sides
  TopBall := (qDY === (maxX - StartY.U)) && HorzBall
  BotBall := (qDY === (maxX - 17.U - StartY.U)) && HorzBall
  LftBall := (qDX === (maxX - StartX.U)) && VertBall
  RgtBall := (qDX === (maxX - 17.U - StartX.U)) && VertBall
  
  MTopBall := (qDY === (maxX - StartY.U)) && MiddleBallY
  MBotBall := (qDY === (maxX - 17.U - StartY.U)) && MiddleBallY
  MLftBall := (qDX === (maxX - StartX.U)) && MiddleBallX
  MRgtBall := (qDX === (maxX - 17.U - StartX.U)) && MiddleBallX
  
  // Ball shade
  HorzShade := MiddleBallY || (HorzShade && !(qDX === (maxX - 8.U - StartX.U - 16.U)))
  VertShade := MiddleBallX || (VertShade && !(qDY === (maxX - 8.U - StartY.U - 16.U)))
  
  // Counters
  when(io.HSync) {
    qDX := BallPosX
  }.otherwise {
    qDX := qDX - 1.U
  }
  
  when(SSMoveBall === BallState.LoadY) {
    qDY := BallPosY
  }.elsewhen(io.HSync) {
    qDY := qDY - 1.U
  }
  
  // Raster generation
  BitRaster := HorzBall && VertBall
  BitRasterShade := HorzShade && VertShade
  
  // Outputs
  io.BitRaster := BitRaster
  io.BitRasterShade := BitRasterShade
  io.BRLftBall := LftBall
  io.BRRgtBall := RgtBall
  
  // Debug outputs
  io.BallPosX := BallPosX
  io.BallPosY := BallPosY
  io.BallDX := BallDX
  io.BallDY := BallDY
  io.qDX := qDX
  io.qDY := qDY
  io.SSMoveBall := SSMoveBall.asUInt
  io.TopBall := TopBall
  io.BotBall := BotBall
  io.LftBall := LftBall
  io.RgtBall := RgtBall
  io.MTopBall := MTopBall
  io.MBotBall := MBotBall
  io.MLftBall := MLftBall
  io.MRgtBall := MRgtBall
  io.CollisionTop := CollisionTop
  io.CollisionBot := CollisionBot
  io.CollisionLft := CollisionLft
  io.CollisionRgt := CollisionRgt
  io.CollisionMTop := CollisionMTop
  io.CollisionMBot := CollisionMBot
  io.CollisionMLft := CollisionMLft
  io.CollisionMRgt := CollisionMRgt
  io.HorzShade := HorzShade
  io.VertShade := VertShade
}

object VerilogGenerator extends App {
  emitVerilog(new Ball(), args)
}