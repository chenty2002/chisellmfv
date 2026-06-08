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
  when(!io.rst) {
    rst_cnt := 0.U
  }.elsewhen(io.LineState_o =/= 0.U) {
    rst_cnt := 0.U
  }.elsewhen(!usb_rst_reg && fs_ce) {
    rst_cnt := rst_cnt + 1.U
  }
  
  usb_rst_reg := (rst_cnt === 31.U)
  io.usb_rst := usb_rst_reg

  // ========== Formal Verification Assertions ==========

  // Safety: rst_cnt is a 5-bit counter whose terminal value is 31; it must never exceed 31
  fvAssert(rst_cnt <= 31.U, "rst_cnt_valid_range")

  // Safety: usb_rst_reg must correctly reflect the terminal-count comparison
  fvAssert(usb_rst_reg === (rst_cnt === 31.U), "usb_rst_reg_definition")

  // Safety: USB D+ (txdp) and D- (txdn) differential signals must never both be high
  fvAssert(!(io.txdp && io.txdn), "txdp_txdn_exclusive")

  // Safety: The 2-bit line state encoding must never be 0b11 (reserved/invalid in USB)
  fvAssert(io.LineState_o =/= 3.U, "line_state_valid_encoding")

  // Safety: Once usb_rst_reg is asserted and the reset conditions remain (rst high,
  // line idle), the register must hold its value on the next cycle (avoids glitches
  // or unintended deassertion)
  assertImpliesDelay(
    usb_rst_reg && io.rst && io.LineState_o === 0.U,
    usb_rst_reg,
    1,
    "usb_rst_reg_persistence"
  )

  // Liveness/Progress: When the main reset is active and the USB line stays in SE0
  // state (LineState_o === 0), the USB reset signal should assert within 40 cycles.
  // Bounded by the 5-bit counter's max range (31) plus margin.
  astRelaxedLiveness(
    io.rst && io.LineState_o === 0.U && !usb_rst_reg,
    usb_rst_reg,
    40,
    "usb_rst_eventually_asserted"
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
