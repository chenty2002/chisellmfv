package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

class rotate extends Module with Formal {
  val io = IO(new Bundle {
    val amount = Input(UInt(2.W))
    val din = Input(UInt(4.W))
    val dout = Output(UInt(4.W))
  })

  // Registers
  val inr = RegInit(0.U(4.W))
  val dout = RegInit(0.U(4.W))

  // Combinational logic for barrel shifter
  val tmp0 = inr
  val tmp1 = Mux(io.amount(0), Cat(tmp0(0), tmp0(3, 1)), tmp0)  // Rotate right by 1 if amount[0] = 1
  val tmp2 = Mux(io.amount(1), Cat(tmp1(1, 0), tmp1(3, 2)), tmp1)  // Rotate right by 2 if amount[1] = 1

  // Sequential logic on clock edge
  dout := tmp2
  inr := io.din

  // Output assignment
  io.dout := dout

  // === Formal Verification Assertions ===

  // 1. Barrel shifter correctness: tmp2 implements the correct right rotation of inr by amount
  val refRotate = Wire(UInt(4.W))
  refRotate := Mux(io.amount === 0.U, inr,
               Mux(io.amount === 1.U, Cat(inr(0), inr(3, 1)),
               Mux(io.amount === 2.U, Cat(inr(1, 0), inr(3, 2)),
                                      Cat(inr(2, 0), inr(3)))))
  fvAssert(tmp2 === refRotate, "barrel_shifter_correct")

  // 2. Rotate by zero: when amount is 0, tmp2 preserves inr unchanged
  fvAssert((io.amount === 0.U) === (tmp2 === inr), "rotate_by_zero")

  // 3. Rotate by one: when amount is 1, tmp2 is inr rotated right by 1
  fvAssert((io.amount === 1.U) === (tmp2 === Cat(inr(0), inr(3, 1))), "rotate_by_one")

  // 4. Output stability: when both din and amount remain unchanged, dout must also remain unchanged
  assertStableWhen(
    io.din === RegNext(io.din) && io.amount === RegNext(io.amount),
    io.dout,
    "output_stable_when_inputs_stable"
  )

  // 5. Rotate preserves popcount: the number of set bits is invariant under rotation
  fvAssert(PopCount(tmp2) === PopCount(inr), "rotate_preserves_popcount")
}

object VerilogGenerator extends App {
  emitVerilog(new rotate(), args)
}
