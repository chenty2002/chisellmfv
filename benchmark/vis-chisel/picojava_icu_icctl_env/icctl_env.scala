package llmverify

import chisel3._
import chisel3.util._

// Utility modules
class Mux8 extends Module {
  val io = IO(new Bundle {
    val out = Output(Bool())
    val in7 = Input(Bool())
    val in6 = Input(Bool())
    val in5 = Input(Bool())
    val in4 = Input(Bool())
    val in3 = Input(Bool())
    val in2 = Input(Bool())
    val in1 = Input(Bool())
    val in0 = Input(Bool())
    val sel = Input(UInt(8.W))
  })
  
  io.out := Mux1H(Seq(
    io.sel(7) -> io.in7,
    io.sel(6) -> io.in6,
    io.sel(5) -> io.in5,
    io.sel(4) -> io.in4,
    io.sel(3) -> io.in3,
    io.sel(2) -> io.in2,
    io.sel(1) -> io.in1,
    io.sel(0) -> io.in0
  ))
}

class MJ_S_FF_SNR_D extends Module {
  val io = IO(new Bundle {
    val out = Output(Bool())
    val in = Input(Bool())
    val reset_l = Input(Bool())
    val clk = Input(Clock())
  })
  
  val reg = RegInit(0.U(1.W))
  when(!io.reset_l) {
    reg := 0.U
  }.otherwise {
    reg := io.in
  }
  io.out := reg
}

class MJ_S_FF_S_D extends Module {
  val io = IO(new Bundle {
    val out = Output(Bool())
    val in = Input(Bool())
    val clk = Input(Clock())
  })
  
  val reg = RegInit(0.U(1.W))
  reg := io.in
  io.out := reg
}

class MJ_S_FF_SNR_D_8 extends Module {
  val io = IO(new Bundle {
    val out = Output(UInt(8.W))
    val din = Input(UInt(8.W))
    val reset_l = Input(Bool())
    val clk = Input(Clock())
  })
  
  val reg = RegInit(0.U(8.W))
  when(!io.reset_l) {
    reg := 0.U
  }.otherwise {
    reg := io.din
  }
  io.out := reg
}

class MJ_S_FF_SNRE_D extends Module {
  val io = IO(new Bundle {
    val out = Output(Bool())
    val in = Input(Bool())
    val lenable = Input(Bool())
    val reset_l = Input(Bool())
    val clk = Input(Clock())
  })
  
  val reg = RegInit(0.U(1.W))
  when(!io.reset_l) {
    reg := 0.U
  }.elsewhen(io.lenable) {
    reg := io.in
  }
  io.out := reg
}

class FF_SR extends Module {
  val io = IO(new Bundle {
    val out = Output(Bool())
    val din = Input(Bool())
    val reset_l = Input(Bool())
    val clk = Input(Clock())
  })
  
  val reg = RegInit(0.U(1.W))
  when(!io.reset_l) {
    reg := 0.U
  }.otherwise {
    reg := io.din
  }
  io.out := reg
}

class FF_SRE extends Module {
  val io = IO(new Bundle {
    val out = Output(Bool())
    val din = Input(Bool())
    val enable = Input(Bool())
    val reset_l = Input(Bool())
    val clk = Input(Clock())
  })
  
  val reg = RegInit(0.U(1.W))
  when(!io.reset_l) {
    reg := 0.U
  }.elsewhen(io.enable) {
    reg := io.din
  }
  io.out := reg
}

class EncodeShiftModule extends Module {
  val io = IO(new Bundle {
    val iu_shift_e = Input(UInt(8.W))
    val encod_shift_e = Output(UInt(3.W))
  })
  
  io.encod_shift_e := MuxCase(0.U(3.W), Seq(
    (io.iu_shift_e === "b00000001".U) -> 0.U,
    (io.iu_shift_e === "b00000010".U) -> 1.U,
    (io.iu_shift_e === "b00000100".U) -> 2.U,
    (io.iu_shift_e === "b00001000".U) -> 3.U,
    (io.iu_shift_e === "b00010000".U) -> 4.U,
    (io.iu_shift_e === "b00100000".U) -> 5.U,
    (io.iu_shift_e === "b01000000".U) -> 6.U,
    (io.iu_shift_e === "b10000000".U) -> 7.U
  ))
}

class IBufCtlSlice extends Module {
  val io = IO(new Bundle {
    val valid_bits = Input(UInt(7.W))
    val dirty_bits = Input(UInt(7.W))
    val shft_dsel = Input(UInt(8.W))
    val valid_out = Output(Bool())
    val dirty_out = Output(Bool())
    val new_valid = Input(Bool())
    val new_dirty = Input(Bool())
    val icu_stall = Input(Bool())
    val fill_sel = Output(Bool())
    val ibuf_en = Input(Bool())
    val buf_ic_drty = Output(Bool())
    val buf_ic_valid = Output(Bool())
    val jmp_e = Input(Bool())
    val reset_l = Input(Bool())
    val clk = Input(Clock())
  })
  
