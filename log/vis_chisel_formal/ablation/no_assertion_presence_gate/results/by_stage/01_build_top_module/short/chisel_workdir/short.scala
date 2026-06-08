package llmverify
import chisel3._
import chisel3.util._
import java.io.{File, PrintWriter}

class short extends Module {
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
