package llmverify

import chisel3._
import chisel3.util._

class ibuf_ctl extends Module {
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
    val ibuf_enable_out = Output(Bool())
    val valid = Output(UInt(16.W))
    val reset_l = Input(Bool())
    val jmp_e = Input(Bool())
    val iu_psr_bm8 = Input(Bool())
    val icu_bypass_q = Input(Bool())
  })

  // Internal signals
  val ic_dout_sel_ext = Wire(UInt(4.W))
  val dirty = Wire(UInt(16.W))
  val new_valid = Wire(UInt(16.W))
  val new_dirty = Wire(UInt(16.W))
  val ibuf_en = Wire(Bool())
  val squash_vld = Wire(Bool())
  val ic_fill_sel = Wire(Vec(16, Bool()))
  val iu_shift_e = Wire(UInt(8.W))

  // Assignments
  io.ibuf_enable := (!io.icu_stall) | (!io.iu_shift_d(0))
  ibuf_en := (!io.icu_stall) | (!io.iu_shift_d(0)) | io.jmp_e

  // Create 16 instances of ibuf_ctl_slice
  val ibuf_ctl_slices = Seq.fill(16)(Module(new ibuf_ctl_slice()))

  // Initialize valid and dirty outputs from slices
  val slice_valid_out = Wire(Vec(16, Bool()))
  val slice_dirty_out = Wire(Vec(16, Bool()))
  val slice_buf_ic_valid = Wire(Vec(16, Bool()))
  val slice_buf_ic_drty = Wire(Vec(16, Bool()))
  
  for (i <- 0 until 16) {
    slice_valid_out(i) := ibuf_ctl_slices(i).io.valid_out
    slice_dirty_out(i) := ibuf_ctl_slices(i).io.dirty_out
    slice_buf_ic_valid(i) := ibuf_ctl_slices(i).io.buf_ic_valid
    slice_buf_ic_drty(i) := ibuf_ctl_slices(i).io.buf_ic_drty
  }

  // Connect slice inputs and outputs
  for (i <- 0 until 16) {
    val slice = ibuf_ctl_slices(i).io
    
    // Set valid_bits and dirty_bits based on slice index
    if (i == 0) {
      slice.valid_bits := Cat(slice_buf_ic_valid(7), slice_buf_ic_valid(6), slice_buf_ic_valid(5), slice_buf_ic_valid(4), slice_buf_ic_valid(3), slice_buf_ic_valid(2), slice_buf_ic_valid(1))
      slice.dirty_bits := Cat(slice_buf_ic_drty(7), slice_buf_ic_drty(6), slice_buf_ic_drty(5), slice_buf_ic_drty(4), slice_buf_ic_drty(3), slice_buf_ic_drty(2), slice_buf_ic_drty(1))
    } else if (i <= 8) {
      slice.valid_bits := Cat(slice_buf_ic_valid(i + 7), slice_buf_ic_valid(i + 6), slice_buf_ic_valid(i + 5), slice_buf_ic_valid(i + 4), slice_buf_ic_valid(i + 3), slice_buf_ic_valid(i + 2), slice_buf_ic_valid(i + 1))
      slice.dirty_bits := Cat(slice_buf_ic_drty(i + 7), slice_buf_ic_drty(i + 6), slice_buf_ic_drty(i + 5), slice_buf_ic_drty(i + 4), slice_buf_ic_drty(i + 3), slice_buf_ic_drty(i + 2), slice_buf_ic_drty(i + 1))
    } else if (i == 9) {
      slice.valid_bits := Cat(0.U(1.W), slice_buf_ic_valid(15), slice_buf_ic_valid(14), slice_buf_ic_valid(13), slice_buf_ic_valid(12), slice_buf_ic_valid(11), slice_buf_ic_valid(10))
      slice.dirty_bits := Cat(0.U(1.W), slice_buf_ic_drty(15), slice_buf_ic_drty(14), slice_buf_ic_drty(13), slice_buf_ic_drty(12), slice_buf_ic_drty(11), slice_buf_ic_drty(10))
    } else if (i == 10) {
      slice.valid_bits := Cat(0.U(2.W), slice_buf_ic_valid(15), slice_buf_ic_valid(14), slice_buf_ic_valid(13), slice_buf_ic_valid(12), slice_buf_ic_valid(11))
      slice.dirty_bits := Cat(0.U(2.W), slice_buf_ic_drty(15), slice_buf_ic_drty(14), slice_buf_ic_drty(13), slice_buf_ic_drty(12), slice_buf_ic_drty(11))
    } else if (i == 11) {
      slice.valid_bits := Cat(0.U(3.W), slice_buf_ic_valid(15), slice_buf_ic_valid(14), slice_buf_ic_valid(13), slice_buf_ic_valid(12))
      slice.dirty_bits := Cat(0.U(3.W), slice_buf_ic_drty(15), slice_buf_ic_drty(14), slice_buf_ic_drty(13), slice_buf_ic_drty(12))
    } else if (i == 12) {
      slice.valid_bits := Cat(0.U(4.W), slice_buf_ic_valid(15), slice_buf_ic_valid(14), slice_buf_ic_valid(13))
      slice.dirty_bits := Cat(0.U(4.W), slice_buf_ic_drty(15), slice_buf_ic_drty(14), slice_buf_ic_drty(13))
    } else if (i == 13) {
      slice.valid_bits := Cat(0.U(5.W), slice_buf_ic_valid(15), slice_buf_ic_valid(14))
      slice.dirty_bits := Cat(0.U(5.W), slice_buf_ic_drty(15), slice_buf_ic_drty(14))
    } else if (i == 14) {
      slice.valid_bits := Cat(0.U(6.W), slice_buf_ic_valid(15))
      slice.dirty_bits := Cat(0.U(6.W), slice_buf_ic_drty(15))
    } else { // i == 15
      slice.valid_bits := 0.U(7.W)
      slice.dirty_bits := 0.U(7.W)
    }
    
    slice.shft_dsel := io.iu_shift_d
    slice.jmp_e := io.jmp_e
    slice.ibuf_en := ibuf_en
    slice.icu_stall := io.icu_stall
    slice.new_valid := new_valid(i)
    slice.new_dirty := new_dirty(i)
    slice.reset_l := io.reset_l
    
    ic_fill_sel(i) := slice.fill_sel
  }

  // Connect valid and dirty outputs
  io.valid := Cat(slice_valid_out.reverse)
  dirty := Cat(slice_dirty_out.reverse)

  // Generate new_valid bits
  val dword_align = (io.icu_addr_2_0 === 0.U(3.W))
  
  new_valid(0) := 1.U(1.W)
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
  io.ic_dout_sel(0) := !io.valid(0)
  io.ic_dout_sel(1) := io.valid(0) & !io.valid(1)
  io.ic_dout_sel(2) := io.valid(1) & !io.valid(2)
  io.ic_dout_sel(3) := io.valid(2) & !io.valid(3)
  io.ic_dout_sel(4) := io.valid(3) & !io.valid(4)
  io.ic_dout_sel(5) := io.valid(4) & !io.valid(5)
  io.ic_dout_sel(6) := io.valid(5) & !io.valid(6)
  io.ic_dout_sel(7) := io.valid(6) & !io.valid(7)
  io.ic_dout_sel(8) := io.valid(7) & !io.valid(8)
  io.ic_dout_sel(9) := io.valid(8) & !io.valid(9)
  io.ic_dout_sel(10) := io.valid(9) & !io.valid(10)
  io.ic_dout_sel(11) := io.valid(10) & !io.valid(11)
  ic_dout_sel_ext(0) := io.valid(11) & !io.valid(12)
  ic_dout_sel_ext(1) := io.valid(12) & !io.valid(13)
  ic_dout_sel_ext(2) := io.valid(13) & !io.valid(14)
  ic_dout_sel_ext(3) := io.valid(14) & !io.valid(15)

  // Generate new_dirty bits
  new_dirty(0) := io.icu_bypass_q & io.ic_drty & io.ic_dout_sel(0)
  new_dirty(1) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(0)) | (!io.iu_psr_bm8 & io.ic_dout_sel(1, 0).orR))
  new_dirty(2) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(1)) | (!io.iu_psr_bm8 & io.ic_dout_sel(2, 0).orR))
  new_dirty(3) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(2)) | (!io.iu_psr_bm8 & io.ic_dout_sel(3, 0).orR))
  new_dirty(4) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(3)) | (!io.iu_psr_bm8 & io.ic_dout_sel(4, 1).orR))
  new_dirty(5) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(4)) | (!io.iu_psr_bm8 & io.ic_dout_sel(5, 2).orR))
  new_dirty(6) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(5)) | (!io.iu_psr_bm8 & io.ic_dout_sel(6, 3).orR))
  new_dirty(7) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(6)) | (!io.iu_psr_bm8 & io.ic_dout_sel(7, 4).orR))
  new_dirty(8) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(7)) | (!io.iu_psr_bm8 & io.ic_dout_sel(8, 5).orR))
  new_dirty(9) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(8)) | (!io.iu_psr_bm8 & io.ic_dout_sel(9, 6).orR))
  new_dirty(10) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(9)) | (!io.iu_psr_bm8 & io.ic_dout_sel(10, 7).orR))
  new_dirty(11) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(10)) | (!io.iu_psr_bm8 & io.ic_dout_sel(11, 8).orR))
  new_dirty(12) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & io.ic_dout_sel(11)) | (!io.iu_psr_bm8 & (io.ic_dout_sel(11, 9).orR | ic_dout_sel_ext(0))))
  new_dirty(13) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & ic_dout_sel_ext(0)) | (!io.iu_psr_bm8 & (io.ic_dout_sel(11, 10).orR | ic_dout_sel_ext(1, 0).orR)))
  new_dirty(14) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & ic_dout_sel_ext(1)) | (!io.iu_psr_bm8 & (io.ic_dout_sel(11) | ic_dout_sel_ext(2, 0).orR)))
  new_dirty(15) := io.icu_bypass_q & io.ic_drty & ((io.iu_psr_bm8 & ic_dout_sel_ext(2)) | (!io.iu_psr_bm8 & ic_dout_sel_ext(3, 0).orR))

  // Generate squash_vld
  val squash_vld_reg = RegInit(0.U(1.W))
  when(io.reset_l) {
    squash_vld_reg := io.jmp_e
  }.otherwise {
    squash_vld_reg := 0.U
  }
  squash_vld := squash_vld_reg

  // Generate ibuf_full
  io.ibuf_full := (io.valid(12) | ((!io.icu_bypass_q | io.iu_psr_bm8) & io.valid(8))) & !squash_vld

  // Generate icu_vld_d and icu_drty_d
  io.icu_vld_d := io.valid(6, 0)
  io.icu_drty_d := dirty(6, 0)

  // Generate iu_shift_e register
  val iu_shift_e_reg = RegInit(0.U(8.W))
  when(io.reset_l) {
    iu_shift_e_reg := io.iu_shift_d
  }.otherwise {
    iu_shift_e_reg := 0.U
  }
  iu_shift_e := iu_shift_e_reg

  // Generate encod_shift_e
  val encode_shift = Module(new encode_shift_module())
  encode_shift.io.iu_shift_e := iu_shift_e
  io.encod_shift_e := encode_shift.io.encod_shift_e

  // Generate ibuf_pc_sel
  io.ibuf_pc_sel(1) := squash_vld
  io.ibuf_pc_sel(0) := !squash_vld

  // Additional output to preserve design
  io.ibuf_enable_out := io.ibuf_enable
}

