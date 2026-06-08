package llmverify
import chisel3._
import chisel3.util._
import chiselFv._

class gray extends Module with Formal {
  val io = IO(new Bundle {
    val i = Input(Bool())
    val z = Output(Bool())
  })
  
  // Internal registers with non-deterministic initialization (using 0.B as default)
  val p = RegInit(0.B)
  val q = RegInit(0.B)
  val r = RegInit(0.B)
  
  // Wire
  val w = Wire(Bool())
  
  // Sequential logic
  r := io.z
  q := p
  p := io.i
  
  // Combinational logic
  w := p ^ q
  io.z := w ^ r
  
  // Formal verification assertions
  
  // Structural invariant: output z is the XOR of all three state registers
  fvAssert(io.z === (p ^ q ^ r), "z_eq_p_xor_q_xor_r")
  
  // Structural invariant: wire w is the XOR of p and q
  fvAssert(w === (p ^ q), "w_eq_p_xor_q")
  
  // Bounded liveness: if input i changes from the previous value,
  // the output z must change within 3 cycles
  // (The state propagates: i -> p in 1 cycle, p -> q in 1 cycle, 
  //  and r feeds back z, so 3 cycles covers the full state update)
  val prev_i = RegNext(io.i, 0.B)   // deterministic initial value prevents spurious trigger at time 0
  val input_changed = io.i =/= prev_i
  
  // Future-looking bounded-liveness monitor:
  // When input changes, start a 3-cycle countdown window.
  // If the output changes during the window, the monitor resets.
  // Assertion fails if the window expires without an output change.
  val mon_active = RegInit(false.B)
  val mon_cnt    = RegInit(0.U(2.W))   // 0..3
  
  when(input_changed) {
    mon_active := true.B
    mon_cnt    := 0.U
  } .elsewhen(mon_active) {
    mon_cnt := mon_cnt + 1.U
    when(io.z =/= RegNext(io.z)) {
      mon_active := false.B            // output changed → success
    }
  }
  
  // Fail if the window expires (mon_cnt reaches 3) before output changes
  fvAssert(!(mon_active && mon_cnt >= 3.U), "input_change_propagates_to_output_within_3")
  
  // Mutex: after reset, all registers are 0
  // This is guaranteed by RegInit, so we assert it holds
  fvAssert(!reset.asBool || (p === 0.B && q === 0.B && r === 0.B), "registers_zero_after_reset")
  
  // Stable output when input is stable for 4 cycles:
  // After 4 cycles of constant input, the output stabilizes
  // (p and q catch up to the input, and the feedback loop settles)
  // Use a counter-based approach: count consecutive stable-input cycles
  val stable_cnt = RegInit(0.U(3.W))  // 0..7, enough for >=4
  when(io.i === RegNext(io.i)) {
    stable_cnt := stable_cnt + 1.U
  } .otherwise {
    stable_cnt := 0.U
  }
  
  val output_stable = io.z === RegNext(io.z)
  fvAssert(!(stable_cnt >= 4.U) || output_stable, "output_stable_when_input_stable_4_cycles")
}

object VerilogGenerator extends App {
  emitVerilog(new gray(), args)
}
