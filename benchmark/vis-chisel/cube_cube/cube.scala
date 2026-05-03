package llmverify
import chisel3._
import chisel3.util._

class cube extends Module {
  val io = IO(new Bundle {
    val dir = Input(UInt(3.W))
    val start = Input(UInt(5.W))
    val pos = Output(UInt(5.W))
    // Add outputs to preserve internal state
    val visited = Output(Vec(27, Bool()))
    val dest = Output(UInt(5.W))
  })

  // Direction constants
  val U = 0.U(3.W)  // upward
  val D = 1.U(3.W)  // downward
  val L = 2.U(3.W)  // left
  val R = 3.U(3.W)  // right
  val F = 4.U(3.W)  // forward
  val B = 5.U(3.W)  // backward

  // Position register - initialize with start value (or 0 if invalid)
  val initPos = Mux(io.start > 26.U || io.start === 13.U, 0.U, io.start)
  val posReg = RegInit(initPos)
  
  // Visited array - 27 elements, initialize with only start position visited
  val visited = RegInit(VecInit(Seq.fill(27)(false.B)))
  
  // Set initial position as visited
  visited(initPos) := true.B
  
  // Compute the residue of a 5-bit number mod 3
  def resMod3(n: UInt): UInt = {
    val result = Wire(UInt(2.W))
    result := MuxCase(2.U(2.W), Seq(
      (n === 0.U || n === 3.U || n === 6.U || n === 9.U || n === 12.U || 
       n === 15.U || n === 18.U || n === 21.U || n === 24.U) -> 0.U,
      (n === 1.U || n === 4.U || n === 7.U || n === 10.U || n === 13.U || 
       n === 16.U || n === 19.U || n === 22.U || n === 25.U) -> 1.U
    ))
    result
  }

  // Compute the residue of a 5-bit number mod 9
  def resMod9(n: UInt): UInt = {
    val result = Wire(UInt(4.W))
    result := MuxCase(8.U(4.W), Seq(
      (n === 0.U || n === 9.U || n === 18.U) -> 0.U,
      (n === 1.U || n === 10.U || n === 19.U) -> 1.U,
      (n === 2.U || n === 11.U || n === 20.U) -> 2.U,
      (n === 3.U || n === 12.U || n === 21.U) -> 3.U,
      (n === 4.U || n === 13.U || n === 22.U) -> 4.U,
      (n === 5.U || n === 14.U || n === 23.U) -> 5.U,
      (n === 6.U || n === 15.U || n === 24.U) -> 6.U,
      (n === 7.U || n === 16.U || n === 25.U) -> 7.U
    ))
    result
  }

  // Compute next position
  def next(current: UInt, where: UInt): UInt = {
    val result = Wire(UInt(5.W))
    result := current
    
    when(where === U) {
      when(current > 8.U) {
        result := current - 9.U
      }
    }.elsewhen(where === D) {
      when(current < 18.U) {
        result := current + 9.U
      }
    }.elsewhen(where === L) {
      when(resMod3(current) =/= 0.U) {
        result := current - 1.U
      }
    }.elsewhen(where === R) {
      when(resMod3(current) =/= 2.U) {
        result := current + 1.U
      }
    }.elsewhen(where === F) {
      when(resMod9(current) > 2.U) {
        result := current - 3.U
      }
    }.elsewhen(where === B) {
      when(resMod9(current) < 6.U) {
        result := current + 3.U
      }
    }
    
    result
  }

  // Compute destination
  val dest = Wire(UInt(5.W))
  dest := next(posReg, io.dir)

  // Sequential logic - update position and visited array
  when(!visited(dest)) {
    posReg := dest
    visited(dest) := true.B
  }

  // Connect outputs
  io.pos := posReg
  io.visited := visited
  io.dest := dest
}

object VerilogGenerator extends App {
  emitVerilog(new cube(), args)
}