class encode_shift_module extends Module {
  val io = IO(new Bundle {
    val iu_shift_e = Input(UInt(8.W))
    val encod_shift_e = Output(UInt(3.W))
  })

  io.encod_shift_e := MuxCase(0.U(3.W), Seq(
    (io.iu_shift_e === 0.U(8.W)) -> 0.U(3.W),
    (io.iu_shift_e === 2.U(8.W)) -> 1.U(3.W),
    (io.iu_shift_e === 4.U(8.W)) -> 2.U(3.W),
    (io.iu_shift_e === 8.U(8.W)) -> 3.U(3.W),
    (io.iu_shift_e === 16.U(8.W)) -> 4.U(3.W),
    (io.iu_shift_e === 32.U(8.W)) -> 5.U(3.W),
    (io.iu_shift_e === 64.U(8.W)) -> 6.U(3.W),
    (io.iu_shift_e === 128.U(8.W)) -> 7.U(3.W)
  ))
}

class ibuf_ctl_slice extends Module {
  val io = IO(new Bundle {
    val valid_bits = Input(UInt(7.W))
    val dirty_bits = Input(UInt(7.W))
    val shft_dsel = Input(UInt(8.W))
    val valid_out = Output(Bool())
    val dirty_out = Output(Bool())
    val new_valid = Input(Bool())
    val icu_stall = Input(Bool())
    val fill_sel = Output(Bool())
    val ibuf_en = Input(Bool())
    val new_dirty = Input(Bool())
    val buf_ic_drty = Output(Bool())
    val buf_ic_valid = Output(Bool())
    val jmp_e = Input(Bool())
    val reset_l = Input(Bool())
  })