  val validMux = Module(new Mux8())
  val dirtyMux = Module(new Mux8())
  val validFlop = Module(new MJ_S_FF_SNRE_D())
  val dirtyFlop = Module(new MJ_S_FF_SNRE_D())
  
  val buf_ic_valid_wire = Wire(Bool())
  val buf_ic_drty_wire = Wire(Bool())
  
  buf_ic_valid_wire := io.valid_out | (io.new_valid & !io.icu_stall)
  buf_ic_drty_wire := io.dirty_out | (io.new_dirty & !io.icu_stall)
  
  validMux.io.sel := io.shft_dsel
  validMux.io.in0 := buf_ic_valid_wire
  validMux.io.in1 := io.valid_bits(0)
  validMux.io.in2 := io.valid_bits(1)
  validMux.io.in3 := io.valid_bits(2)
  validMux.io.in4 := io.valid_bits(3)
  validMux.io.in5 := io.valid_bits(4)
  validMux.io.in6 := io.valid_bits(5)
  validMux.io.in7 := io.valid_bits(6)
  
  dirtyMux.io.sel := io.shft_dsel
  dirtyMux.io.in0 := buf_ic_drty_wire
  dirtyMux.io.in1 := io.dirty_bits(0)
  dirtyMux.io.in2 := io.dirty_bits(1)
  dirtyMux.io.in3 := io.dirty_bits(2)
  dirtyMux.io.in4 := io.dirty_bits(3)
  dirtyMux.io.in5 := io.dirty_bits(4)
  dirtyMux.io.in6 := io.dirty_bits(5)
  dirtyMux.io.in7 := io.dirty_bits(6)
  
  io.fill_sel := io.valid_out
  
  validFlop.io.in := validMux.io.out & !io.jmp_e
  validFlop.io.lenable := io.ibuf_en
  validFlop.io.reset_l := io.reset_l
  validFlop.io.clk := io.clk
  
  dirtyFlop.io.in := dirtyMux.io.out & !io.jmp_e
  dirtyFlop.io.lenable := io.ibuf_en
  dirtyFlop.io.reset_l := io.reset_l
  dirtyFlop.io.clk := io.clk
  
  io.valid_out := validFlop.io.out
  io.dirty_out := dirtyFlop.io.out
  io.buf_ic_valid := buf_ic_valid_wire
  io.buf_ic_drty := buf_ic_drty_wire
}

class IBufCtl extends Module {
  val io = IO(new Bundle {
    val ic_drty = Input(Bool())
    val icu_stall = Input(Bool())
    val ibuf_enable = Output(Bool())
    val ic_dout_sel = Output(UInt(12.W))
    val icu_vld_d = Output(UInt(7.W))
    val icu_drty_d = Output(UInt(7.W))
    val ibuf_full = Output(Bool())
    val iu_shift_d = Input(UInt(8.W))
    val encod_shift_e = Output(UInt(3.W))
    val icu_addr_2_0 = Input(UInt(3.W))
    val ibuf_pc_sel = Output(UInt(2.W))
    val reset_l = Input(Bool())
    val jmp_e = Input(Bool())
    val valid = Output(UInt(16.W))
    val iu_psr_bm8 = Input(Bool())
    val icu_bypass_q = Input(Bool())
    val clk = Input(Clock())
  })
  
  val ibuf_slices = Array.fill(16)(Module(new IBufCtlSlice()))
  val encodeShift = Module(new EncodeShiftModule())
  val squashVldReg = Module(new FF_SR())
  val iuShiftEReg = Module(new MJ_S_FF_SNR_D_8())
  
  val valid = Wire(Vec(16, Bool()))
  val dirty = Wire(Vec(16, Bool()))
  val buf_ic_valid = Wire(Vec(16, Bool()))
  val buf_ic_drty = Wire(Vec(16, Bool()))
  val new_valid = Wire(Vec(16, Bool()))
  val new_dirty = Wire(Vec(16, Bool()))
  val ic_fill_sel = Wire(Vec(16, Bool()))
  
  val ibuf_en_wire = !io.icu_stall | !io.iu_shift_d(0) | io.jmp_e
  
  // Connect slices
  for (i <- 0 until 16) {
    ibuf_slices(i).io.shft_dsel := io.iu_shift_d
    ibuf_slices(i).io.icu_stall := io.icu_stall
    ibuf_slices(i).io.jmp_e := io.jmp_e
    ibuf_slices(i).io.ibuf_en := ibuf_en_wire
    ibuf_slices(i).io.reset_l := io.reset_l
    ibuf_slices(i).io.clk := io.clk
    
    valid(i) := ibuf_slices(i).io.valid_out
    dirty(i) := ibuf_slices(i).io.dirty_out
    buf_ic_valid(i) := ibuf_slices(i).io.buf_ic_valid
    buf_ic_drty(i) := ibuf_slices(i).io.buf_ic_drty
    ic_fill_sel(i) := ibuf_slices(i).io.fill_sel
    
    if (i < 15) {
      val validBitsSlice = buf_ic_valid.slice(i+1, math.min(i+8, 16))
      val dirtyBitsSlice = buf_ic_drty.slice(i+1, math.min(i+8, 16))
      ibuf_slices(i).io.valid_bits := Cat(validBitsSlice.reverse)
      ibuf_slices(i).io.dirty_bits := Cat(dirtyBitsSlice.reverse)
    } else {
      ibuf_slices(i).io.valid_bits := 0.U(7.W)
      ibuf_slices(i).io.dirty_bits := 0.U(7.W)
    }
  }
  
