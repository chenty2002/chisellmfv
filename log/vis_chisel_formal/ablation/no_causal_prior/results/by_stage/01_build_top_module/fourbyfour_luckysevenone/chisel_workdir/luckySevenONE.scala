package llmverify
import chisel3._
import chisel3.util._

class luckySeven extends Module {
  val io = IO(new Bundle {
    val from = Input(UInt(3.W))
    val to = Input(UInt(3.W))
    // Add outputs to preserve the design
    val b_out = Output(Vec(8, UInt(3.W)))
    val valid_out = Output(Bool())
    val permutation_out = Output(Bool())
  })
  
  // Internal registers
  val b = RegInit(VecInit(Seq.tabulate(8)(i => i.U(3.W))))
  val freg = RegInit(0.U(3.W))
  val treg = RegInit(0.U(3.W))
  
  // Latch inputs on clock edge
  freg := io.from
  treg := io.to
  
  // Valid move predicate
  val valid = (b(treg) === 0.U) && (
    (treg === (freg + 1.U)) || 
    (freg === (treg + 1.U)) || 
    (treg(1,0) === 0.U && freg(1,0) === 0.U && freg(2) =/= treg(2))
  )
  
  // Permutation check ensures all board values are unique
  val permutation = b(0) =/= b(1) && b(0) =/= b(2) && b(0) =/= b(3) &&
    b(0) =/= b(4) && b(0) =/= b(5) && b(0) =/= b(6) && b(0) =/= b(7) &&
    b(1) =/= b(2) && b(1) =/= b(3) && b(1) =/= b(4) && b(1) =/= b(5) &&
    b(1) =/= b(6) && b(1) =/= b(7) && b(2) =/= b(3) && b(2) =/= b(4) &&
    b(2) =/= b(5) && b(2) =/= b(6) && b(2) =/= b(7) && b(3) =/= b(4) &&
    b(3) =/= b(5) && b(3) =/= b(6) && b(3) =/= b(7) && b(4) =/= b(5) &&
    b(4) =/= b(6) && b(4) =/= b(7) && b(5) =/= b(6) && b(5) =/= b(7) &&
    b(6) =/= b(7)
  
  // Perform move if valid
  when(valid) {
    b(treg) := b(freg)
    b(freg) := 0.U
  }
  
  // Connect outputs
  io.b_out := b
  io.valid_out := valid
  io.permutation_out := permutation
}

object VerilogGenerator extends App {
  emitVerilog(new luckySeven(), args)
}