package llmverify

import chisel3._
import chisel3.util._

// Helper flip-flop modules
class ff_sre extends Module {
  val io = IO(new Bundle {
    val out = Output(Bool())
    val din = Input(Bool())
    val enable = Input(Bool())
    val reset_l = Input(Bool())
  })
  
  val reg = RegInit(false.B)
  when(!io.reset_l) {
    reg := false.B
  }.elsewhen(io.enable) {
    reg := io.din
  }
  io.out := reg
}

class mj_s_ff_snre_d extends Module {
  val io = IO(new Bundle {
    val out = Output(Bool())
    val in = Input(Bool())
    val lenable = Input(Bool())
    val reset_l = Input(Bool())
  })
  
  val reg = RegInit(false.B)
  when(!io.reset_l) {
    reg := false.B
  }.elsewhen(io.lenable) {
    reg := io.in
  }
  io.out := reg
}

class mj_s_ff_snr_d extends Module {
  val io = IO(new Bundle {
    val out = Output(Bool())
    val in = Input(Bool())
    val reset_l = Input(Bool())
  })
  
  val reg = RegInit(false.B)
  when(!io.reset_l) {
    reg := false.B
  }.otherwise {
    reg := io.in
  }
  io.out := reg
}

class mj_s_ff_s_d extends Module {
  val io = IO(new Bundle {
    val out = Output(Bool())
    val in = Input(Bool())
  })
  
  val reg = RegInit(false.B)
  reg := io.in
  io.out := reg
}

// Main instruction cache control module
class ic_cntl extends Module {
  val io = IO(new Bundle {
    // Outputs
    val icu_req = Output(Bool())
    val icu_type = Output(UInt(4.W))
    val icu_size = Output(UInt(2.W))
    val icu_addr_sel = Output(UInt(2.W))
    val next_addr_sel = Output(UInt(4.W))
    val addr_reg_sel = Output(UInt(2.W))
    val addr_reg_enable = Output(Bool())
    val biu_addr_sel = Output(UInt(2.W))
    val ic_data_sel = Output(Bool())
    val icu_tag_sel = Output(Bool())
    val ic_drty = Output(Bool())
    val icu_stall = Output(Bool())
    val icu_tag_vld = Output(Bool())
    val icu_itag_we = Output(Bool())
    val icu_ram_we = Output(UInt(2.W))
    val icu_bypass_q = Output(Bool())
    val latch_biu_addr = Output(Bool())
    val diag_ld_cache_c = Output(Bool())
    val fill_word_addr = Output(UInt(2.W))
    val icu_in_powerdown = Output(Bool())
    val icram_powerdown = Output(Bool())
    val next_fetch_inc = Output(UInt(4.W))
    val icu_hold = Output(Bool())
    val bypass_ack = Output(Bool())
    
    // Inputs
    val iu_psr_bm8 = Input(Bool())
    val iu_ic_diag_e = Input(UInt(4.W))
    val biu_icu_ack = Input(UInt(2.W))
    val iu_psr_ice = Input(Bool())
    val iu_brtaken_e = Input(Bool())
    val iu_flush_e = Input(Bool())
    val ibuf_full = Input(Bool())
    val icu_hit = Input(Bool())
    val iu_data_e_0 = Input(Bool())
    val pcsu_powerdown = Input(Bool())
    val misc_wrd_sel = Input(Bool())
    val ice_line_align = Input(Bool())
    val reset_l = Input(Bool())
  })
  
  // State machine states - using 7-bit one-hot encoding as in original
  val IDLE = "b0000001".U(7.W)
  val NC_REQ_STATE = "b0000010".U(7.W)
  val REQ_STATE = "b0000100".U(7.W)
  val FILL_2ND_WD = "b0001000".U(7.W)
  val REQ_STATE2 = "b0010000".U(7.W)
  val FILL_4TH_WD = "b0100000".U(7.W)
  val STANDBY_PWR_DN = "b1000000".U(7.W)
  
  // State registers and wires
  val ic_miss_state = RegInit(IDLE)
  