  // Generate new_valid signals
  val dword_align = (io.icu_addr_2_0 === 0.U)
  
  new_valid(0) := 1.U
  new_valid(1) := ((!io.icu_bypass_q & !io.icu_addr_2_0(2)) | !(io.icu_addr_2_0(1) & io.icu_addr_2_0(0))) & !io.iu_psr_bm8 | (ic_fill_sel(0) & io.iu_psr_bm8)
  new_valid(2) := (((!io.icu_bypass_q & !io.icu_addr_2_0(2)) | !io.icu_addr_2_0(1)) & !io.iu_psr_bm8) | (ic_fill_sel(1) & io.iu_psr_bm8)
  new_valid(3) := (((!io.icu_bypass_q & !io.icu_addr_2_0(2)) | (!io.icu_addr_2_0(1) & !io.icu_addr_2_0(0))) & !io.iu_psr_bm8) | (ic_fill_sel(2) & io.iu_psr_bm8)
  new_valid(4) := ((!io.icu_bypass_q & !io.icu_addr_2_0(2)) | (io.icu_bypass_q & ic_fill_sel(0)) & !io.iu_psr_bm8) | ic_fill_sel(3)
  new_valid(5) := ((!io.icu_bypass_q & (!io.icu_addr_2_0(2) & !(io.icu_addr_2_0(1) & io.icu_addr_2_0(0)))) | (io.icu_bypass_q & ic_fill_sel(1)) & !io.iu_psr_bm8) | ic_fill_sel(4)
  new_valid(6) := ((!io.icu_bypass_q & !io.icu_addr_2_0(2) & !io.icu_addr_2_0(1)) | (io.icu_bypass_q & ic_fill_sel(2)) & !io.iu_psr_bm8) | ic_fill_sel(5)
  new_valid(7) := (!io.icu_bypass_q & !io.icu_addr_2_0(2) & !io.icu_addr_2_0(1) & !io.icu_addr_2_0(0) | io.icu_bypass_q & ic_fill_sel(3)) & !io.iu_psr_bm8 | ic_fill_sel(6)
  new_valid(8) := !io.iu_psr_bm8 & (!io.icu_bypass_q & dword_align & ic_fill_sel(0) | io.icu_bypass_q & ic_fill_sel(4)) | ic_fill_sel(7)
  new_valid(9) := !io.iu_psr_bm8 & (!io.icu_bypass_q & dword_align & ic_fill_sel(1) | io.icu_bypass_q & ic_fill_sel(5))
  new_valid(10) := !io.iu_psr_bm8 & (!io.icu_bypass_q & dword_align & ic_fill_sel(2) | io.icu_bypass_q & ic_fill_sel(6))
  new_valid(11) := !io.iu_psr_bm8 & (!io.icu_bypass_q & dword_align & ic_fill_sel(3) | io.icu_bypass_q & ic_fill_sel(7))
  new_valid(12) := !io.iu_psr_bm8 & (!io.icu_bypass_q & dword_align & ic_fill_sel(4) | io.icu_bypass_q & ic_fill_sel(8))
  new_valid(13) := !io.iu_psr_bm8 & (!io.icu_bypass_q & dword_align & ic_fill_sel(5) | io.icu_bypass_q & ic_fill_sel(9))
  new_valid(14) := !io.iu_psr_bm8 & (!io.icu_bypass_q & dword_align & ic_fill_sel(6) | io.icu_bypass_q & ic_fill_sel(10))
  new_valid(15) := !io.iu_psr_bm8 & (!io.icu_bypass_q & dword_align & ic_fill_sel(7) | io.icu_bypass_q & ic_fill_sel(11))
  
