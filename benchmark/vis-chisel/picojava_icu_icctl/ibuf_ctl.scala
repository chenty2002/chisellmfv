package llmverify

import chisel3._
import chisel3.util._

/**
 * Instruction buffer control module
 * Manages instruction buffer valid/dirty bits and data selection
 */
class ibuf_ctl extends Module {
  val io = IO(new Bundle {
    // Inputs
    val ic_drty = Input(Bool())
    val icu_stall = Input(Bool())
    val iu_shift_d = Input(UInt(8.W))
    val icu_addr_2_0 = Input(UInt(3.W))
    val reset_l = Input(Bool())
    val jmp_e = Input(Bool())
    val iu_psr_bm8 = Input(Bool())
    val icu_bypass_q = Input(Bool())
    
    // Outputs
    val ibuf_enable = Output(Bool())
    val ic_dout_sel = Output(UInt(12.W))
    val icu_vld_d = Output(UInt(7.W))
    val icu_drty_d = Output(UInt(7.W))
    val ibuf_full = Output(Bool())
    val encod_shift_e = Output(UInt(3.W))
    val ibuf_pc_sel = Output(UInt(2.W))
    val valid = Output(UInt(16.W))
  })
  
  // Internal signals
  val ibuf_en = !io.icu_stall | !io.iu_shift_d(0) | io.jmp_e
  io.ibuf_enable := !io.icu_stall | !io.iu_shift_d(0)
  
  // Valid and dirty bits for each buffer entry
  val valid = Wire(Vec(16, Bool()))
  val dirty = Wire(Vec(16, Bool()))
  val buf_ic_valid = Wire(Vec(16, Bool()))
  val buf_ic_drty = Wire(Vec(16, Bool()))
  val new_valid = Wire(Vec(16, Bool()))
  val new_dirty = Wire(Vec(16, Bool()))
  val ic_fill_sel = Wire(Vec(16, Bool()))
  
  // Create ibuf_ctl_slice instances for each buffer entry
  val slices = (0 until 16).map { i =>
    val slice = Module(new ibuf_ctl_slice)
    
    // Connect valid_bits and dirty_bits
    if (i < 15) {
      slice.io.valid_bits := Cat(buf_ic_valid.slice(i+1, i+8).reverse)
      slice.io.dirty_bits := Cat(buf_ic_drty.slice(i+1, i+8).reverse)
    } else {
      slice.io.valid_bits := 0.U(7.W)
      slice.io.dirty_bits := 0.U(7.W)
    }
    
    slice.io.shft_dsel := io.iu_shift_d
    slice.io.jmp_e := io.jmp_e
    slice.io.ibuf_en := ibuf_en
    slice.io.icu_stall := io.icu_stall
    slice.io.fill_sel := ic_fill_sel(i)
    slice.io.new_valid := new_valid(i)
    slice.io.new_dirty := new_dirty(i)
    slice.io.reset_l := io.reset_l
    
    valid(i) := slice.io.valid_out
    dirty(i) := slice.io.dirty_out
    buf_ic_valid(i) := slice.io.buf_ic_valid
    buf_ic_drty(i) := slice.io.buf_ic_drty
    
    slice
  }
  
  // Generate new_valid bits based on cache fill logic
  val dword_align = (io.icu_addr_2_0 === 0.U)
  
  new_valid(0) := true.B
  new_valid(1) := ((!io.icu_bypass_q & !io.icu_addr_2_0(2)) | !(io.icu_addr_2_0(1) & io.icu_addr_2_0(0)) & !io.iu_psr_bm8) | (ic_fill_sel(0) & io.iu_psr_bm8)
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
  
  // Generate ic_dout_sel signals
  val ic_dout_sel_ext = Wire(UInt(4.W))
  ic_dout_sel_ext(0) := valid(11) & !valid(12)
  ic_dout_sel_ext(1) := valid(12) & !valid(13)
  ic_dout_sel_ext(2) := valid(13) & !valid(14)
  ic_dout_sel_ext(3) := valid(14) & !valid(15)
  
