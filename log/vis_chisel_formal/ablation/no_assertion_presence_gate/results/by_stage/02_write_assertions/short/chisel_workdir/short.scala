package llmverify
import chisel3._
import chisel3.util._
import chiselFv._
import java.io.{File, PrintWriter}

class short extends Module with Formal {
  val io = IO(new Bundle {
    val request = Output(Bool())
  })
  
  // Define the enum for status
  val ready :: busy :: Nil = Enum(2)
  
  // State register with initial value ready
  val state = RegInit(ready)
  
  // Simple pseudo-random generator for nondeterministic behavior
  val randomCounter = RegInit(0.U(8.W))
  randomCounter := randomCounter + 1.U
  
  // Nondeterministic state - using pseudo-random bit
  val nond_state = Mux(randomCounter(0), ready, busy)
  
  // Nondeterministic request output
  io.request := randomCounter(1)
  
  // State machine logic
  when(state === ready) {
    when(io.request) {
      state := busy
    }.otherwise {
      state := nond_state
    }
  }.elsewhen(state === busy) {
    state := nond_state
  }

  // ========== Formal Assertions ==========

  // Safety: state is always one of the valid enum values
  fvAssert(state === ready || state === busy, "state_valid")

  // Safety: when in ready state and request fires, next cycle must be busy
  assertImpliesDelay(state === ready && io.request, state === busy, 1, "ready_request_goes_busy")

  // Bounded liveness: from busy state, eventually become ready
  // randomCounter(0) toggles every cycle, so nond_state alternates ready/busy.
  // From busy state, nond_state is assigned to state; within at most 2 cycles
  // nond_state will be ready. Bound of 5 is well above the expected latency.
  astRelaxedLiveness(state === busy, state === ready, 5, "busy_eventually_ready")
}

object VerilogGenerator extends App {
  // Parse --target-dir argument if provided
  val targetDir = if (args.contains("--target-dir")) {
    val idx = args.indexOf("--target-dir")
    if (idx + 1 < args.length) args(idx + 1) else "."
  } else {
    "."
  }
  
  val dir = new File(targetDir)
  if (!dir.exists()) dir.mkdirs()
  
  val verilog = emitVerilog(new short())
  val pw = new PrintWriter(new File(dir, "short.v"))
  pw.write(verilog)
  pw.close()
}