  val valid_in = Wire(Bool())
  val dirty_in = Wire(Bool())
  val current_valid_out = RegInit(0.U(1.W))
  val current_dirty_out = RegInit(0.U(1.W))

  io.valid_out := current_valid_out
  io.dirty_out := current_dirty_out

  io.buf_ic_valid := current_valid_out | (io.new_valid & !io.icu_stall)
  io.buf_ic_drty := current_dirty_out | (io.new_dirty & !io.icu_stall)

  // 8-to-1 multiplexer for valid_in
  valid_in := MuxCase(io.buf_ic_valid, Seq(
    io.shft_dsel(0) -> io.buf_ic_valid,
    io.shft_dsel(1) -> io.valid_bits(0),
    io.shft_dsel(2) -> io.valid_bits(1),
    io.shft_dsel(3) -> io.valid_bits(2),
    io.shft_dsel(4) -> io.valid_bits(3),
    io.shft_dsel(5) -> io.valid_bits(4),
    io.shft_dsel(6) -> io.valid_bits(5),
    io.shft_dsel(7) -> io.valid_bits(6)
  ))

  // 8-to-1 multiplexer for dirty_in
  dirty_in := MuxCase(io.buf_ic_drty, Seq(
    io.shft_dsel(0) -> io.buf_ic_drty,
    io.shft_dsel(1) -> io.dirty_bits(0),
    io.shft_dsel(2) -> io.dirty_bits(1),
    io.shft_dsel(3) -> io.dirty_bits(2),
    io.shft_dsel(4) -> io.dirty_bits(3),
    io.shft_dsel(5) -> io.dirty_bits(4),
    io.shft_dsel(6) -> io.dirty_bits(5),
    io.shft_dsel(7) -> io.dirty_bits(6)
  ))

  io.fill_sel := current_valid_out

  // Flip-flop with enable and reset for valid_out
  when(io.reset_l) {
    when(io.ibuf_en) {
      current_valid_out := valid_in & !io.jmp_e
    }
  }.otherwise {
    current_valid_out := 0.U
  }

  // Flip-flop with enable and reset for dirty_out
  when(io.reset_l) {
    when(io.ibuf_en) {
      current_dirty_out := dirty_in & !io.jmp_e
    }
  }.otherwise {
    current_dirty_out := 0.U
  }
}

object VerilogGenerator extends App {
  emitVerilog(new ibuf_ctl(), args)
}