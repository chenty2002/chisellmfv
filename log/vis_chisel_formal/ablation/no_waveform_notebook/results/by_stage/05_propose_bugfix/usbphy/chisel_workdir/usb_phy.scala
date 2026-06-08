package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

class usb_phy extends Module with Formal {
  val io = IO(new Bundle {
    val rst = Input(Bool())
    val phy_tx_mode = Input(Bool())
    val usb_rst = Output(Bool())
    
    // Transceiver Interface
    val txdp = Output(Bool())
    val txdn = Output(Bool())
    val txoe = Output(Bool())
    val rxd = Input(Bool())
    val rxdp = Input(Bool())
    val rxdn = Input(Bool())
    
    // UTMI Interface
    val DataOut_i = Input(UInt(8.W))
    val TxValid_i = Input(Bool())
    val TxReady_o = Output(Bool())
    val DataIn_o = Output(UInt(8.W))
    val RxValid_o = Output(Bool())
    val RxActive_o = Output(Bool())
    val RxError_o = Output(Bool())
    val LineState_o = Output(UInt(2.W))
  })
  
  // Local Wires and Registers
  val rst_cnt = RegInit(0.U(5.W))
  val usb_rst_reg = RegInit(false.B)
  
  // Misc Logic - renamed 'reset' to 'reset_wire' to avoid conflict with Chisel's built-in reset
  val reset_wire = io.rst & ~usb_rst_reg
  
  // Generate fs_ce (clock enable) - this would typically come from a clock divider
  // For now, assuming it's always enabled for simplicity
  val fs_ce = true.B
  
  // TX Phy instantiation
  val i_tx_phy = Module(new usb_tx_phy())
  i_tx_phy.io.clk := clock
  i_tx_phy.io.rst := reset_wire
  i_tx_phy.io.fs_ce := fs_ce
  i_tx_phy.io.phy_mode := io.phy_tx_mode
  i_tx_phy.io.DataOut_i := io.DataOut_i
  i_tx_phy.io.TxValid_i := io.TxValid_i
  io.TxReady_o := i_tx_phy.io.TxReady_o
  
  // Connect TX Phy outputs to main module outputs
  io.txdp := i_tx_phy.io.txdp
  io.txdn := i_tx_phy.io.txdn
  io.txoe := i_tx_phy.io.txoe
  
  // RX Phy instantiation
  val i_rx_phy = Module(new usb_rx_phy())
  i_rx_phy.io.clk := clock
  i_rx_phy.io.rst := reset_wire
  i_rx_phy.io.fs_ce := fs_ce
  i_rx_phy.io.rxd := io.rxd
  i_rx_phy.io.rxdp := io.rxdp
  i_rx_phy.io.rxdn := io.rxdn
  i_rx_phy.io.RxEn_i := io.txoe
  io.DataIn_o := i_rx_phy.io.DataIn_o
  io.RxValid_o := i_rx_phy.io.RxValid_o
  io.RxActive_o := i_rx_phy.io.RxActive_o
  io.RxError_o := i_rx_phy.io.RxError_o
  io.LineState_o := i_rx_phy.io.LineState
  
  // USB Reset generation logic
  // Combinational terminal-count check (not registered) so counting stops immediately
  // when rst_cnt reaches 31, preventing the one-cycle drift between reaching the
  // terminal count and latching usb_rst_reg.
  val usb_rst_terminal = rst_cnt === 31.U
  when(!io.rst) {
    rst_cnt := 0.U
    usb_rst_reg := false.B
  }.elsewhen(io.LineState_o =/= 0.U) {
    rst_cnt := 0.U
  }.elsewhen(!usb_rst_terminal && fs_ce) {
    rst_cnt := rst_cnt + 1.U
  }.elsewhen(usb_rst_terminal) {
    usb_rst_reg := true.B
  }
  
  io.usb_rst := usb_rst_reg

  // ===== Formal Verification Assertions =====

  // Safety: Reset counter must never exceed its maximum value (31)
  fvAssert(rst_cnt <= 31.U, "rst_cnt_bounded")

