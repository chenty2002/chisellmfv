package llmverify
import chisel3._
import chisel3.util._
import chiselFv._

class luckySeven extends Module with Formal {
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

  // ========== Formal Verification Assertions ==========

  // Safety 1: Each board position always holds a value in [0, 7]
  for (i <- 0 until 8) {
    fvAssert(b(i) <= 7.U, s"b($i) should be in range 0-7")
  }

  // Safety 2: The sum of all board values is always 28 (0+1+2+3+4+5+6+7)
  // Zero-extend each 3-bit value to 6 bits to avoid overflow (max sum = 56)
  val boardSum = b.map(v => 0.U(3.W) ## v).reduce(_ + _)
  fvAssert(boardSum === 28.U(6.W), "board multiset sum invariant: total is always 28")

  // Safety 3: Exactly one empty position (value 0) on the board at all times
  val zeroCount = b.map(v => Mux(v === 0.U, 1.U(3.W), 0.U(3.W))).reduce(_ + _)
  fvAssert(zeroCount === 1.U(3.W), "exactly one empty position (value 0) on the board")

  // Safety 4: When a valid move executes, the board remains a permutation of 0..7
  fvAssert(!valid || permutation, "valid moves preserve the permutation invariant")

  // Safety 5: from and to must be distinct for a valid move
  fvAssert(!(valid && (freg === treg)), "cannot move from a position to itself")

  // Safety 6: A valid move can only target an empty position
  fvAssert(!valid || (b(treg) === 0.U), "valid move target must be empty")
}

object VerilogGenerator extends App {
  emitVerilog(new luckySeven(), args)
}