  io.ic_dout_sel(0) := !valid(0)
  io.ic_dout_sel(1) := valid(0) & !valid(1)
  io.ic_dout_sel(2) := valid(1) & !valid(2)
  io.ic_dout_sel(3) := valid(2) & !valid(3)
  io.ic_dout_sel(4) := valid(3) & !valid(4)
  io.ic_dout_sel(5) := valid(4) & !valid(5)
  io.ic_dout_sel(6) := valid(5) & !valid(6)
  io.ic_dout_sel(7) := valid(6) & !valid(7)
  io.ic_dout_sel(8) := valid(7) & !valid(8)
  io.ic_dout_sel(9) := valid(8) & !valid(9)
  io.ic_dout_sel(10) := valid(9) & !valid(10)
  io.ic_dout_sel(11) := valid(10) & !valid(11)
  
  // Combine extended bits
  io.ic_dout_sel := Cat(ic_dout_sel_ext, io.ic_dout_sel(11, 0))
  
  // Generate new_dirty bits using individual assignments instead of loop
  new_dirty(0) := io.icu_bypass_q & io.ic_drty & io.ic_dout_sel(0)
  new_dirty(1) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(0)) | (!io.iu_psr_bm8 & (io.ic_dout_sel(1) | io.ic_dout_sel(0))))
  new_dirty(2) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(1)) | (!io.iu_psr_bm8 & (io.ic_dout_sel(2) | io.ic_dout_sel(1) | io.ic_dout_sel(0))))
  new_dirty(3) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(2)) | (!io.iu_psr_bm8 & (io.ic_dout_sel(3) | io.ic_dout_sel(2) | io.ic_dout_sel(1) | io.ic_dout_sel(0))))
  new_dirty(4) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(3)) | (!io.iu_psr_bm8 & (io.ic_dout_sel(4) | io.ic_dout_sel(3) | io.ic_dout_sel(2) | io.ic_dout_sel(1))))
  new_dirty(5) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(4)) | (!io.iu_psr_bm8 & (io.ic_dout_sel(5) | io.ic_dout_sel(4) | io.ic_dout_sel(3) | io.ic_dout_sel(2))))
  new_dirty(6) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(5)) | (!io.iu_psr_bm8 & (io.ic_dout_sel(6) | io.ic_dout_sel(5) | io.ic_dout_sel(4) | io.ic_dout_sel(3))))
  new_dirty(7) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(6)) | (!io.iu_psr_bm8 & (io.ic_dout_sel(7) | io.ic_dout_sel(6) | io.ic_dout_sel(5) | io.ic_dout_sel(4))))
  new_dirty(8) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(7)) | (!io.iu_psr_bm8 & (io.ic_dout_sel(8) | io.ic_dout_sel(7) | io.ic_dout_sel(6) | io.ic_dout_sel(5))))
  new_dirty(9) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(8)) | (!io.iu_psr_bm8 & (io.ic_dout_sel(9) | io.ic_dout_sel(8) | io.ic_dout_sel(7) | io.ic_dout_sel(6))))
  new_dirty(10) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(9)) | (!io.iu_psr_bm8 & (io.ic_dout_sel(10) | io.ic_dout_sel(9) | io.ic_dout_sel(8) | io.ic_dout_sel(7))))
  new_dirty(11) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(10)) | (!io.iu_psr_bm8 & (io.ic_dout_sel(11) | io.ic_dout_sel(10) | io.ic_dout_sel(9) | io.ic_dout_sel(8))))
  new_dirty(12) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & ic_dout_sel_ext(0)) | (!io.iu_psr_bm8 & (io.ic_dout_sel(11) | io.ic_dout_sel(10) | io.ic_dout_sel(9) | ic_dout_sel_ext(0))))
  new_dirty(13) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & ic_dout_sel_ext(1)) | (!io.iu_psr_bm8 & (io.ic_dout_sel(11) | io.ic_dout_sel(10) | (ic_dout_sel_ext(1) | ic_dout_sel_ext(0)))))
  new_dirty(14) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & ic_dout_sel_ext(2)) | (!io.iu_psr_bm8 & (io.ic_dout_sel(11) | (ic_dout_sel_ext(2) | ic_dout_sel_ext(1) | ic_dout_sel_ext(0)))))
  new_dirty(15) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & ic_dout_sel_ext(3)) | (!io.iu_psr_bm8 & (ic_dout_sel_ext(3) | ic_dout_sel_ext(2) | ic_dout_sel_ext(1) | ic_dout_sel_ext(0))))
  
  // Generate ibuf_full signal
  val squash_vld = RegNext(io.jmp_e, false.B)
  io.ibuf_full := (valid(12) | ((!io.icu_bypass_q | io.iu_psr_bm8) & valid(8))) & !squash_vld
  
  // Generate outputs
  io.icu_vld_d := Cat(valid.slice(0, 7).reverse)
  io.icu_drty_d := Cat(dirty.slice(0, 7).reverse)
  io.valid := Cat(valid.reverse)
  
  // Generate encod_shift_e
  val iu_shift_e = RegNext(io.iu_shift_d, 0.U)
  io.encod_shift_e := MuxLookup(iu_shift_e, 0.U(3.W), Seq(
    "b00000001".U -> 0.U(3.W),
    "b00000010".U -> 1.U(3.W),
    "b00000100".U -> 2.U(3.W),
    "b00001000".U -> 3.U(3.W),
    "b00010000".U -> 4.U(3.W),
    "b00100000".U -> 5.U(3.W),
    "b01000000".U -> 6.U(3.W),
    "b10000000".U -> 7.U(3.W)
  ))
  
  // Generate ibuf_pc_sel
  io.ibuf_pc_sel := Cat(squash_vld, !squash_vld)
}

