package llmverify

import chisel3._
import chisel3.util._

/**
 * Top-level instruction cache control module
 * Instantiates ic_cntl and ibuf_ctl modules
 */
class icctl extends Module {
  val io = IO(new Bundle {
    // Inputs
    val biu_icu_ack = Input(UInt(2.W))
    val icu_addr_2_0 = Input(UInt(3.W))
    val icu_hit = Input(Bool())
    val iu_brtaken_e = Input(Bool())
    val iu_data_e_0 = Input(Bool())
    val iu_flush_e = Input(Bool())
    val iu_ic_diag_e = Input(UInt(4.W))
    val iu_psr_ice = Input(Bool())
    val iu_shift_d = Input(UInt(8.W))
    val reset_l = Input(Bool())
    val pcsu_powerdown = Input(Bool())
    val iu_psr_bm8 = Input(Bool())
    val ice_line_align = Input(Bool())
    val misc_wrd_sel = Input(Bool())
    
    // Outputs
    val next_fetch_inc = Output(UInt(4.W))
    val encod_shift_e = Output(UInt(3.W))
    val ibuf_pc_sel = Output(UInt(2.W))
    val icu_addr_sel = Output(UInt(2.W))
    val ibuf_enable = Output(Bool())
    val ic_data_sel = Output(Bool())
    val ic_dout_sel = Output(UInt(12.W))
    val icu_bypass_q = Output(Bool())
    val icu_drty_d = Output(UInt(7.W))
    val icu_itag_we = Output(Bool())
    val latch_biu_addr = Output(Bool())
    val icu_ram_we = Output(UInt(2.W))
    val icu_req = Output(Bool())
    val icu_size = Output(UInt(2.W))
    val icu_tag_sel = Output(Bool())
    val icu_tag_vld = Output(Bool())
    val icu_type = Output(UInt(4.W))
    val icu_vld_d = Output(UInt(7.W))
    val next_addr_sel = Output(UInt(4.W))
    val addr_reg_sel = Output(UInt(2.W))
    val addr_reg_enable = Output(Bool())
    val biu_addr_sel = Output(UInt(2.W))
    val diag_ld_cache_c = Output(Bool())
    val icu_in_powerdown = Output(Bool())
    val icram_powerdown = Output(Bool())
    val icu_hold = Output(Bool())
    val valid = Output(UInt(16.W))
    val fill_word_addr = Output(UInt(2.W))
    val bypass_ack = Output(Bool())
  })
  
  // Internal wires
  val ibuf_full = Wire(Bool())
  val ic_drty = Wire(Bool())
  val icu_stall = Wire(Bool())
  
  // Instantiate ic_cntl module
  val ic_cntl_inst = Module(new ic_cntl)
  ic_cntl_inst.io.iu_ic_diag_e := io.iu_ic_diag_e
  ic_cntl_inst.io.biu_icu_ack := io.biu_icu_ack
  ic_cntl_inst.io.iu_psr_ice := io.iu_psr_ice
  ic_cntl_inst.io.iu_brtaken_e := io.iu_brtaken_e
  ic_cntl_inst.io.iu_flush_e := io.iu_flush_e
  ic_cntl_inst.io.ibuf_full := ibuf_full
  ic_cntl_inst.io.icu_hit := io.icu_hit
  ic_cntl_inst.io.iu_data_e_0 := io.iu_data_e_0
  ic_cntl_inst.io.pcsu_powerdown := io.pcsu_powerdown
  ic_cntl_inst.io.iu_psr_bm8 := io.iu_psr_bm8
  ic_cntl_inst.io.misc_wrd_sel := io.misc_wrd_sel
  ic_cntl_inst.io.ice_line_align := io.ice_line_align
  ic_cntl_inst.io.reset_l := io.reset_l
  
  io.icu_req := ic_cntl_inst.io.icu_req
  io.icu_type := ic_cntl_inst.io.icu_type
  io.icu_size := ic_cntl_inst.io.icu_size
  io.icu_addr_sel := ic_cntl_inst.io.icu_addr_sel
  io.next_addr_sel := ic_cntl_inst.io.next_addr_sel
  io.addr_reg_sel := ic_cntl_inst.io.addr_reg_sel
  io.addr_reg_enable := ic_cntl_inst.io.addr_reg_enable
  io.biu_addr_sel := ic_cntl_inst.io.biu_addr_sel
  io.ic_data_sel := ic_cntl_inst.io.ic_data_sel
  io.icu_tag_sel := ic_cntl_inst.io.icu_tag_sel
  ic_drty := ic_cntl_inst.io.ic_drty
  icu_stall := ic_cntl_inst.io.icu_stall
  io.icu_tag_vld := ic_cntl_inst.io.icu_tag_vld
  io.icu_itag_we := ic_cntl_inst.io.icu_itag_we
  io.icu_ram_we := ic_cntl_inst.io.icu_ram_we
  io.icu_bypass_q := ic_cntl_inst.io.icu_bypass_q
  io.latch_biu_addr := ic_cntl_inst.io.latch_biu_addr
  io.diag_ld_cache_c := ic_cntl_inst.io.diag_ld_cache_c
  io.icu_in_powerdown := ic_cntl_inst.io.icu_in_powerdown
  io.icram_powerdown := ic_cntl_inst.io.icram_powerdown
  io.icu_hold := ic_cntl_inst.io.icu_hold
  io.next_fetch_inc := ic_cntl_inst.io.next_fetch_inc
  io.fill_word_addr := ic_cntl_inst.io.fill_word_addr
  io.bypass_ack := ic_cntl_inst.io.bypass_ack
  
  // Instantiate ibuf_ctl module
  val ibuf_ctl_inst = Module(new ibuf_ctl)
  ibuf_ctl_inst.io.ic_drty := ic_drty
  ibuf_ctl_inst.io.icu_stall := icu_stall
  ibuf_ctl_inst.io.iu_shift_d := io.iu_shift_d
  ibuf_ctl_inst.io.icu_addr_2_0 := io.icu_addr_2_0
  ibuf_ctl_inst.io.reset_l := io.reset_l
  ibuf_ctl_inst.io.jmp_e := io.iu_brtaken_e
  ibuf_ctl_inst.io.iu_psr_bm8 := io.iu_psr_bm8
  ibuf_ctl_inst.io.icu_bypass_q := io.icu_bypass_q
  
  io.ibuf_enable := ibuf_ctl_inst.io.ibuf_enable
  io.ic_dout_sel := ibuf_ctl_inst.io.ic_dout_sel
  io.icu_vld_d := ibuf_ctl_inst.io.icu_vld_d
  io.icu_drty_d := ibuf_ctl_inst.io.icu_drty_d
  ibuf_full := ibuf_ctl_inst.io.ibuf_full
  io.encod_shift_e := ibuf_ctl_inst.io.encod_shift_e
  io.ibuf_pc_sel := ibuf_ctl_inst.io.ibuf_pc_sel
  io.valid := ibuf_ctl_inst.io.valid
}

object VerilogGenerator extends App {
  emitVerilog(new icctl(), args)
}