  // Generate new_dirty signals - fix the bit range issues
  new_dirty(0) := io.icu_bypass_q & io.ic_drty & io.ic_dout_sel(0)
  new_dirty(1) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(0)) | (!io.iu_psr_bm8 & io.ic_dout_sel(1,0).orR))
  new_dirty(2) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(1)) | (!io.iu_psr_bm8 & io.ic_dout_sel(2,0).orR))
  new_dirty(3) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(2)) | (!io.iu_psr_bm8 & io.ic_dout_sel(3,0).orR))
  new_dirty(4) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(3)) | (!io.iu_psr_bm8 & io.ic_dout_sel(4,1).orR))
  new_dirty(5) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(4)) | (!io.iu_psr_bm8 & io.ic_dout_sel(5,2).orR))
  new_dirty(6) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(5)) | (!io.iu_psr_bm8 & io.ic_dout_sel(6,3).orR))
  new_dirty(7) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(6)) | (!io.iu_psr_bm8 & io.ic_dout_sel(7,4).orR))
  new_dirty(8) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(7)) | (!io.iu_psr_bm8 & io.ic_dout_sel(8,5).orR))
  new_dirty(9) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(8)) | (!io.iu_psr_bm8 & io.ic_dout_sel(9,6).orR))
  new_dirty(10) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(9)) | (!io.iu_psr_bm8 & io.ic_dout_sel(10,7).orR))
  new_dirty(11) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(10)) | (!io.iu_psr_bm8 & io.ic_dout_sel(11,8).orR))
  new_dirty(12) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(11)) | (!io.iu_psr_bm8 & (io.ic_dout_sel(11,9).orR | io.ic_dout_sel(0))))
  new_dirty(13) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(0)) | (!io.iu_psr_bm8 & (io.ic_dout_sel(11,10).orR | io.ic_dout_sel(1,0).orR)))
  new_dirty(14) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(1)) | (!io.iu_psr_bm8 & (io.ic_dout_sel(11) | io.ic_dout_sel(2,0).orR)))
  new_dirty(15) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(2)) | (!io.iu_psr_bm8 & io.ic_dout_sel(3,0).orR))
  
  // Generate ic_dout_sel
  io.ic_dout_sel(0) := !valid(0)
  for (i <- 1 until 12) {
    io.ic_dout_sel(i) := valid(i-1) & !valid(i)
  }
  
  // Connect new_valid and new_dirty to slices
  for (i <- 0 until 16) {
    ibuf_slices(i).io.new_valid := new_valid(i)
    ibuf_slices(i).io.new_dirty := new_dirty(i)
  }
  
  // Generate ibuf_full
  squashVldReg.io.din := io.jmp_e
  squashVldReg.io.reset_l := io.reset_l
  squashVldReg.io.clk := io.clk
  
  io.ibuf_full := (valid(12) | (!io.icu_bypass_q | io.iu_psr_bm8) & valid(8)) & !squashVldReg.io.out
  
  // Generate outputs
  io.icu_vld_d := Cat(valid.slice(0, 7).reverse)
  io.icu_drty_d := Cat(dirty.slice(0, 7).reverse)
  io.valid := Cat(valid.reverse)
  
  // Generate encod_shift_e
  iuShiftEReg.io.din := io.iu_shift_d
  iuShiftEReg.io.reset_l := io.reset_l
  iuShiftEReg.io.clk := io.clk
  
  encodeShift.io.iu_shift_e := iuShiftEReg.io.out
  io.encod_shift_e := encodeShift.io.encod_shift_e
  
  // Generate ibuf_pc_sel
  io.ibuf_pc_sel := Cat(squashVldReg.io.out, !squashVldReg.io.out)
  
  io.ibuf_enable := ibuf_en_wire
}

class ICCntl extends Module {
  val io = IO(new Bundle {
    val biu_icu_ack = Input(UInt(2.W))
    val clk = Input(Clock())
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
    val misc_wrd_sel = Input(Bool())
    val fill_word_addr = Output(UInt(2.W))
    val ice_line_align = Input(Bool())
    val bypass_ack = Output(Bool())
  })
  
  val ic_cntl = Module(new ICCntlCore())
  val ibuf_ctl = Module(new IBufCtl())
  
  // Connect ic_cntl
  ic_cntl.io.biu_icu_ack := io.biu_icu_ack
  ic_cntl.io.clk := io.clk
  ic_cntl.io.icu_addr_2_0 := io.icu_addr_2_0
  ic_cntl.io.icu_hit := io.icu_hit
  ic_cntl.io.iu_brtaken_e := io.iu_brtaken_e
  ic_cntl.io.iu_data_e_0 := io.iu_data_e_0
  ic_cntl.io.iu_flush_e := io.iu_flush_e
  ic_cntl.io.iu_ic_diag_e := io.iu_ic_diag_e
  ic_cntl.io.iu_psr_ice := io.iu_psr_ice
  ic_cntl.io.reset_l := io.reset_l
  ic_cntl.io.pcsu_powerdown := io.pcsu_powerdown
  ic_cntl.io.iu_psr_bm8 := io.iu_psr_bm8
  ic_cntl.io.misc_wrd_sel := io.misc_wrd_sel
  ic_cntl.io.ice_line_align := io.ice_line_align
  ic_cntl.io.clk := io.clk
  ic_cntl.io.reset_l := io.reset_l
  
  // Connect ibuf_ctl
  ibuf_ctl.io.ic_drty := ic_cntl.io.ic_drty
  ibuf_ctl.io.icu_stall := ic_cntl.io.icu_stall
  ibuf_ctl.io.iu_shift_d := io.iu_shift_d
  ibuf_ctl.io.icu_addr_2_0 := io.icu_addr_2_0
  ibuf_ctl.io.reset_l := io.reset_l
  ibuf_ctl.io.jmp_e := io.iu_brtaken_e
  ibuf_ctl.io.iu_psr_bm8 := io.iu_psr_bm8
  ibuf_ctl.io.icu_bypass_q := ic_cntl.io.icu_bypass_q
  ibuf_ctl.io.clk := io.clk
  