  // Helper modules
  val iu_psr_ice_reg = Module(new ff_sre())
  iu_psr_ice_reg.io.din := io.iu_psr_ice
  iu_psr_ice_reg.io.enable := ic_miss_state === IDLE
  iu_psr_ice_reg.io.reset_l := io.reset_l
  val new_psr_ice = iu_psr_ice_reg.io.out
  
  val qual_iu_psr_ice_reg = Module(new mj_s_ff_snr_d())
  qual_iu_psr_ice_reg.io.reset_l := io.reset_l
  val qual_iu_psr_ice_q = Wire(Bool())
  qual_iu_psr_ice_q := qual_iu_psr_ice_reg.io.out
  
  val diag_ld_c_reg = Module(new mj_s_ff_snr_d())
  diag_ld_c_reg.io.reset_l := io.reset_l
  val diag_ld_c = diag_ld_c_reg.io.out
  
  val ic_drty_reg = Module(new mj_s_ff_s_d())
  ic_drty_reg.io.in := io.biu_icu_ack(1)
  
  val diag_ld_cache_c_reg = Module(new mj_s_ff_snr_d())
  diag_ld_cache_c_reg.io.reset_l := io.reset_l
  
  val valid_diag_c_reg = Module(new mj_s_ff_snr_d())
  valid_diag_c_reg.io.reset_l := io.reset_l
  
  val reset_reg = Module(new mj_s_ff_s_d())
  reset_reg.io.in := io.reset_l
  val reset_d1_l = reset_reg.io.out
  
  val standby_d1_reg = Module(new mj_s_ff_s_d())
  standby_d1_reg.io.in := io.icu_in_powerdown
  val standby_d1 = standby_d1_reg.io.out
  
  val set_stall_reg = Module(new mj_s_ff_snr_d())
  set_stall_reg.io.reset_l := io.reset_l
  
  val icu_bypass_reg = Module(new mj_s_ff_s_d())
  
  val valid_diag_window_flop = Module(new mj_s_ff_s_d())
  val valid_diag_window_d1 = valid_diag_window_flop.io.out
  
  val nc_fill_cyc_flop = Module(new mj_s_ff_snr_d())
  nc_fill_cyc_flop.io.reset_l := io.reset_l
  val nc_fill_cyc_d1 = nc_fill_cyc_flop.io.out
  
  val fourth_fill_cyc_flop = Module(new mj_s_ff_snr_d())
  fourth_fill_cyc_flop.io.reset_l := io.reset_l
  val fourth_fill_cyc_d1 = fourth_fill_cyc_flop.io.out
  
  val ic_idle_d1_reg = Module(new mj_s_ff_s_d())
  val ic_idle_d1 = ic_idle_d1_reg.io.out
  
  // Combinational signals
  val ic_idle = ic_miss_state === IDLE
  val qual_iu_psr_ice_sel = ic_idle && io.ice_line_align && !valid_diag_c_reg.io.out && !nc_fill_cyc_d1 && !fourth_fill_cyc_d1
  val qual_iu_psr_ice = Mux(qual_iu_psr_ice_sel, new_psr_ice, qual_iu_psr_ice_q)
  
  qual_iu_psr_ice_reg.io.in := qual_iu_psr_ice
  
  val qual_iu_flush_e = io.iu_flush_e && io.iu_psr_ice && ic_idle
  
  val valid_diag_e = qual_iu_flush_e || 
                     io.iu_ic_diag_e(3) || io.iu_ic_diag_e(2) || 
                     io.iu_ic_diag_e(1) || io.iu_ic_diag_e(0)
  
  val valid_diag_window = valid_diag_e || valid_diag_c_reg.io.out
  
  val diag_st_cache_e = io.iu_ic_diag_e(3) && ic_idle
  val diag_ld_cache_e = io.iu_ic_diag_e(2) && ic_idle
  val diag_st_tag_e = io.iu_ic_diag_e(1) && ic_idle
  val diag_ld_tag_e = io.iu_ic_diag_e(0) && ic_idle
  
  diag_ld_c_reg.io.in := diag_ld_cache_e || diag_ld_tag_e
  
  val icu_miss = (!qual_iu_psr_ice || io.iu_psr_bm8 || !io.icu_hit) &&
                  ic_idle && !valid_diag_c_reg.io.out && !fourth_fill_cyc_d1 && !nc_fill_cyc_d1 && !ic_drty_reg.io.out
  
