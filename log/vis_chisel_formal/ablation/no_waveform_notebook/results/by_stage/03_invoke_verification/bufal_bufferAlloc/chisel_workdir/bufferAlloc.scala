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

  // ===== Formal Verification Assertions =====

  // Safety 1: count must always equal the number of busy buffers (popcount).
  // This is the most critical invariant — if count drifts from the true count,
  // allocation/free tracking is broken.
  fvAssert(count === PopCount(busy.asUInt), "count_eq_popcount")

  // Safety 2: nack is asserted iff all 16 buffers are busy AND alloc is active.
  fvAssert(io.nack === (alloc && count === 16.U), "nack_correct")

  // Safety 3: on a successful allocation, the allocated address points to a
  // buffer that is currently free (not busy in the current cycle).
  fvAssert(!(alloc && !io.nack) || !busy(io.alloc_addr), "alloc_addr_free")

  // Safety 4: count must never exceed 16 (the total number of buffer slots).
  fvAssert(count <= 16.U, "count_max_16")

  // Safety 5: if a buffer being freed is currently busy, count must be > 0.
  // (count == 0 with a busy-buffer free would indicate a count inconsistency.)
  fvAssert(!(free && busy(free_addr)) || count > 0.U, "free_busy_count_positive")

  // Liveness 6: when a free request targets a busy buffer, that buffer becomes
  // non-busy within 3 cycles (the register update takes effect next cycle).
  astRelaxedLiveness(free && busy(free_addr), !busy(free_addr), 3, "free_progress")

  // Liveness 7: when an allocation succeeds, the allocated buffer becomes busy
  // within 3 cycles (the register update takes effect next cycle).
  astRelaxedLiveness(alloc && !io.nack, busy(io.alloc_addr), 3, "alloc_progress")
}

object VerilogGenerator extends App {
  emitVerilog(new buffer_alloc(), args)
}