  // Connect outputs
  io.next_fetch_inc := ic_cntl.io.next_fetch_inc
  io.encod_shift_e := ibuf_ctl.io.encod_shift_e
  io.ibuf_pc_sel := ibuf_ctl.io.ibuf_pc_sel
  io.icu_addr_sel := ic_cntl.io.icu_addr_sel
  io.ibuf_enable := ibuf_ctl.io.ibuf_enable
  io.ic_data_sel := ic_cntl.io.ic_data_sel
  io.ic_dout_sel := ibuf_ctl.io.ic_dout_sel
  io.icu_bypass_q := ic_cntl.io.icu_bypass_q
  io.icu_drty_d := ibuf_ctl.io.icu_drty_d
  io.icu_itag_we := ic_cntl.io.icu_itag_we
  io.latch_biu_addr := ic_cntl.io.latch_biu_addr
  io.icu_ram_we := ic_cntl.io.icu_ram_we
  io.icu_req := ic_cntl.io.icu_req
  io.icu_size := ic_cntl.io.icu_size
  io.icu_tag_sel := ic_cntl.io.icu_tag_sel
  io.icu_tag_vld := ic_cntl.io.icu_tag_vld
  io.icu_type := ic_cntl.io.icu_type
  io.icu_vld_d := ibuf_ctl.io.icu_vld_d
  io.next_addr_sel := ic_cntl.io.next_addr_sel
  io.addr_reg_sel := ic_cntl.io.addr_reg_sel
  io.addr_reg_enable := ic_cntl.io.addr_reg_enable
  io.biu_addr_sel := ic_cntl.io.biu_addr_sel
  io.diag_ld_cache_c := ic_cntl.io.diag_ld_cache_c
  io.icu_in_powerdown := ic_cntl.io.icu_in_powerdown
  io.icram_powerdown := ic_cntl.io.icram_powerdown
  io.icu_hold := ic_cntl.io.icu_hold
  io.valid := ibuf_ctl.io.valid
  io.fill_word_addr := ic_cntl.io.fill_word_addr
  io.bypass_ack := ic_cntl.io.bypass_ack
  
  // Connect ibuf_full back to ic_cntl
  ic_cntl.io.ibuf_full := ibuf_ctl.io.ibuf_full
}

class ICCntlCore extends Module {
  val io = IO(new Bundle {
    val biu_icu_ack = Input(UInt(2.W))
    val clk = Input(Clock())
    val icu_addr_2_0 = Input(UInt(3.W))
    val icu_hit = Input(Bool())
    val iu_brtaken_e = Input(Bool())
    val iu_data_e_0 = Input(Bool())
    val iu_flush_e = Input(Bool())
    val iu_ic_diag_e = Input(UInt(4.W))
    val iu_psr_ice = Input(Bool())
    val reset_l = Input(Bool())
    val pcsu_powerdown = Input(Bool())
    val iu_psr_bm8 = Input(Bool())
    val misc_wrd_sel = Input(Bool())
    val ice_line_align = Input(Bool())
    val ibuf_full = Input(Bool())
    
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
    val icu_in_powerdown = Output(Bool())
    val icram_powerdown = Output(Bool())
    val icu_hold = Output(Bool())
    val next_fetch_inc = Output(UInt(4.W))
    val fill_word_addr = Output(UInt(2.W))
    val bypass_ack = Output(Bool())
  })
  
  // State machine states
  val sIdle :: sNcReqState :: sReqState :: sFill2ndWd :: sReqState2 :: sFill4thWd :: sStandbyPwrDn :: Nil = Enum(7)
  
  val ic_miss_state = RegInit(sIdle)
  
  // Utility modules
  val iuPsrIceReg = Module(new FF_SRE())
  val qualIuPsrIceReg = Module(new MJ_S_FF_SNR_D())
  val icDrtyReg = Module(new MJ_S_FF_S_D())
  val diagLdCacheCReg = Module(new MJ_S_FF_SNR_D())
  val validDiagCReg = Module(new MJ_S_FF_SNR_D())
  val resetReg = Module(new MJ_S_FF_S_D())
  val standbyD1Reg = Module(new MJ_S_FF_S_D())
  val setStallReg = Module(new MJ_S_FF_SNR_D())
  val ncFillCycFlop = Module(new MJ_S_FF_SNR_D())
  val fourthFillCycFlop = Module(new MJ_S_FF_SNR_D())
  val icIdleD1Reg = Module(new MJ_S_FF_S_D())
  val icuBypassReg = Module(new MJ_S_FF_S_D())
  
  // Signals
  val normal_ack = io.biu_icu_ack(0) & !io.biu_icu_ack(1)
  val error_ack = io.biu_icu_ack(1)
  val bypass_ack = normal_ack | error_ack
  
