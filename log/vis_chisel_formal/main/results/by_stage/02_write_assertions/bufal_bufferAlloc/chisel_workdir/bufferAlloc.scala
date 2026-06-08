package llmverify
import chisel3._
import chisel3.util._
import chiselFv._

class buffer_alloc extends Module with Formal {
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

  // Register inputs on clock edge
  alloc := io.alloc_raw
  free := io.free_raw
  free_addr := io.free_addr_raw

  // Generate nack when all buffers are busy
  io.nack := alloc && (count === 16.U)

  // Priority encoder to find first free buffer
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

  // Update count and busy flags
  count := count + (alloc && !io.nack).asUInt - (free && busy(free_addr)).asUInt

  when(free) {
    busy(free_addr) := false.B
  }

  when(alloc && !io.nack) {
    busy(io.alloc_addr) := true.B
  }

  // === Formal Verification Assertions ===

  // Safety: count must always equal the number of busy buffers (key invariant)
  fvAssert(count === PopCount(busy.asUInt), "count_eq_popcount")

  // Safety: count must never exceed the total number of buffers (16)
  fvAssert(count <= 16.U, "count_upper_bound")

  // Safety: when allocation succeeds (not nacked), the allocated buffer must be free
  fvAssert(!(alloc && !io.nack) || !busy(io.alloc_addr), "alloc_addr_is_free")

  // Safety: nack must not fire when buffers are available
  fvAssert(!(alloc && count < 16.U) || !io.nack, "no_false_nack")

  // Bounded liveness: when alloc is requested and buffers are free,
  // the request makes progress within 4 cycles (either alloc completes or nack fires)
  astRelaxedLiveness(alloc && count < 16.U, io.nack || !alloc, 4, "alloc_progress")
}

object VerilogGenerator extends App {
  emitVerilog(new buffer_alloc(), args)
}
