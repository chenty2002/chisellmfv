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

  // ============================================================
  // FORMAL ASSERTIONS
  // ============================================================

  // Safety: count must always be bounded by the total number of buffers (0 to 16)
  fvAssert(count <= 16.U, "count_never_exceeds_16")

  // Safety: count must match the exact number of busy entries at all times
  // This checks internal consistency between the count register and the busy vector
  fvAssert(count === PopCount(busy.asUInt), "count_matches_popcount_of_busy")

  // Safety: nack may only be asserted when alloc is true AND all buffers are busy
  fvAssert(!io.nack || (alloc && count === 16.U), "nack_implies_alloc_and_full")

  // Safety: when allocation succeeds, the allocated address must point to a free entry
  fvAssert(!(alloc && !io.nack) || !busy(io.alloc_addr), "alloc_target_is_free")

  // Safety: a successful allocation implies the buffer pool was not full
  fvAssert(!(alloc && !io.nack) || count < 16.U, "alloc_success_implies_not_full")

  // Bounded liveness: every alloc_raw request, when the buffer is not full,
  // is followed by a successful allocation within 2 cycles
  // (alloc_raw is registered, so alloc appears 1 cycle later, and allocation
  //  completes in that same cycle when !nack)
  astRelaxedLiveness(
    io.alloc_raw && count < 16.U,
    alloc && !io.nack,
    2,
    "alloc_request_eventually_served"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new buffer_alloc(), args)
}