  val valid_diag_e = io.iu_flush_e & io.iu_psr_ice | io.iu_ic_diag_e.orR
  val diag_st_cache_e = io.iu_ic_diag_e(3) & (ic_miss_state === sIdle)
  val diag_ld_cache_e = io.iu_ic_diag_e(2) & (ic_miss_state === sIdle)
  val diag_st_tag_e = io.iu_ic_diag_e(1) & (ic_miss_state === sIdle)
  val diag_ld_tag_e = io.iu_ic_diag_e(0) & (ic_miss_state === sIdle)
  
  val qual_iu_psr_ice_sel = (ic_miss_state === sIdle) & io.ice_line_align & !validDiagCReg.io.out & !ncFillCycFlop.io.out & !fourthFillCycFlop.io.out
  val new_psr_ice = Wire(Bool())
  val qual_iu_psr_ice_q = Wire(Bool())
  val qual_iu_psr_ice = Mux(qual_iu_psr_ice_sel, new_psr_ice, qual_iu_psr_ice_q)
  
  val valid_diag_window = valid_diag_e | validDiagCReg.io.out
  val qual_iu_flush_e = io.iu_flush_e & io.iu_psr_ice & (ic_miss_state === sIdle)
  
  val icu_miss = (!qual_iu_psr_ice | io.iu_psr_bm8 | !io.icu_hit) & (ic_miss_state === sIdle) & !validDiagCReg.io.out & !fourthFillCycFlop.io.out & !ncFillCycFlop.io.out & !icDrtyReg.io.out
  
  val cacheable = qual_iu_psr_ice & !io.iu_psr_bm8
  
  val second_fill_cyc = (ic_miss_state === sFill2ndWd) & (normal_ack | error_ack)
  val third_fill_cyc = (ic_miss_state === sReqState2) & (normal_ack | error_ack)
  val fourth_fill_cyc = (ic_miss_state === sFill4thWd) & (normal_ack | error_ack)
  val nc_fill_cyc = (ic_miss_state === sNcReqState) & (normal_ack | error_ack)
  val cache_fill_cyc = ((ic_miss_state === sReqState) | (ic_miss_state === sFill2ndWd) | (ic_miss_state === sReqState2) | (ic_miss_state === sFill4thWd)) & (normal_ack | error_ack)
  
  val set_stall = io.iu_brtaken_e & (ic_miss_state =/= sIdle) | (setStallReg.io.out & (ic_miss_state =/= sIdle))
  val jmp_e = io.iu_brtaken_e
  
  // Connect utility modules
  iuPsrIceReg.io.din := io.iu_psr_ice
  iuPsrIceReg.io.enable := (ic_miss_state === sIdle)
  iuPsrIceReg.io.reset_l := io.reset_l
  iuPsrIceReg.io.clk := io.clk
  new_psr_ice := iuPsrIceReg.io.out
  
  qualIuPsrIceReg.io.in := qual_iu_psr_ice
  qualIuPsrIceReg.io.reset_l := io.reset_l
  qualIuPsrIceReg.io.clk := io.clk
  qual_iu_psr_ice_q := qualIuPsrIceReg.io.out
  
  icDrtyReg.io.in := io.biu_icu_ack(1)
  icDrtyReg.io.clk := io.clk
  
  diagLdCacheCReg.io.in := diag_ld_cache_e
  diagLdCacheCReg.io.reset_l := io.reset_l
  diagLdCacheCReg.io.clk := io.clk
  
  validDiagCReg.io.in := valid_diag_e
  validDiagCReg.io.reset_l := io.reset_l
  validDiagCReg.io.clk := io.clk
  
  resetReg.io.in := io.reset_l
  resetReg.io.clk := io.clk
  
  standbyD1Reg.io.in := io.icu_in_powerdown
  standbyD1Reg.io.clk := io.clk
  
  setStallReg.io.in := set_stall
  setStallReg.io.reset_l := io.reset_l
  setStallReg.io.clk := io.clk
  
  ncFillCycFlop.io.in := nc_fill_cyc & !setStallReg.io.out
  ncFillCycFlop.io.reset_l := io.reset_l
  ncFillCycFlop.io.clk := io.clk
  
  fourthFillCycFlop.io.in := fourth_fill_cyc & !setStallReg.io.out
  fourthFillCycFlop.io.reset_l := io.reset_l
  fourthFillCycFlop.io.clk := io.clk
  
  icIdleD1Reg.io.in := (ic_miss_state === sIdle)
  icIdleD1Reg.io.clk := io.clk
  
  val icu_bypass = (ic_miss_state === sReqState) | error_ack
  icuBypassReg.io.in := icu_bypass
  icuBypassReg.io.clk := io.clk
  
  // Generate outputs
  io.next_fetch_inc := Mux(io.iu_psr_bm8, 1.U, Mux(!qual_iu_psr_ice, 4.U, 8.U))
  