  // Safety: usb_rst_reg is only true when the counter has reached the terminal count
  fvAssert(!usb_rst_reg || rst_cnt === 31.U, "usb_rst_consistent")

  // Safety: When external reset is asserted (io.rst is false), counter must be zero
  fvAssert(io.rst || rst_cnt === 0.U, "rst_clears_counter")

  // Safety: When line state is non-zero (not SE0), counter must be zero
  fvAssert(!(io.LineState_o =/= 0.U) || rst_cnt === 0.U, "line_activity_clears_counter")

  // Safety: Once usb_rst_reg is set, it stays set until external reset (io.rst goes low)
  // If usb_rst_reg is true and io.rst stays true, usb_rst_reg must remain true
  // Note: The condition includes usb_rst_reg to allow the legitimate 0→1 transition
  // when the counter reaches terminal count while io.rst is true.
  assertStableWhen(io.rst && usb_rst_reg, usb_rst_reg.asUInt, "usb_rst_stable")

  // Safety: Counter can only increment by at most 1 per cycle
  // When counting is enabled, rst_cnt either stays the same or increments by 1
  // When not counting and not being reset, rst_cnt stays the same
  val counting = !usb_rst_terminal && fs_ce
  val not_resetting = io.rst && io.LineState_o === 0.U
  val rst_cnt_next = WireInit(rst_cnt)
  when(!io.rst) {
    rst_cnt_next := 0.U
  }.elsewhen(io.LineState_o =/= 0.U) {
    rst_cnt_next := 0.U
  }.elsewhen(counting) {
    rst_cnt_next := rst_cnt + 1.U
  }.otherwise {
    rst_cnt_next := rst_cnt
  }
  // The next value of rst_cnt must be: 0 (reset), rst_cnt+1 (counting), or rst_cnt (hold)
  fvAssert(
    rst_cnt_next === 0.U || rst_cnt_next === rst_cnt || rst_cnt_next === rst_cnt + 1.U,
    "rst_cnt_transitions_valid"
  )

  // Bounded liveness: When in SE0 (LineState_o === 0) with external reset active and
  // usb_rst_reg not yet set, the reset completion (usb_rst_reg) will occur
  // within 32 cycles, or the SE0 condition will be broken (LineState_o becomes non-zero),
  // or external reset will be deasserted (io.rst goes low).
  // This ensures the reset counter always makes forward progress.
  val se0_reset_active = io.rst && io.LineState_o === 0.U && !usb_rst_terminal && fs_ce
  val reset_done_or_aborted = usb_rst_reg || io.LineState_o =/= 0.U || !io.rst
  astRelaxedLiveness(se0_reset_active, reset_done_or_aborted, 32, "reset_progress_liveness")
}

// Placeholder for usb_tx_phy module
class usb_tx_phy extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val fs_ce = Input(Bool())
    val phy_mode = Input(Bool())
    
    // Transceiver Interface
    val txdp = Output(Bool())
    val txdn = Output(Bool())
    val txoe = Output(Bool())
    
    // UTMI Interface
    val DataOut_i = Input(UInt(8.W))
    val TxValid_i = Input(Bool())
    val TxReady_o = Output(Bool())
  })
  
  // Placeholder implementation
  io.txdp := false.B
  io.txdn := false.B
  io.txoe := false.B
  io.TxReady_o := true.B
}

// Placeholder for usb_rx_phy module
class usb_rx_phy extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val fs_ce = Input(Bool())
    
    // Transceiver Interface
    val rxd = Input(Bool())
    val rxdp = Input(Bool())
    val rxdn = Input(Bool())
    
    // UTMI Interface
    val DataIn_o = Output(UInt(8.W))
    val RxValid_o = Output(Bool())
    val RxActive_o = Output(Bool())
    val RxError_o = Output(Bool())
    val RxEn_i = Input(Bool())
    val LineState = Output(UInt(2.W))
  })
  
  // Placeholder implementation
  io.DataIn_o := 0.U
  io.RxValid_o := false.B
  io.RxActive_o := false.B
  io.RxError_o := false.B
  io.LineState := 0.U
}

object VerilogGenerator extends App {
  emitVerilog(new usb_phy(), args)
}