  val normal_ack = io.biu_icu_ack(0) && !io.biu_icu_ack(1)
  val error_ack = io.biu_icu_ack(1)
  val bypass_ack = normal_ack || error_ack
  
  diag_ld_cache_c_reg.io.in := diag_ld_cache_e
  valid_diag_c_reg.io.in := valid_diag_e
  
  val jmp_e = io.iu_brtaken_e
  
  val cacheable = qual_iu_psr_ice && !io.iu_psr_bm8
  
  val set_stall = io.iu_brtaken_e && !ic_idle || set_stall_reg.io.out && !ic_idle
  set_stall_reg.io.in := set_stall
  
  // Address selection logic
  io.icu_addr_sel := Cat(jmp_e && (ic_idle && reset_d1_l), !(jmp_e && (ic_idle && reset_d1_l)))
  io.addr_reg_sel := Cat(jmp_e, !jmp_e)
  io.addr_reg_enable := jmp_e || !(standby_d1 || io.ibuf_full || valid_diag_window || !ic_idle || icu_miss ||
                                   set_stall_reg.io.out || fourth_fill_cyc_d1)
  
  io.next_addr_sel := Cat(
    !reset_d1_l || !ic_idle,
    valid_diag_e && ic_idle && reset_d1_l,
    (set_stall_reg.io.out || io.ibuf_full || fourth_fill_cyc_d1 || valid_diag_c_reg.io.out) && reset_d1_l && ic_idle && !valid_diag_e,
    !set_stall_reg.io.out && !io.ibuf_full && reset_d1_l && ic_idle && !valid_diag_e && !fourth_fill_cyc_d1
  )
  
  io.biu_addr_sel := Cat(ic_miss_state === REQ_STATE || (ic_idle && cacheable),
                         !(ic_miss_state === REQ_STATE || (ic_idle && cacheable)))
  
  // Cache control signals
  io.icu_tag_sel := normal_ack || (ic_idle && !valid_diag_e)
  io.ic_data_sel := normal_ack || error_ack
  
  val nc_req = ic_miss_state === NC_REQ_STATE
  val ic_req = ic_miss_state === REQ_STATE ||
               ic_miss_state === FILL_2ND_WD ||
               ic_miss_state === REQ_STATE2 ||
               ic_miss_state === FILL_4TH_WD
  
  val second_fill_cyc = ic_miss_state === FILL_2ND_WD && (normal_ack || error_ack)
  val third_fill_cyc = ic_miss_state === REQ_STATE2 && (normal_ack || error_ack)
  val fourth_fill_cyc = ic_miss_state === FILL_4TH_WD && (normal_ack || error_ack)
  val nc_fill_cyc = ic_miss_state === NC_REQ_STATE && (normal_ack || error_ack)
  val cache_fill_cyc = (ic_miss_state === REQ_STATE ||
                        ic_miss_state === FILL_2ND_WD ||
                        ic_miss_state === REQ_STATE2 ||
                        ic_miss_state === FILL_4TH_WD) && (normal_ack || error_ack)
  
  io.icu_type := Cat(0.U(1.W), nc_req, 0.U(2.W))
  io.icu_size := Cat(!io.iu_psr_bm8, 0.U(1.W))
  
  io.icu_itag_we := cache_fill_cyc || diag_st_tag_e || qual_iu_flush_e
  io.icu_ram_we := Cat(
    ((ic_miss_state === REQ_STATE || ic_miss_state === FILL_2ND_WD) && normal_ack) ||
    (diag_st_cache_e && !io.misc_wrd_sel),
    ((ic_miss_state === FILL_2ND_WD || ic_miss_state === FILL_4TH_WD) && normal_ack) ||
    (diag_st_cache_e && io.misc_wrd_sel)
  )
  
  val icu_bypass = ic_miss_state === NC_REQ_STATE || error_ack
  icu_bypass_reg.io.in := icu_bypass
  io.icu_bypass_q := icu_bypass_reg.io.out
  