  io.fill_word_addr := Cat(ic_miss_state === sFill2ndWd | ic_miss_state === sFill4thWd, ic_miss_state === sFill2ndWd | ic_miss_state === sReqState2)
  
  io.latch_biu_addr := validDiagCReg.io.out | (ic_miss_state =/= sIdle)
  
  io.icu_addr_sel := Cat(jmp_e & (ic_miss_state === sIdle) & resetReg.io.out, !(jmp_e & (ic_miss_state === sIdle) & resetReg.io.out))
  
  io.addr_reg_sel := Cat(jmp_e, !jmp_e)
  
  io.addr_reg_enable := jmp_e | !(standbyD1Reg.io.out | io.ibuf_full | valid_diag_window | (ic_miss_state =/= sIdle) | icu_miss | setStallReg.io.out | fourthFillCycFlop.io.out)
  
  io.next_addr_sel := Cat(
    !resetReg.io.out | (ic_miss_state =/= sIdle),
    valid_diag_e & (ic_miss_state === sIdle) & resetReg.io.out,
    (setStallReg.io.out | io.ibuf_full | fourthFillCycFlop.io.out | validDiagCReg.io.out) & resetReg.io.out & (ic_miss_state === sIdle) & !valid_diag_e,
    !setStallReg.io.out & !io.ibuf_full & resetReg.io.out & (ic_miss_state === sIdle) & !valid_diag_e & !fourthFillCycFlop.io.out
  )
  
  io.biu_addr_sel := Cat((ic_miss_state === sReqState) | ((ic_miss_state === sIdle) & cacheable), !((ic_miss_state === sReqState) | ((ic_miss_state === sIdle) & cacheable)))
  
  io.icu_tag_sel := normal_ack | ((ic_miss_state === sIdle) & !valid_diag_e)
  
  io.ic_data_sel := normal_ack | error_ack
  
  io.icu_itag_we := cache_fill_cyc | diag_st_tag_e | qual_iu_flush_e
  
  io.icu_ram_we := Cat(
    ((ic_miss_state === sReqState) | (ic_miss_state === sFill2ndWd)) & normal_ack | (diag_st_cache_e & !io.misc_wrd_sel),
    ((ic_miss_state === sFill2ndWd) | (ic_miss_state === sFill4thWd)) & normal_ack | (diag_st_cache_e & io.misc_wrd_sel)
  )
  
  io.icu_bypass_q := icuBypassReg.io.out
  
  io.icu_tag_vld := ((ic_miss_state === sFill4thWd) & normal_ack | diag_st_tag_e & io.iu_data_e_0) & !qual_iu_flush_e
  
  io.icu_stall := icu_miss | (ic_miss_state =/= sIdle) | valid_diag_window | setStallReg.io.out | io.ibuf_full | standbyD1Reg.io.out | !resetReg.io.out | fourthFillCycFlop.io.out
  
  io.icu_req := (ic_miss_state === sNcReqState) | (ic_miss_state === sReqState)
  
  io.icu_type := Cat(0.U(2.W), (ic_miss_state === sNcReqState), 0.U(1.W))
  
  io.icu_size := Cat(!io.iu_psr_bm8, 0.U(1.W))
  
  io.diag_ld_cache_c := diagLdCacheCReg.io.out
  
  io.icram_powerdown := (ic_miss_state =/= sIdle) & !normal_ack & !valid_diag_window
  
  io.icu_in_powerdown := (ic_miss_state === sStandbyPwrDn) & !jmp_e
  
  io.icu_hold := (io.iu_ic_diag_e.orR | (io.iu_flush_e & io.iu_psr_ice)) & (ic_miss_state =/= sIdle)
  
  io.ic_drty := icDrtyReg.io.out
  
  io.bypass_ack := bypass_ack
  
  // State machine
  when(!io.reset_l) {
    ic_miss_state := sIdle
  }.otherwise {
    switch(ic_miss_state) {
      is(sIdle) {
        when(io.pcsu_powerdown & !jmp_e & !valid_diag_window) {
          ic_miss_state := sStandbyPwrDn
        }.elsewhen(valid_diag_window | io.ibuf_full | jmp_e) {
          ic_miss_state := ic_miss_state
        }.elsewhen(icu_miss & !cacheable) {
          ic_miss_state := sNcReqState
        }.elsewhen(icu_miss & cacheable) {
          ic_miss_state := sReqState
        }
      }
      is(sNcReqState) {
        when(normal_ack | error_ack) {
          ic_miss_state := sIdle
        }
      }
      is(sReqState) {
        when(normal_ack) {
          ic_miss_state := sFill2ndWd
        }.elsewhen(error_ack) {
          ic_miss_state := sIdle
        }
      }
      is(sFill2ndWd) {
        when(normal_ack) {
          ic_miss_state := sReqState2
        }.elsewhen(error_ack) {
          ic_miss_state := sIdle
        }
      }
      is(sReqState2) {
        when(normal_ack) {
          ic_miss_state := sFill4thWd
        }.elsewhen(error_ack) {
          ic_miss_state := sIdle
        }
      }
      is(sFill4thWd) {
        when(normal_ack | error_ack) {
          ic_miss_state := sIdle
        }
      }
      is(sStandbyPwrDn) {
        when(!io.pcsu_powerdown | jmp_e) {
          ic_miss_state := sIdle
        }
      }
    }
  }
}

