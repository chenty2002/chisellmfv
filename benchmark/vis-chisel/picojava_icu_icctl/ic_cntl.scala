package llmverify

import chisel3._
import chisel3.util._

/**
 * Instruction cache control module
 * Handles cache miss state machine and control signals
 */
class ic_cntl extends Module {
  val io = IO(new Bundle {
    // Inputs
    val iu_ic_diag_e = Input(UInt(4.W))
    val biu_icu_ack = Input(UInt(2.W))
    val iu_psr_ice = Input(Bool())
    val iu_brtaken_e = Input(Bool())
    val iu_flush_e = Input(Bool())
    val ibuf_full = Input(Bool())
    val icu_hit = Input(Bool())
    val iu_data_e_0 = Input(Bool())
    val pcsu_powerdown = Input(Bool())
    val iu_psr_bm8 = Input(Bool())
    val misc_wrd_sel = Input(Bool())
    val ice_line_align = Input(Bool())
    val reset_l = Input(Bool())
    
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
  })
  
  // State machine states
  val sIdle :: sNcReqState :: sReqState :: sFill2ndWd :: sReqState2 :: sFill4thWd :: sStandbyPwrDn :: Nil = Enum(7)
  val ic_miss_state = RegInit(sIdle)
  
  // Internal registers and wires
  val new_psr_ice = RegInit(false.B)
  val qual_iu_psr_ice_q = RegInit(false.B)
  val diag_ld_c = RegInit(false.B)
  val valid_diag_c = RegInit(false.B)
  val reset_d1_l = RegInit(false.B)
  val standby_d1 = RegInit(false.B)
  val stall_valid = RegInit(false.B)
  val ic_drty_reg = RegInit(false.B)
  val icu_bypass_q_reg = RegInit(false.B)
  val fourth_fill_cyc_d1 = RegInit(false.B)
  val nc_fill_cyc_d1 = RegInit(false.B)
  val ic_idle_d1 = RegInit(false.B)
  
  // Combinational signals
  val normal_ack = io.biu_icu_ack(0) & !io.biu_icu_ack(1)
  val error_ack = io.biu_icu_ack(1)
  val bypass_ack = normal_ack | error_ack
  
  val valid_diag_e = (io.iu_flush_e & io.iu_psr_ice & (ic_miss_state === sIdle)) | 
                     io.iu_ic_diag_e.orR
  
  val valid_diag_window = valid_diag_e | valid_diag_c
  
  val qual_iu_flush_e = io.iu_flush_e & io.iu_psr_ice & (ic_miss_state === sIdle)
  
  val qual_iu_psr_ice_sel = (ic_miss_state === sIdle) & io.ice_line_align & 
                            !valid_diag_c & !nc_fill_cyc_d1 & !fourth_fill_cyc_d1
  val qual_iu_psr_ice = Mux(qual_iu_psr_ice_sel, new_psr_ice, qual_iu_psr_ice_q)
  
  val cacheable = qual_iu_psr_ice & !io.iu_psr_bm8
  
  val icu_miss = (!qual_iu_psr_ice | io.iu_psr_bm8 | !io.icu_hit) & 
                 (ic_miss_state === sIdle) & !valid_diag_c & 
                 !fourth_fill_cyc_d1 & !nc_fill_cyc_d1 & !ic_drty_reg
  
  val jmp_e = io.iu_brtaken_e
  
  // Register updates
  when(io.reset_l) {
    when((ic_miss_state === sIdle)) {
      new_psr_ice := io.iu_psr_ice
    }
    qual_iu_psr_ice_q := qual_iu_psr_ice
    diag_ld_c := (io.iu_ic_diag_e(2) | io.iu_ic_diag_e(0)) & (ic_miss_state === sIdle)
    valid_diag_c := valid_diag_e
    reset_d1_l := io.reset_l
    standby_d1 := io.icu_in_powerdown
    ic_drty_reg := io.biu_icu_ack(1)
    
    val set_stall = jmp_e & (ic_miss_state =/= sIdle) | (stall_valid & (ic_miss_state =/= sIdle))
    stall_valid := set_stall
    
    val icu_bypass = (ic_miss_state === sNcReqState) | error_ack
    icu_bypass_q_reg := icu_bypass
    
    fourth_fill_cyc_d1 := ((ic_miss_state === sFill4thWd) & (normal_ack | error_ack)) & !stall_valid
    nc_fill_cyc_d1 := ((ic_miss_state === sNcReqState) & (normal_ack | error_ack)) & !stall_valid
    ic_idle_d1 := (ic_miss_state === sIdle)
  }.otherwise {
    new_psr_ice := false.B
    qual_iu_psr_ice_q := false.B
    diag_ld_c := false.B
    valid_diag_c := false.B
    reset_d1_l := false.B
    standby_d1 := false.B
    ic_drty_reg := false.B
    stall_valid := false.B
    icu_bypass_q_reg := false.B
    fourth_fill_cyc_d1 := false.B
    nc_fill_cyc_d1 := false.B
    ic_idle_d1 := false.B
  }
  
