package llmverify

import chisel3._
import chisel3.util._

class buffer_alloc extends Module {
  val io = IO(new Bundle {
    val alloc_raw = Input(Bool())
    val nack = Output(Bool())
    val alloc_addr = Output(UInt(4.W))
    val free_raw = Input(Bool())
    val free_addr_raw = Input(UInt(4.W))
  })

  // Internal registers
  val busy = RegInit(VecInit(Seq.fill(16)(false.B)))
  val count = RegInit(0.U(5.W))
  val alloc = RegInit(false.B)
  val free = RegInit(false.B)
  val free_addr = RegInit(0.U(4.W))

  // Combinational logic for nack
  io.nack := alloc && (count === 16.U)

  // Priority encoder for alloc_addr
  io.alloc_addr := MuxCase(0.U, Seq(
    (!busy(0)) -> 0.U,
    (!busy(1)) -> 1.U,
    (!busy(2)) -> 2.U,
    (!busy(3)) -> 3.U,
    (!busy(4)) -> 4.U,
    (!busy(5)) -> 5.U,
    (!busy(6)) -> 6.U,
    (!busy(7)) -> 7.U,
    (!busy(8)) -> 8.U,
    (!busy(9)) -> 9.U,
    (!busy(10)) -> 10.U,
    (!busy(11)) -> 11.U,
    (!busy(12)) -> 12.U,
    (!busy(13)) -> 13.U,
    (!busy(14)) -> 14.U,
    (!busy(15)) -> 15.U
  ))

  // Sequential logic
  when(this.reset.asBool) {
    // Initialize all busy bits to 0
    for (i <- 0 until 16) {
      busy(i) := false.B
    }
    count := 0.U
    alloc := false.B
    free := false.B
    free_addr := 0.U
  }.otherwise {
    // Sample inputs
    alloc := io.alloc_raw
    free := io.free_raw
    free_addr := io.free_addr_raw

    // Update count
    val alloc_increment = alloc && !io.nack
    val free_decrement = free && busy(free_addr)
    count := count + alloc_increment.asUInt - free_decrement.asUInt

    // Update busy array
    when(free) {
      busy(free_addr) := false.B
    }
    when(alloc && !io.nack) {
      busy(io.alloc_addr) := true.B
    }
  }
}

object VerilogGenerator extends App {
  emitVerilog(new buffer_alloc(), args)
}