class Env extends Module {
  val io = IO(new Bundle {
    val biu_icu_ack = Input(UInt(2.W))
    val clk = Input(Clock())
    val icu_addr_2_0 = Input(UInt(3.W))
    val icu_hit = Input(Bool())
    val iu_brtaken_e = Input(Bool())
    val iu_data_e_0 = Input(Bool())
    val iu_flush_e = Input(Bool())
    val iu_ic_diag_e = Input(UInt(4.W))
    val iu_psr_ice = Input(Bool())
    val reset_l = Input(Bool())
    val pcsu_powerdown = Input(Bool())
    val iu_psr_bm8 = Input(Bool())
    val ice_line_align = Input(Bool())
    val misc_wrd_sel = Input(Bool())
    
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
  
  val iu_shift_d = RegInit("b00000001".U(8.W))
  
  // Generate iu_shift_d (one-hot encoder)
  val iu_shift_ND = Wire(UInt(3.W))
  iu_shift_ND := 0.U
  for (i <- 0 until 8) {
    when(iu_shift_d(i)) {
      iu_shift_ND := i.U
    }
  }
  
  when(true.B) { // Always on positive edge
    switch(iu_shift_ND) {
      is(0.U) { iu_shift_d := "b00000010".U }
      is(1.U) { iu_shift_d := "b00000100".U }
      is(2.U) { iu_shift_d := "b00001000".U }
      is(3.U) { iu_shift_d := "b00010000".U }
      is(4.U) { iu_shift_d := "b00100000".U }
      is(5.U) { iu_shift_d := "b01000000".U }
      is(6.U) { iu_shift_d := "b10000000".U }
      is(7.U) { iu_shift_d := "b00000001".U }
    }
  }
  
  val icctl = Module(new ICCntl())
  
  // Connect inputs
  icctl.io.biu_icu_ack := io.biu_icu_ack
  icctl.io.clk := io.clk
  icctl.io.icu_addr_2_0 := io.icu_addr_2_0
  icctl.io.icu_hit := io.icu_hit
  icctl.io.iu_brtaken_e := io.iu_brtaken_e
  icctl.io.iu_data_e_0 := io.iu_data_e_0
  icctl.io.iu_flush_e := io.iu_flush_e
  icctl.io.iu_ic_diag_e := io.iu_ic_diag_e
  icctl.io.iu_psr_ice := io.iu_psr_ice
  icctl.io.iu_shift_d := iu_shift_d
  icctl.io.reset_l := io.reset_l
  icctl.io.pcsu_powerdown := io.pcsu_powerdown
  icctl.io.iu_psr_bm8 := io.iu_psr_bm8
  icctl.io.ice_line_align := io.ice_line_align
  icctl.io.misc_wrd_sel := io.misc_wrd_sel
  
  // Connect outputs
  io.next_fetch_inc := icctl.io.next_fetch_inc
  io.encod_shift_e := icctl.io.encod_shift_e
  io.ibuf_pc_sel := icctl.io.ibuf_pc_sel
  io.icu_addr_sel := icctl.io.icu_addr_sel
  io.ibuf_enable := icctl.io.ibuf_enable
  io.ic_data_sel := icctl.io.ic_data_sel
  io.ic_dout_sel := icctl.io.ic_dout_sel
  io.icu_bypass_q := icctl.io.icu_bypass_q
  io.icu_drty_d := icctl.io.icu_drty_d
  io.icu_itag_we := icctl.io.icu_itag_we
  io.latch_biu_addr := icctl.io.latch_biu_addr
  io.icu_ram_we := icctl.io.icu_ram_we
  io.icu_req := icctl.io.icu_req
  io.icu_size := icctl.io.icu_size
  io.icu_tag_sel := icctl.io.icu_tag_sel
  io.icu_tag_vld := icctl.io.icu_tag_vld
  io.icu_type := icctl.io.icu_type
  io.icu_vld_d := icctl.io.icu_vld_d
  io.next_addr_sel := icctl.io.next_addr_sel
  io.addr_reg_sel := icctl.io.addr_reg_sel
  io.addr_reg_enable := icctl.io.addr_reg_enable
  io.biu_addr_sel := icctl.io.biu_addr_sel
  io.diag_ld_cache_c := icctl.io.diag_ld_cache_c
  io.icu_in_powerdown := icctl.io.icu_in_powerdown
  io.icram_powerdown := icctl.io.icram_powerdown
  io.icu_hold := icctl.io.icu_hold
  io.valid := icctl.io.valid
  io.fill_word_addr := icctl.io.fill_word_addr
  io.bypass_ack := icctl.io.bypass_ack
}

object VerilogGenerator extends App {
  emitVerilog(new Env(), args)
}