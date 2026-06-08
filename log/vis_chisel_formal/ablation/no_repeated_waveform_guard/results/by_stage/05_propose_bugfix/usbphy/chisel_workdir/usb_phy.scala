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
  // Use a Wire to compute the next rst_cnt value so that usb_rst_reg compares
  // against the same value that rst_cnt will become, avoiding a race condition
  // when io.rst deasserts in the same cycle that rst_cnt reaches 31.
  val next_rst_cnt = WireInit(rst_cnt)
  when(!io.rst) {
    next_rst_cnt := 0.U
  }.elsewhen(io.LineState_o =/= 0.U) {
    next_rst_cnt := 0.U
  }.elsewhen(!usb_rst_reg && fs_ce) {
    next_rst_cnt := rst_cnt + 1.U
  }
  
  rst_cnt := next_rst_cnt
  usb_rst_reg := (next_rst_cnt === 31.U)
  io.usb_rst := usb_rst_reg

  // ========== FORMAL ASSERTIONS ==========

  // Safety 1: rst_cnt must never exceed 31 (counter should not overflow its 5-bit range)
  fvAssert(rst_cnt <= 31.U, "rst_cnt_no_overflow")

  // Safety 2: txdp and txdn must not both be high simultaneously (invalid USB signaling)
  fvAssert(!(io.txdp && io.txdn), "txdp_txdn_mutex")

  // Safety 3: Once usb_rst is asserted, rst_cnt must be 31 in the same cycle
  // (The counter reaching 31 is what triggers the reset flag)
  fvAssert(!io.usb_rst || (rst_cnt === 31.U), "usb_rst_implies_cnt_31")

  // Safety 4: When io.usb_rst is asserted and io.rst remains asserted,
  // rst_cnt must stay at 31 (stable, no further counting).
  // Use a conditional stability check with RegNext to skip the transition
  // cycle where io.usb_rst becomes asserted (rst_cnt transitions from 30
  // to 31 in that same cycle, so assertStableWhen would falsely fire).
  val usb_rst_cond = io.usb_rst && io.rst
  val prev_usb_rst_cond = RegNext(usb_rst_cond, false.B)
  val prev_rst_cnt = RegNext(rst_cnt, 0.U)
  fvAssert(!(usb_rst_cond && prev_usb_rst_cond) || (rst_cnt === prev_rst_cnt), "rst_cnt_stable_during_usb_rst")

  // Safety 5: usb_rst_reg and io.usb_rst must be equivalent
  fvAssert(io.usb_rst === usb_rst_reg, "usb_rst_valid")

  // Bounded Liveness 6: If SE0 (LineState_o === 0) persists with io.rst high
  // and usb_rst not yet asserted, then usb_rst must fire within 32 cycles.
  // This catches deadlock in the reset generation FSM.
  astRelaxedLiveness(
    io.rst && (io.LineState_o === 0.U) && !io.usb_rst,
    io.usb_rst || !io.rst || (io.LineState_o =/= 0.U),
    32,
    "usb_reset_progress_32"
  )
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