/**
 * Single slice of instruction buffer control
 */
class ibuf_ctl_slice extends Module {
  val io = IO(new Bundle {
    val valid_bits = Input(UInt(7.W))
    val dirty_bits = Input(UInt(7.W))
    val shft_dsel = Input(UInt(8.W))
    val jmp_e = Input(Bool())
    val ibuf_en = Input(Bool())
    val icu_stall = Input(Bool())
    val fill_sel = Input(Bool())
    val new_valid = Input(Bool())
    val new_dirty = Input(Bool())
    val reset_l = Input(Bool())
    
    val valid_out = Output(Bool())
    val dirty_out = Output(Bool())
    val buf_ic_valid = Output(Bool())
    val buf_ic_drty = Output(Bool())
  })
  
  val valid_out = RegInit(false.B)
  val dirty_out = RegInit(false.B)
  
  io.buf_ic_valid := valid_out | (io.new_valid & !io.icu_stall)
  io.buf_ic_drty := dirty_out | (io.new_dirty & !io.icu_stall)
  
  // 8-to-1 multiplexer for valid and dirty bits
  val valid_in = MuxLookup(io.shft_dsel, false.B, Seq(
    "b00000001".U -> io.buf_ic_valid,
    "b00000010".U -> io.valid_bits(0),
    "b00000100".U -> io.valid_bits(1),
    "b00001000".U -> io.valid_bits(2),
    "b00010000".U -> io.valid_bits(3),
    "b00100000".U -> io.valid_bits(4),
    "b01000000".U -> io.valid_bits(5),
    "b10000000".U -> io.valid_bits(6)
  ))
  
  val dirty_in = MuxLookup(io.shft_dsel, false.B, Seq(
    "b00000001".U -> io.buf_ic_drty,
    "b00000010".U -> io.dirty_bits(0),
    "b00000100".U -> io.dirty_bits(1),
    "b00001000".U -> io.dirty_bits(2),
    "b00010000".U -> io.dirty_bits(3),
    "b00100000".U -> io.dirty_bits(4),
    "b01000000".U -> io.dirty_bits(5),
    "b10000000".U -> io.dirty_bits(6)
  ))
  
  io.fill_sel := valid_out
  
  // Register updates
  when(io.reset_l) {
    when(io.ibuf_en) {
      valid_out := valid_in & !io.jmp_e
      dirty_out := dirty_in & !io.jmp_e
    }
  }.otherwise {
    valid_out := false.B
    dirty_out := false.B
  }
  
  io.valid_out := valid_out
  io.dirty_out := dirty_out
}