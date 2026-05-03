package llmverify

import chisel3._
import chisel3.util._

// Enum definitions
object BooleanType {
  def TRUE = 1.U(1.W)
  def FALSE = 0.U(1.W)
}

object HandShakeType {
  val idle :: request :: lock :: release :: Nil = Enum(4)
}

// Processor Model
class procModel extends Module {
  val io = IO(new Bundle {
    val ack = Input(UInt(1.W))
    val req = Output(UInt(2.W))
  })
  
  val procState = RegInit(HandShakeType.idle)
  
  // Random choice (simplified as alternating pattern)
  val randChoiceCounter = RegInit(0.U(1.W))
  randChoiceCounter := randChoiceCounter + 1.U
  val randChoice = randChoiceCounter
  
  io.req := procState
  
  when(procState === HandShakeType.idle && randChoice === 1.U) {
    procState := HandShakeType.request
  }.elsewhen(procState === HandShakeType.request && io.ack === BooleanType.TRUE) {
    procState := HandShakeType.lock
  }.elsewhen(procState === HandShakeType.lock && randChoice === 1.U) {
    procState := HandShakeType.release
  }.elsewhen(procState === HandShakeType.release) {
    procState := HandShakeType.idle
  }
}

// Arbiter Cell
class arbitCell extends Module {
  val io = IO(new Bundle {
    val topCell = Input(UInt(1.W))
    val urLeft = Input(UInt(2.W))
    val urRight = Input(UInt(2.W))
    val xa = Input(UInt(1.W))
    val uaLeft = Output(UInt(1.W))
    val uaRight = Output(UInt(1.W))
    val xr = Output(UInt(2.W))
  })
  
  val prevLeft = RegInit(BooleanType.FALSE)
  val prevRight = RegInit(BooleanType.TRUE)
  val processedLeft = RegInit(BooleanType.FALSE)
  val processedRight = RegInit(BooleanType.FALSE)
  val holdToken = RegInit(0.U(1.W))
  
  // Initialize holdToken based on topCell at reset
  when(reset.asBool) {
    holdToken := io.topCell
  }
  
  val mustGiveParent = Mux(
    (processedLeft === BooleanType.TRUE && processedRight === BooleanType.TRUE && 
     !(io.topCell === BooleanType.TRUE)),
    BooleanType.TRUE,
    BooleanType.FALSE
  )
  
  val childOwns = Mux(
    (io.urLeft === HandShakeType.lock || io.urRight === HandShakeType.lock),
    BooleanType.TRUE,
    BooleanType.FALSE
  )
  
  val giveChild = Mux(
    (io.uaLeft === BooleanType.TRUE || io.uaRight === BooleanType.TRUE),
    BooleanType.TRUE,
    BooleanType.FALSE
  )
  
  io.uaLeft := Mux(
    !(mustGiveParent === BooleanType.TRUE) && 
    (holdToken === BooleanType.TRUE && io.urLeft === HandShakeType.request && 
     (!(io.urRight === HandShakeType.request) || prevRight === BooleanType.TRUE)),
    BooleanType.TRUE,
    BooleanType.FALSE
  )
  
  io.uaRight := Mux(
    !(mustGiveParent === BooleanType.TRUE) && 
    (holdToken === BooleanType.TRUE && io.urRight === HandShakeType.request && 
     (!(io.urLeft === HandShakeType.request) || prevLeft === BooleanType.TRUE)),
    BooleanType.TRUE,
    BooleanType.FALSE
  )
  
  io.xr := Mux(
    holdToken === BooleanType.FALSE && (io.urLeft === HandShakeType.request || io.urRight === HandShakeType.request),
    HandShakeType.request,
    Mux(
      childOwns === BooleanType.TRUE,
      HandShakeType.lock,
      Mux(
        holdToken === BooleanType.TRUE && ((mustGiveParent === BooleanType.TRUE || 
          !(io.urLeft === HandShakeType.request || io.urRight === HandShakeType.request)) && 
          !(io.topCell === BooleanType.TRUE)),
        HandShakeType.release,
        HandShakeType.idle
      )
    )
  )
  
  // Update holdToken
  when(io.xa === BooleanType.TRUE) {
    holdToken := BooleanType.TRUE
  }.elsewhen(giveChild === BooleanType.TRUE) {
    holdToken := BooleanType.FALSE
  }.elsewhen(io.urLeft === HandShakeType.release || io.urRight === HandShakeType.release) {
    holdToken := BooleanType.TRUE
  }.elsewhen(io.xr === HandShakeType.release) {
    holdToken := BooleanType.FALSE
  }
  