  io.icu_tag_vld := ((ic_miss_state === FILL_4TH_WD && normal_ack) || diag_st_tag_e && io.iu_data_e_0) && !qual_iu_flush_e
  
  io.fill_word_addr := Cat(
    ic_miss_state === REQ_STATE2 || ic_miss_state === FILL_4TH_WD,
    ic_miss_state === FILL_2ND_WD || ic_miss_state === FILL_4TH_WD
  )
  
  io.latch_biu_addr := valid_diag_c_reg.io.out || !ic_idle
  
  valid_diag_window_flop.io.in := valid_diag_window
  
  io.icu_stall := icu_miss || (!ic_idle || valid_diag_window || set_stall_reg.io.out || io.ibuf_full || standby_d1 ||
                               !reset_d1_l || fourth_fill_cyc_d1)
  
  nc_fill_cyc_flop.io.in := nc_fill_cyc && !set_stall_reg.io.out
  fourth_fill_cyc_flop.io.in := fourth_fill_cyc && !set_stall_reg.io.out
  ic_idle_d1_reg.io.in := ic_idle
  
  io.icu_req := nc_req || ic_miss_state === REQ_STATE
  io.ic_drty := ic_drty_reg.io.out
  io.diag_ld_cache_c := diag_ld_cache_c_reg.io.out
  io.icram_powerdown := !ic_idle && !normal_ack && !valid_diag_window
  io.icu_in_powerdown := ic_miss_state === STANDBY_PWR_DN && !jmp_e
  io.icu_hold := (io.iu_ic_diag_e.orR || (io.iu_flush_e && io.iu_psr_ice)) && !ic_idle
  io.bypass_ack := bypass_ack
  
  io.next_fetch_inc := Mux(io.iu_psr_bm8, 1.U(4.W),
                          Mux(!qual_iu_psr_ice, 4.U(4.W), 8.U(4.W)))
  
  // State machine logic - initialize with default value
  val next_miss_state = WireDefault(0.U(7.W))
  
  switch(ic_miss_state) {
    is(IDLE) {
      when(io.pcsu_powerdown && !jmp_e && !valid_diag_window) {
        next_miss_state := STANDBY_PWR_DN
      }.elsewhen(valid_diag_window || io.ibuf_full || jmp_e) {
        next_miss_state := ic_miss_state
      }.elsewhen(icu_miss && !cacheable) {
        next_miss_state := NC_REQ_STATE
      }.elsewhen(icu_miss && cacheable) {
        next_miss_state := REQ_STATE
      }.otherwise {
        next_miss_state := ic_miss_state
      }
    }
    is(NC_REQ_STATE) {
      when(normal_ack || error_ack) {
        next_miss_state := IDLE
      }.otherwise {
        next_miss_state := ic_miss_state
      }
    }
    is(REQ_STATE) {
      when(normal_ack) {
        next_miss_state := FILL_2ND_WD
      }.elsewhen(error_ack) {
        next_miss_state := IDLE
      }.otherwise {
        next_miss_state := ic_miss_state
      }
    }
    is(FILL_2ND_WD) {
      when(normal_ack) {
        next_miss_state := REQ_STATE2
      }.elsewhen(error_ack) {
        next_miss_state := IDLE
      }.otherwise {
        next_miss_state := ic_miss_state
      }
    }
    is(REQ_STATE2) {
      when(normal_ack) {
        next_miss_state := FILL_4TH_WD
      }.elsewhen(error_ack) {
        next_miss_state := IDLE
      }.otherwise {
        next_miss_state := ic_miss_state
      }
    }
    is(FILL_4TH_WD) {
      when(normal_ack || error_ack) {
        next_miss_state := IDLE
      }.otherwise {
        next_miss_state := ic_miss_state
      }
    }
    is(STANDBY_PWR_DN) {
      when(!io.pcsu_powerdown || jmp_e) {
        next_miss_state := IDLE
      }.otherwise {
        next_miss_state := ic_miss_state
      }
    }
  }
  
  // State update
  when(!io.reset_l) {
    ic_miss_state := IDLE
  }.otherwise {
    ic_miss_state := next_miss_state
  }
}

object VerilogGenerator extends App {
  emitVerilog(new ic_cntl(), args)
}