  // State machine next state logic
  val next_miss_state = Wire(UInt())
  switch(ic_miss_state) {
    is(sIdle) {
      when(io.pcsu_powerdown & !jmp_e & !valid_diag_window) {
        next_miss_state := sStandbyPwrDn
      }.elsewhen(valid_diag_window | io.ibuf_full | jmp_e) {
        next_miss_state := ic_miss_state
      }.elsewhen(icu_miss & !cacheable) {
        next_miss_state := sNcReqState
      }.elsewhen(icu_miss & cacheable) {
        next_miss_state := sReqState
      }.otherwise {
        next_miss_state := ic_miss_state
      }
    }
    is(sNcReqState) {
      when(normal_ack | error_ack) {
        next_miss_state := sIdle
      }.otherwise {
        next_miss_state := ic_miss_state
      }
    }
    is(sReqState) {
      when(normal_ack) {
        next_miss_state := sFill2ndWd
      }.elsewhen(error_ack) {
        next_miss_state := sIdle
      }.otherwise {
        next_miss_state := ic_miss_state
      }
    }
    is(sFill2ndWd) {
      when(normal_ack) {
        next_miss_state := sReqState2
      }.elsewhen(error_ack) {
        next_miss_state := sIdle
      }.otherwise {
        next_miss_state := ic_miss_state
      }
    }
    is(sReqState2) {
      when(normal_ack) {
        next_miss_state := sFill4thWd
      }.elsewhen(error_ack) {
        next_miss_state := sIdle
      }.otherwise {
        next_miss_state := ic_miss_state
      }
    }
    is(sFill4thWd) {
      when(normal_ack | error_ack) {
        next_miss_state := sIdle
      }.otherwise {
        next_miss_state := ic_miss_state
      }
    }
    is(sStandbyPwrDn) {
      when(!io.pcsu_powerdown | jmp_e) {
        next_miss_state := sIdle
      }.otherwise {
        next_miss_state := sStandbyPwrDn
      }
    }
  }
  
  // State register update
  when(io.reset_l) {
    ic_miss_state := next_miss_state.asTypeOf(ic_miss_state)
  }.otherwise {
    ic_miss_state := sIdle
  }
  
  // Output assignments
  io.icu_req := (ic_miss_state === sNcReqState) | (ic_miss_state === sReqState)
  io.icu_type := Cat("b00".U, (ic_miss_state === sNcReqState))
  io.icu_size := Cat(!io.iu_psr_bm8, "b0".U(1.W))
  io.icu_addr_sel := Cat(jmp_e & ((ic_miss_state === sIdle) & reset_d1_l), 
                         !(jmp_e & ((ic_miss_state === sIdle) & reset_d1_l)))
  io.next_addr_sel := Cat(
    !reset_d1_l | (ic_miss_state =/= sIdle),
    valid_diag_e & (ic_miss_state === sIdle) & reset_d1_l,
    (stall_valid | io.ibuf_full | fourth_fill_cyc_d1 | valid_diag_c) & reset_d1_l & (ic_miss_state === sIdle) & !valid_diag_e,
    !stall_valid & !io.ibuf_full & reset_d1_l & (ic_miss_state === sIdle) & !valid_diag_e & !fourth_fill_cyc_d1
  )
  io.addr_reg_sel := Cat(jmp_e, !jmp_e)
  io.addr_reg_enable := jmp_e | !(standby_d1 | io.ibuf_full | valid_diag_window | 
                                  (ic_miss_state =/= sIdle) | icu_miss | stall_valid | fourth_fill_cyc_d1)
  io.biu_addr_sel := Cat((ic_miss_state === sReqState) | ((ic_miss_state === sIdle) & cacheable),
                         !((ic_miss_state === sReqState) | ((ic_miss_state === sIdle) & cacheable)))
  io.ic_data_sel := normal_ack | error_ack
  io.icu_tag_sel := normal_ack | ((ic_miss_state === sIdle) & !valid_diag_e)
  io.ic_drty := ic_drty_reg
  io.icu_stall := icu_miss | ((ic_miss_state =/= sIdle) | valid_diag_window | stall_valid | 
                             io.ibuf_full | standby_d1 | !reset_d1_l | fourth_fill_cyc_d1)
  io.icu_tag_vld := (((ic_miss_state === sFill4thWd) & normal_ack) | 
                     (io.iu_ic_diag_e(1) & (ic_miss_state === sIdle) & io.iu_data_e_0)) & !qual_iu_flush_e
  io.icu_itag_we := ((ic_miss_state === sReqState) | (ic_miss_state === sFill2ndWd) | 
                    (ic_miss_state === sReqState2) | (ic_miss_state === sFill4thWd)) & 
                    (normal_ack | error_ack) | 
                    (io.iu_ic_diag_e(1) & (ic_miss_state === sIdle)) | qual_iu_flush_e
  io.icu_ram_we := Cat(
    ((ic_miss_state === sReqState) | (ic_miss_state === sFill4thWd)) & normal_ack |
    (io.iu_ic_diag_e(3) & (ic_miss_state === sIdle) & !io.misc_wrd_sel),
    ((ic_miss_state === sFill2ndWd) | (ic_miss_state === sReqState2)) & normal_ack |
    (io.iu_ic_diag_e(3) & (ic_miss_state === sIdle) & io.misc_wrd_sel)
  )
  io.icu_bypass_q := icu_bypass_q_reg
  io.latch_biu_addr := valid_diag_c | (ic_miss_state =/= sIdle)
  io.diag_ld_cache_c := diag_ld_c
  io.fill_word_addr := Cat(ic_miss_state === sFill4thWd | ic_miss_state === sReqState2,
                          ic_miss_state === sFill2ndWd | ic_miss_state === sReqState2)
  io.icu_in_powerdown := (ic_miss_state === sStandbyPwrDn) & !jmp_e
  io.icram_powerdown := (ic_miss_state =/= sIdle) & !normal_ack & !valid_diag_window
  io.next_fetch_inc := Mux(io.iu_psr_bm8, "b0001".U(4.W), 
                          Mux(!qual_iu_psr_ice, "b0100".U(4.W), "b1000".U(4.W)))
  io.icu_hold := (io.iu_ic_diag_e.orR | (io.iu_flush_e & io.iu_psr_ice)) & (ic_miss_state =/= sIdle)
  io.bypass_ack := bypass_ack
}