  // Update prevLeft and prevRight
  when(io.uaLeft === BooleanType.TRUE) {
    prevLeft := BooleanType.TRUE
    prevRight := BooleanType.FALSE
  }.elsewhen(io.uaRight === BooleanType.TRUE) {
    prevLeft := BooleanType.FALSE
    prevRight := BooleanType.TRUE
  }
  
  // Update processed flags
  when(io.urLeft === HandShakeType.release) {
    processedLeft := BooleanType.TRUE
  }.elsewhen(io.urRight === HandShakeType.release) {
    processedRight := BooleanType.TRUE
  }.elsewhen(processedLeft === BooleanType.TRUE && processedRight === BooleanType.TRUE) {
    processedLeft := BooleanType.FALSE
    processedRight := BooleanType.FALSE
  }
}

// Four Cells Module
class fourCells extends Module {
  val io = IO(new Bundle {
    val sa = Input(UInt(1.W))
    val sr = Output(UInt(2.W))
    // Additional outputs for debugging/verification
    val ur1 = Output(UInt(2.W))
    val ur2 = Output(UInt(2.W))
    val ur3 = Output(UInt(2.W))
    val ur4 = Output(UInt(2.W))
    val ua1 = Output(UInt(1.W))
    val ua2 = Output(UInt(1.W))
    val ua3 = Output(UInt(1.W))
    val ua4 = Output(UInt(1.W))
    val xa = Output(UInt(1.W))
    val ya = Output(UInt(1.W))
  })
  
  // Arbiter cells
  val C0 = Module(new arbitCell())
  val C1 = Module(new arbitCell())
  val C2 = Module(new arbitCell())
  
  // Processor models
  val P1 = Module(new procModel())
  val P2 = Module(new procModel())
  val P3 = Module(new procModel())
  val P4 = Module(new procModel())
  
  // Connect C0 (top cell)
  C0.io.topCell := BooleanType.FALSE
  C0.io.urLeft := C1.io.xr
  C0.io.urRight := C2.io.xr
  C0.io.xa := io.sa
  io.sr := C0.io.xr
  
  // Connect C1 (left subtree)
  C1.io.topCell := BooleanType.FALSE
  C1.io.urLeft := P1.io.req
  C1.io.urRight := P2.io.req
  C1.io.xa := C0.io.uaLeft
  io.xa := C0.io.uaLeft
  
  // Connect C2 (right subtree)
  C2.io.topCell := BooleanType.FALSE
  C2.io.urLeft := P3.io.req
  C2.io.urRight := P4.io.req
  C2.io.xa := C0.io.uaRight
  io.ya := C0.io.uaRight
  
  // Connect processors
  P1.io.ack := C1.io.uaLeft
  P2.io.ack := C1.io.uaRight
  P3.io.ack := C2.io.uaLeft
  P4.io.ack := C2.io.uaRight
  
  // Output debugging signals
  io.ur1 := P1.io.req
  io.ur2 := P2.io.req
  io.ur3 := P3.io.req
  io.ur4 := P4.io.req
  io.ua1 := C1.io.uaLeft
  io.ua2 := C1.io.uaRight
  io.ua3 := C2.io.uaLeft
  io.ua4 := C2.io.uaRight
}

// Main Module
class main extends Module {
  val io = IO(new Bundle {
    // Additional outputs for debugging/verification
    val xr = Output(UInt(2.W))
    val yr = Output(UInt(2.W))
    val xa = Output(UInt(1.W))
    val ya = Output(UInt(1.W))
    val sr = Output(UInt(2.W))
    val sa = Output(UInt(1.W))
  })
  
  val F0 = Module(new arbitCell())
  val G1 = Module(new fourCells())
  val G2 = Module(new fourCells())
  
  // Connect F0 (top arbiter)
  F0.io.topCell := BooleanType.TRUE
  F0.io.urLeft := G1.io.sr
  F0.io.urRight := G2.io.sr
  F0.io.xa := BooleanType.FALSE
  
  // Connect G1
  G1.io.sa := F0.io.uaLeft
  
  // Connect G2
  G2.io.sa := F0.io.uaRight
  
  // Output debugging signals
  io.xr := G1.io.sr
  io.yr := G2.io.sr
  io.xa := F0.io.uaLeft
  io.ya := F0.io.uaRight
  io.sr := F0.io.xr
  io.sa := BooleanType.FALSE
}

// Verilog Generator
object VerilogGenerator extends App {
  emitVerilog(new main(), Array("--target-dir", "generated"))
}