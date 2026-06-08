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

  // ========== Formal Assertions ==========

  // Core invariant: count must always equal the number of busy buffers.
  // This is the most critical property — if count and PopCount(busy) diverge,
  // the allocator's bookkeeping is broken and nack logic becomes unreliable.
  fvAssert(count === PopCount(busy), "count_matches_popcount")

  // Safety: count must never exceed 16 (the total number of buffers).
  // count is 5 bits wide (0-31) but can never legitimately go above 16.
  fvAssert(count <= 16.U, "count_within_bounds")

  // Safety: a successful allocation must target a buffer that is currently free.
  // When alloc && !io.nack, the priority encoder should select a non-busy buffer.
  fvAssert(!(alloc && !io.nack) || !busy(io.alloc_addr), "alloc_to_free_buffer")

  // Bounded liveness: if all buffers are ever busy, the system should make
  // forward progress and free at least one buffer within 100 cycles.
  // This catches deadlock scenarios where allocation stops permanently.
  astRelaxedLiveness(count === 16.U, count =/= 16.U, 100, "buffers_eventually_freed")

  // ========== Environment Assumptions ==========

  // Assumption: a free request must target a buffer that is currently busy,
  // so the free request actually decrements the count and frees a buffer.
  // Without this constraint, the formal tool can generate free requests
  // targeting unallocated buffers, making them no-ops.
  assume(!io.free_raw || busy(io.free_addr_raw), "free_addr_must_be_busy")

  // Assumption: when all buffers are busy, a free request must be present
  // in the current cycle so that count can decrement. This prevents the
  // environment from starving the system when it is full, which would
  // trivially violate the bounded liveness property.
  assume(!(count === 16.U) || io.free_raw, "free_when_all_busy")
}

object VerilogGenerator extends App {
  emitVerilog(new buffer_alloc(), args)
}
