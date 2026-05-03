package llmverify
import chisel3._
import chisel3.util._

class am2901 extends Module {
  val io = IO(new Bundle {
    val I = Input(UInt(9.W))
    val Aadd = Input(UInt(4.W))
    val Badd = Input(UInt(4.W))
    val D = Input(UInt(4.W))
    val Y = Output(UInt(4.W))
    val RAM0in = Input(Bool())
    val RAM0out = Output(Bool())
    val RAM3in = Input(Bool())
    val RAM3out = Output(Bool())
    val Q0in = Input(Bool())
    val Q0out = Output(Bool())
    val Q3in = Input(Bool())
    val Q3out = Output(Bool())
    val C0 = Input(Bool())
    val OEbar = Input(Bool())
    val C4 = Output(Bool())
    val Gbar = Output(Bool())
    val Pbar = Output(Bool())
    val OVR = Output(Bool())
    val F3 = Output(Bool())
    val F30 = Output(Bool())
  })

  // RAM array (16 x 4-bit)
  val RAM = RegInit(VecInit(Seq.fill(16)(0.U(4.W))))
  
  // Q register (4-bit)
  val Q = RegInit(0.U(4.W))

  // ALU inputs
  val A = RAM(io.Aadd)
  val B = RAM(io.Badd)

  // RE source selection
  val RE = Mux(io.I(2,1) === 0.U, A,
    Mux(io.I(2) === 1.U && io.I(1,0) =/= 0.U, io.D, 0.U))

  // S source selection
  val S = Mux(io.I(2,1) === 2.U, A,
    Mux(io.I(2) === 0.U && io.I(0) === 1.U, B,
      Mux(io.I(2,0) === 7.U, 0.U, Q)))

  // ALU extended operands (5-bit for carry computation)
  val R_ext = Mux(io.I(5,3) === 1.U, Cat(0.U(1.W), ~RE), Cat(0.U(1.W), RE))
  val S_ext = Mux(io.I(5,3) === 2.U, Cat(0.U(1.W), ~S), Cat(0.U(1.W), S))

  // ALU function selection - fix MuxLookup syntax
  val result = MuxCase(0.U(5.W), Seq(
    // Addition operations (I[5] = 0, I[4:3] != 3)
    (io.I(5,3) === 0.U) -> (R_ext + S_ext + Cat(0.U(4.W), io.C0)),
    (io.I(5,3) === 1.U) -> (R_ext + S_ext + Cat(0.U(4.W), io.C0)),
    (io.I(5,3) === 2.U) -> (R_ext + S_ext + Cat(0.U(4.W), io.C0)),
    // OR operation
    (io.I(5,3) === 3.U) -> (R_ext | S_ext),
    // AND operation
    (io.I(5,3) === 4.U) -> (R_ext & S_ext),
    // AND with complement of R
    (io.I(5,3) === 5.U) -> (~R_ext & S_ext),
    // XOR operation
    (io.I(5,3) === 6.U) -> (R_ext ^ S_ext),
    // XOR with complement of R
    (io.I(5,3) === 7.U) -> (~R_ext ^ S_ext)
  ))

  // ALU outputs
  val F = result(3,0)
  io.OVR := (~(R_ext(3) ^ S_ext(3))) & (R_ext(3) ^ result(3))
  io.C4 := result(4)
  io.F3 := result(3)
  io.F30 := ~result.orR

  // Carry lookahead computation
  val temp_p = R_ext | S_ext
  val temp_g = R_ext & S_ext
  io.Pbar := ~temp_p(3,0).andR
  io.Gbar := ~(temp_g(3) |
    (temp_p(3) & temp_g(3)) |
    (temp_p(3,2).andR & temp_g(1)) |
    (temp_p(3,1).andR & temp_g(0)))

  // RAM write operations
  when(io.I(8,7) === 1.U) {
    RAM(io.Badd) := F
  }.elsewhen(io.I(8,7) === 2.U) {
    RAM(io.Badd) := Cat(io.RAM3in, F(3,1))
  }.elsewhen(io.I(8,7) === 3.U) {
    RAM(io.Badd) := Cat(F(2,0), io.RAM0in)
  }

  // Q register write operations
  when(io.I(8,6) === 0.U) {
    Q := F
  }.elsewhen(io.I(8,6) === 4.U) {
    Q := Cat(io.Q3in, Q(3,1))
  }.elsewhen(io.I(8,6) === 6.U) {
    Q := Cat(Q(2,0), io.Q0in)
  }

  // Output and shifter block
  io.Y := Mux(io.I(8,6) === 2.U, A, F)
  io.RAM0out := F(0)
  io.RAM3out := F(3)
  io.Q3out := Q(3)
  io.Q0out := Q(0)
}

object VerilogGenerator extends App {
  emitVerilog(new am2901(), args)
}