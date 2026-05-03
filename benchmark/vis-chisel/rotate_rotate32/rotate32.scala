package llmverify
import chisel3._
import chisel3.util._

class rotate extends Module {
  val io = IO(new Bundle {
    val amount = Input(UInt(5.W))
    val din = Input(UInt(32.W))
    val dout = Output(UInt(32.W))
  })

  // Register to hold input data
  val inr = RegInit(0.U(32.W))
  
  // Barrel shifter implementation
  val tmp0 = inr
  val tmp1 = Mux(io.amount(0), Cat(tmp0(0), tmp0(31,1)), tmp0)
  val tmp2 = Mux(io.amount(1), Cat(tmp1(1,0), tmp1(31,2)), tmp1)
  val tmp3 = Mux(io.amount(2), Cat(tmp2(3,0), tmp2(31,4)), tmp2)
  val tmp4 = Mux(io.amount(3), Cat(tmp3(7,0), tmp3(31,8)), tmp3)
  val tmp5 = Mux(io.amount(4), Cat(tmp4(15,0), tmp4(31,16)), tmp4)
  
  // Output register
  val dout = RegInit(0.U(32.W))
  
  // Sequential logic
  inr := io.din
  dout := tmp5
  
  io.dout := dout
}

object VerilogGenerator extends App {
  emitVerilog(new rotate(), args)
}