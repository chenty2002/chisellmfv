package reeds

import chisel3._
import chisel3.util.Cat


class RS_dec(val variant: RSVariant = RSVariant.Reference) extends Module with AsyncRegs {
  val CE = IO(Input(Bool()))
  val input_byte = IO(Input(UInt(8.W)))
  val Out_byte = IO(Output(UInt(8.W)))
  val CEO = IO(Output(Bool()))
  val Valid_out = IO(Output(Bool()))

  val CEO_0 = Wire(Bool()) 
  CEO := CEO_0 && Valid_out
  val S_ready = Wire(Bool()) 
  val s0 = Wire(UInt(8.W)) 
  val s1 = Wire(UInt(8.W)) 
  val s2 = Wire(UInt(8.W)) 
  val s3 = Wire(UInt(8.W)) 
  val s4 = Wire(UInt(8.W)) 
  val s5 = Wire(UInt(8.W)) 
  val s6 = Wire(UInt(8.W)) 
  val s7 = Wire(UInt(8.W)) 
  val s8 = Wire(UInt(8.W)) 
  val s9 = Wire(UInt(8.W)) 
  val s10 = Wire(UInt(8.W)) 
  val s11 = Wire(UInt(8.W)) 
  val s12 = Wire(UInt(8.W)) 
  val s13 = Wire(UInt(8.W)) 
  val s14 = Wire(UInt(8.W)) 
  val s15 = Wire(UInt(8.W)) 
  val In_mem_Read_byte = Wire(UInt(8.W)) 
  val In_mem_R_Add = Wire(UInt(8.W)) 
  val In_mem_RE = Wire(Bool()) 

  val input_syndromes_unit = Module(new input_syndromes)
  input_syndromes_unit.reset := reset
  input_syndromes_unit.CE := CE
  input_syndromes_unit.input_byte := input_byte
  input_syndromes_unit.R_Add := In_mem_R_Add
  input_syndromes_unit.RE := In_mem_RE
  In_mem_Read_byte := input_syndromes_unit.Read_byte
  S_ready := input_syndromes_unit.S_Ready
  s0 := input_syndromes_unit.s0
  s1 := input_syndromes_unit.s1
  s2 := input_syndromes_unit.s2
  s3 := input_syndromes_unit.s3
  s4 := input_syndromes_unit.s4
  s5 := input_syndromes_unit.s5
  s6 := input_syndromes_unit.s6
  s7 := input_syndromes_unit.s7
  s8 := input_syndromes_unit.s8
  s9 := input_syndromes_unit.s9
  s10 := input_syndromes_unit.s10
  s11 := input_syndromes_unit.s11
  s12 := input_syndromes_unit.s12
  s13 := input_syndromes_unit.s13
  s14 := input_syndromes_unit.s14
  s15 := input_syndromes_unit.s15

  val WE_transport = Wire(Bool()) 
  val WrAdd_transport = Wire(UInt(8.W)) 
  val transport_done = Wire(Bool()) 

  val transport_in2out_unit = Module(new transport_in2out)
  transport_in2out_unit.reset := reset
  transport_in2out_unit.S_Ready := S_ready
  In_mem_RE := transport_in2out_unit.RE
  In_mem_R_Add := transport_in2out_unit.RdAdd
  WE_transport := transport_in2out_unit.WE
  WrAdd_transport := transport_in2out_unit.WrAdd
  transport_done := transport_in2out_unit.Wr_done

  val L_ready = Wire(Bool()) 
  val L1 = Wire(UInt(8.W)) 
  val L2 = Wire(UInt(8.W)) 
  val L3 = Wire(UInt(8.W)) 
  val L4 = Wire(UInt(8.W)) 
  val L5 = Wire(UInt(8.W)) 
  val L6 = Wire(UInt(8.W)) 
  val L7 = Wire(UInt(8.W)) 
  val L8 = Wire(UInt(8.W)) 
  val pow1_BM_lamda = Wire(UInt(8.W)) 
  val pow2_BM_lamda = Wire(UInt(8.W)) 
  val dec1_BM_lamda = Wire(UInt(8.W)) 
  val add_pow1_BM_lamda = Wire(UInt(8.W)) 
  val add_pow2_BM_lamda = Wire(UInt(8.W)) 
  val add_dec1_BM_lamda = Wire(UInt(8.W)) 

  val BM_lamda_unit = Module(new BM_lamda(variant.bug1))
  BM_lamda_unit.reset := reset
  BM_lamda_unit.erasure_ready := "b0".U(1.W)
  BM_lamda_unit.erasure_cnt := "b0".U(4.W)
  BM_lamda_unit.Sm_ready := S_ready
  BM_lamda_unit.Sm1 := s0
  BM_lamda_unit.Sm2 := s1
  BM_lamda_unit.Sm3 := s2
  BM_lamda_unit.Sm4 := s3
  BM_lamda_unit.Sm5 := s4
  BM_lamda_unit.Sm6 := s5
  BM_lamda_unit.Sm7 := s6
  BM_lamda_unit.Sm8 := s7
  BM_lamda_unit.Sm9 := s8
  BM_lamda_unit.Sm10 := s9
  BM_lamda_unit.Sm11 := s10
  BM_lamda_unit.Sm12 := s11
  BM_lamda_unit.Sm13 := s12
  BM_lamda_unit.Sm14 := s13
  BM_lamda_unit.Sm15 := s14
  BM_lamda_unit.Sm16 := s15
  add_pow1_BM_lamda := BM_lamda_unit.add_pow1
  add_pow2_BM_lamda := BM_lamda_unit.add_pow2
  add_dec1_BM_lamda := BM_lamda_unit.add_dec1
  BM_lamda_unit.pow1 := pow1_BM_lamda
  BM_lamda_unit.pow2 := pow2_BM_lamda
  BM_lamda_unit.dec1 := dec1_BM_lamda
  L_ready := BM_lamda_unit.L_ready
  L1 := BM_lamda_unit.L1
  L2 := BM_lamda_unit.L2
  L3 := BM_lamda_unit.L3
  L4 := BM_lamda_unit.L4
  L5 := BM_lamda_unit.L5
  L6 := BM_lamda_unit.L6
  L7 := BM_lamda_unit.L7
  L8 := BM_lamda_unit.L8

  val roots_ready = Wire(Bool()) 
  val root_cnt = Wire(UInt(4.W)) 
  val r1 = Wire(UInt(8.W)) 
  val r2 = Wire(UInt(8.W)) 
  val r3 = Wire(UInt(8.W)) 
  val r4 = Wire(UInt(8.W)) 
  val r5 = Wire(UInt(8.W)) 
  val r6 = Wire(UInt(8.W)) 
  val r7 = Wire(UInt(8.W)) 
  val r8 = Wire(UInt(8.W)) 
  val pow1_lamda_roots = Wire(UInt(8.W)) 
  val dec1_lamda_roots = Wire(UInt(8.W)) 
  val dec2_lamda_roots = Wire(UInt(8.W)) 
  val dec3_lamda_roots = Wire(UInt(8.W)) 
  val add_pow1_lamda_roots = Wire(UInt(8.W)) 
  val add_dec1_lamda_roots = Wire(UInt(8.W)) 
  val add_dec2_lamda_roots = Wire(UInt(8.W)) 
  val add_dec3_lamda_roots = Wire(UInt(8.W)) 

  val lamda_roots_unit = Module(new lamda_roots)
  lamda_roots_unit.CE := L_ready
  lamda_roots_unit.reset := reset
  lamda_roots_unit.Lc0 := "h01".U(8.W)
  lamda_roots_unit.Lc1 := L1
  lamda_roots_unit.Lc2 := L2
  lamda_roots_unit.Lc3 := L3
  lamda_roots_unit.Lc4 := L4
  lamda_roots_unit.Lc5 := L5
  lamda_roots_unit.Lc6 := L6
  lamda_roots_unit.Lc7 := L7
  lamda_roots_unit.Lc8 := L8
  add_pow1_lamda_roots := lamda_roots_unit.add_GF_ascending
  add_dec1_lamda_roots := lamda_roots_unit.add_GF_dec0
  add_dec2_lamda_roots := lamda_roots_unit.add_GF_dec1
  add_dec3_lamda_roots := lamda_roots_unit.add_GF_dec2
  lamda_roots_unit.power := pow1_lamda_roots
  lamda_roots_unit.decimal0 := dec1_lamda_roots
  lamda_roots_unit.decimal1 := dec2_lamda_roots
  lamda_roots_unit.decimal2 := dec3_lamda_roots
  roots_ready := lamda_roots_unit.CEO
  root_cnt := lamda_roots_unit.root_cnt
  r1 := lamda_roots_unit.r1
  r2 := lamda_roots_unit.r2
  r3 := lamda_roots_unit.r3
  r4 := lamda_roots_unit.r4
  r5 := lamda_roots_unit.r5
  r6 := lamda_roots_unit.r6
  r7 := lamda_roots_unit.r7
  r8 := lamda_roots_unit.r8

  val poly_ready = Wire(Bool()) 
  val O1 = Wire(UInt(8.W)) 
  val O2 = Wire(UInt(8.W)) 
  val O3 = Wire(UInt(8.W)) 
  val O4 = Wire(UInt(8.W)) 
  val O5 = Wire(UInt(8.W)) 
  val O6 = Wire(UInt(8.W)) 
  val O7 = Wire(UInt(8.W)) 
  val O8 = Wire(UInt(8.W)) 
  val O9 = Wire(UInt(8.W)) 
  val O10 = Wire(UInt(8.W)) 
  val O11 = Wire(UInt(8.W)) 
  val O12 = Wire(UInt(8.W)) 
  val O13 = Wire(UInt(8.W)) 
  val O14 = Wire(UInt(8.W)) 
  val O15 = Wire(UInt(8.W)) 
  val O16 = Wire(UInt(8.W)) 
  val P1 = Wire(UInt(8.W)) 
  val P3 = Wire(UInt(8.W)) 
  val P5 = Wire(UInt(8.W)) 
  val P7 = Wire(UInt(8.W)) 
  val dec1_Omega_Phy = Wire(UInt(8.W)) 
  val pow1_Omega_Phy = Wire(UInt(8.W)) 
  val pow2_Omega_Phy = Wire(UInt(8.W)) 
  val pow3_Omega_Phy = Wire(UInt(8.W)) 
  val add_dec1_Omega_Phy = Wire(UInt(8.W)) 
  val add_pow1_Omega_Phy = Wire(UInt(8.W)) 
  val add_pow2_Omega_Phy = Wire(UInt(8.W)) 
  val add_pow3_Omega_Phy = Wire(UInt(8.W)) 

  val Omega_Phy_unit = Module(new Omega_Phy)
  Omega_Phy_unit.reset := reset
  Omega_Phy_unit.Sm_ready := S_ready
  Omega_Phy_unit.Sm1 := s0
  Omega_Phy_unit.Sm2 := s1
  Omega_Phy_unit.Sm3 := s2
  Omega_Phy_unit.Sm4 := s3
  Omega_Phy_unit.Sm5 := s4
  Omega_Phy_unit.Sm6 := s5
  Omega_Phy_unit.Sm7 := s6
  Omega_Phy_unit.Sm8 := s7
  Omega_Phy_unit.Sm9 := s8
  Omega_Phy_unit.Sm10 := s9
  Omega_Phy_unit.Sm11 := s10
  Omega_Phy_unit.Sm12 := s11
  Omega_Phy_unit.Sm13 := s12
  Omega_Phy_unit.Sm14 := s13
  Omega_Phy_unit.Sm15 := s14
  Omega_Phy_unit.Sm16 := s15
  add_pow1_Omega_Phy := Omega_Phy_unit.add_pow1
  add_pow2_Omega_Phy := Omega_Phy_unit.add_pow2
  add_pow3_Omega_Phy := Omega_Phy_unit.add_pow3
  add_dec1_Omega_Phy := Omega_Phy_unit.add_dec1
  Omega_Phy_unit.pow1 := pow1_Omega_Phy
  Omega_Phy_unit.pow2 := pow2_Omega_Phy
  Omega_Phy_unit.pow3 := pow3_Omega_Phy
  Omega_Phy_unit.dec1 := dec1_Omega_Phy
  Omega_Phy_unit.L_ready := L_ready
  Omega_Phy_unit.L1 := L1
  Omega_Phy_unit.L2 := L2
  Omega_Phy_unit.L3 := L3
  Omega_Phy_unit.L4 := L4
  Omega_Phy_unit.L5 := L5
  Omega_Phy_unit.L6 := L6
  Omega_Phy_unit.L7 := L7
  Omega_Phy_unit.L8 := L8
  poly_ready := Omega_Phy_unit.poly_ready
  O1 := Omega_Phy_unit.O1
  O2 := Omega_Phy_unit.O2
  O3 := Omega_Phy_unit.O3
  O4 := Omega_Phy_unit.O4
  O5 := Omega_Phy_unit.O5
  O6 := Omega_Phy_unit.O6
  O7 := Omega_Phy_unit.O7
  O8 := Omega_Phy_unit.O8
  O9 := Omega_Phy_unit.O9
  O10 := Omega_Phy_unit.O10
  O11 := Omega_Phy_unit.O11
  O12 := Omega_Phy_unit.O12
  O13 := Omega_Phy_unit.O13
  O14 := Omega_Phy_unit.O14
  O15 := Omega_Phy_unit.O15
  O16 := Omega_Phy_unit.O16
  P1 := Omega_Phy_unit.P1
  P3 := Omega_Phy_unit.P3
  P5 := Omega_Phy_unit.P5
  P7 := Omega_Phy_unit.P7

  val RE_error_correction = Wire(Bool()) 
  val WE_error_correction = Wire(Bool()) 
  val Address_error_correction = Wire(UInt(8.W)) 
  val correction_value = Wire(UInt(8.W)) 
  val initial_value = aReg(0.U.asTypeOf(UInt(8.W))) 
  val DONE = Wire(Bool()) 
  val pow1_error_correction = Wire(UInt(8.W)) 
  val pow2_error_correction = Wire(UInt(8.W)) 
  val pow3_error_correction = Wire(UInt(8.W)) 
  val pow4_error_correction = Wire(UInt(8.W)) 
  val dec1_error_correction = Wire(UInt(8.W)) 
  val dec2_error_correction = Wire(UInt(8.W)) 
  val dec3_error_correction = Wire(UInt(8.W)) 
  val dec4_error_correction = Wire(UInt(8.W)) 
  val add_pow1_error_correction = Wire(UInt(8.W)) 
  val add_pow2_error_correction = Wire(UInt(8.W)) 
  val add_pow3_error_correction = Wire(UInt(8.W)) 
  val add_pow4_error_correction = Wire(UInt(8.W)) 
  val add_dec1_error_correction = Wire(UInt(8.W)) 
  val add_dec2_error_correction = Wire(UInt(8.W)) 
  val add_dec3_error_correction = Wire(UInt(8.W)) 
  val add_dec4_error_correction = Wire(UInt(8.W)) 

  val error_correction_unit = Module(new error_correction)
  error_correction_unit.reset := reset
  add_pow1_error_correction := error_correction_unit.add_pow1
  add_pow2_error_correction := error_correction_unit.add_pow2
  add_pow3_error_correction := error_correction_unit.add_pow3
  add_pow4_error_correction := error_correction_unit.add_pow4
  add_dec1_error_correction := error_correction_unit.add_dec1
  add_dec2_error_correction := error_correction_unit.add_dec2
  add_dec3_error_correction := error_correction_unit.add_dec3
  add_dec4_error_correction := error_correction_unit.add_dec4
  error_correction_unit.pow1 := pow1_error_correction
  error_correction_unit.pow2 := pow2_error_correction
  error_correction_unit.pow3 := pow3_error_correction
  error_correction_unit.pow4 := pow4_error_correction
  error_correction_unit.dec1 := dec1_error_correction
  error_correction_unit.dec2 := dec2_error_correction
  error_correction_unit.dec3 := dec3_error_correction
  error_correction_unit.dec4 := dec4_error_correction
  error_correction_unit.roots_ready := roots_ready
  error_correction_unit.root_count := root_cnt
  error_correction_unit.r1 := r1
  error_correction_unit.r2 := r2
  error_correction_unit.r3 := r3
  error_correction_unit.r4 := r4
  error_correction_unit.r5 := r5
  error_correction_unit.r6 := r6
  error_correction_unit.r7 := r7
  error_correction_unit.r8 := r8
  error_correction_unit.poly_ready := poly_ready
  error_correction_unit.O1 := O1
  error_correction_unit.O2 := O2
  error_correction_unit.O3 := O3
  error_correction_unit.O4 := O4
  error_correction_unit.O5 := O5
  error_correction_unit.O6 := O6
  error_correction_unit.O7 := O7
  error_correction_unit.O8 := O8
  error_correction_unit.O9 := O9
  error_correction_unit.O10 := O10
  error_correction_unit.O11 := O11
  error_correction_unit.O12 := O12
  error_correction_unit.O13 := O13
  error_correction_unit.O14 := O14
  error_correction_unit.O15 := O15
  error_correction_unit.O16 := O16
  error_correction_unit.P1 := P1
  error_correction_unit.P3 := P3
  error_correction_unit.P5 := P5
  error_correction_unit.P7 := P7
  RE_error_correction := error_correction_unit.RE
  WE_error_correction := error_correction_unit.WE
  Address_error_correction := error_correction_unit.Address
  correction_value := error_correction_unit.correction_value
  error_correction_unit.initial_value := initial_value
  DONE := error_correction_unit.DONE

  val RE_out_stage = Wire(Bool()) 
  val RdAdd_out_stage = Wire(UInt(8.W)) 
  val In_byte_out_stage = aReg(0.U.asTypeOf(UInt(8.W))) 
  val out_done = Wire(Bool()) 
  val DONE_ext = aReg(false.B) 

  val out_stage_unit = Module(new out_stage(variant.bug3))
  out_stage_unit.reset := reset
  out_stage_unit.DONE := DONE || DONE_ext
  RE_out_stage := out_stage_unit.RE
  RdAdd_out_stage := out_stage_unit.RdAdd
  out_stage_unit.In_byte := In_byte_out_stage
  Out_byte := out_stage_unit.Out_byte
  CEO_0 := out_stage_unit.CEO
  Valid_out := out_stage_unit.Valid_out
  out_done := out_stage_unit.out_done

  val RE1 = aReg(false.B) 
  val WE1 = aReg(false.B) 
  val RE2 = aReg(false.B) 
  val WE2 = aReg(false.B) 
  val R_Add1 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val W_Add1 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val R_Add2 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val W_Add2 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val out_byte1 = Wire(UInt(8.W)) 
  val out_byte2 = Wire(UInt(8.W)) 
  val input_byte1 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val input_byte2 = aReg(0.U.asTypeOf(UInt(8.W))) 

  val mem_out_1 = Module(new DP_RAM(
      num_words = 188,
      address_width = 8,
      data_width = 8
  ))
  mem_out_1.we := WE1
  mem_out_1.re := RE1
  mem_out_1.address_read := R_Add1
  mem_out_1.address_write := W_Add1
  mem_out_1.data_in := input_byte1
  out_byte1 := mem_out_1.data_out


  val mem_out_2 = Module(new DP_RAM(
      num_words = 188,
      address_width = 8,
      data_width = 8
  ))
  mem_out_2.we := WE2
  mem_out_2.re := RE2
  mem_out_2.address_read := R_Add2
  mem_out_2.address_write := W_Add2
  mem_out_2.data_in := input_byte2
  out_byte2 := mem_out_2.data_out

  val pow1 = Wire(UInt(8.W)) 
  val pow2 = Wire(UInt(8.W)) 
  val pow3 = Wire(UInt(8.W)) 
  val pow4 = Wire(UInt(8.W)) 
  val dec1 = Wire(UInt(8.W)) 
  val dec2 = Wire(UInt(8.W)) 
  val dec3 = Wire(UInt(8.W)) 
  val dec4 = Wire(UInt(8.W)) 
  val add_pow1 = Wire(UInt(8.W)) 
  val add_pow2 = Wire(UInt(8.W)) 
  val add_pow3 = Wire(UInt(8.W)) 
  val add_pow4 = Wire(UInt(8.W)) 
  val add_dec1 = Wire(UInt(8.W)) 
  val add_dec2 = Wire(UInt(8.W)) 
  val add_dec3 = Wire(UInt(8.W)) 
  val add_dec4 = Wire(UInt(8.W)) 

  val power_rom_instant1 = Module(new GF_matrix_ascending_binary)
  power_rom_instant1.re := "b1".U(1.W)
  power_rom_instant1.address_read := add_pow1
  pow1 := power_rom_instant1.data_out


  val power_rom_instant2 = Module(new GF_matrix_ascending_binary)
  power_rom_instant2.re := "b1".U(1.W)
  power_rom_instant2.address_read := add_pow2
  pow2 := power_rom_instant2.data_out


  val power_rom_instant3 = Module(new GF_matrix_ascending_binary)
  power_rom_instant3.re := "b1".U(1.W)
  power_rom_instant3.address_read := add_pow3
  pow3 := power_rom_instant3.data_out


  val power_rom_instant4 = Module(new GF_matrix_ascending_binary)
  power_rom_instant4.re := "b1".U(1.W)
  power_rom_instant4.address_read := add_pow4
  pow4 := power_rom_instant4.data_out


  val rom_instant_1 = Module(new GF_matrix_dec)
  rom_instant_1.re := "b1".U(1.W)
  rom_instant_1.address_read := add_dec1
  dec1 := rom_instant_1.data_out


  val rom_instant_2 = Module(new GF_matrix_dec)
  rom_instant_2.re := "b1".U(1.W)
  rom_instant_2.address_read := add_dec2
  dec2 := rom_instant_2.data_out


  val rom_instant_3 = Module(new GF_matrix_dec)
  rom_instant_3.re := "b1".U(1.W)
  rom_instant_3.address_read := add_dec3
  dec3 := rom_instant_3.data_out


  val rom_instant_4 = Module(new GF_matrix_dec)
  rom_instant_4.re := "b1".U(1.W)
  rom_instant_4.address_read := add_dec4
  dec4 := rom_instant_4.data_out

  val S_flag = aReg(false.B) 
  val L_flag = aReg(false.B) 
  val R_flag = aReg(false.B) 
  val T_flag = aReg(false.B) 
  val out_flag = aReg(false.B) 
  val state1: UInt = "b000001".U(6.W)
  val state2: UInt = "b000010".U(6.W)
  val state3: UInt = "b000100".U(6.W)
  val state4: UInt = "b001000".U(6.W)
  val state5: UInt = "b010000".U(6.W)
  val state6: UInt = "b100000".U(6.W)
  val (state, stateNext) = initialAReg(state1)
  when(reset.asBool) {
    S_flag := false.B
    L_flag := false.B
    R_flag := false.B
    T_flag := false.B
    out_flag := false.B
    DONE_ext := false.B
    stateNext := state1
  } .otherwise {
    when(S_ready) {
      T_flag := true.B
    }
    when(transport_done) {
      T_flag := false.B
    }
    when(DONE) {
      out_flag := true.B
    }
    when((out_done && ( !DONE_ext)) && ( !DONE)) {
      out_flag := false.B
    }
    when((DONE && out_flag) && ( !out_done)) {
      DONE_ext := true.B
    }
    when(out_done) {
      DONE_ext := false.B
    }
    when(state === state1) {
      when(S_ready) {
        // buggy_2 suppresses the state1 S_flag assertion.
        S_flag := (!variant.bug2).B
        stateNext := state2
      }
    } .elsewhen (state === state2) {
      when(L_ready) {
        S_flag := false.B
        L_flag := true.B
        stateNext := state3
      }
    } .elsewhen (state === state3) {
      when(roots_ready) {
        L_flag := false.B
        R_flag := true.B
        stateNext := state4
      }
    } .otherwise {
      when(DONE) {
        R_flag := false.B
        stateNext := state1
      }
    }
  }

  val control = Wire(UInt(3.W)) 
  control := Cat(R_flag, L_flag, S_flag)
  add_pow1 := 0.U
  add_pow2 := 0.U
  add_pow3 := 0.U
  add_pow4 := 0.U
  add_dec1 := 0.U
  add_dec2 := 0.U
  add_dec3 := 0.U
  add_dec4 := 0.U
  pow1_BM_lamda := 0.U
  pow2_BM_lamda := 0.U
  dec1_BM_lamda := 0.U
  pow1_lamda_roots := 0.U
  pow1_Omega_Phy := 0.U
  pow2_Omega_Phy := 0.U
  pow3_Omega_Phy := 0.U
  dec1_lamda_roots := 0.U
  dec2_lamda_roots := 0.U
  dec3_lamda_roots := 0.U
  dec1_Omega_Phy := 0.U
  pow1_error_correction := 0.U
  pow2_error_correction := 0.U
  pow3_error_correction := 0.U
  pow4_error_correction := 0.U
  dec1_error_correction := 0.U
  dec2_error_correction := 0.U
  dec3_error_correction := 0.U
  dec4_error_correction := 0.U
  when(control === "b001".U(3.W)) {
    add_pow1 := add_pow1_BM_lamda
    add_pow2 := add_pow2_BM_lamda
    add_dec1 := add_dec1_BM_lamda
    pow1_BM_lamda := pow1
    pow2_BM_lamda := pow2
    dec1_BM_lamda := dec1
  } .elsewhen (control === "b010".U(3.W)) {
    add_pow1 := add_pow1_lamda_roots
    add_pow2 := add_pow1_Omega_Phy
    add_pow3 := add_pow2_Omega_Phy
    add_pow4 := add_pow3_Omega_Phy
    add_dec1 := add_dec1_lamda_roots
    add_dec2 := add_dec2_lamda_roots
    add_dec3 := add_dec3_lamda_roots
    add_dec4 := add_dec1_Omega_Phy
    pow1_lamda_roots := pow1
    pow1_Omega_Phy := pow2
    pow2_Omega_Phy := pow3
    pow3_Omega_Phy := pow4
    dec1_lamda_roots := dec1
    dec2_lamda_roots := dec2
    dec3_lamda_roots := dec3
    dec1_Omega_Phy := dec4
  } .elsewhen (control === "b100".U(3.W)) {
    add_pow1 := add_pow1_error_correction
    add_pow2 := add_pow2_error_correction
    add_pow3 := add_pow3_error_correction
    add_pow4 := add_pow4_error_correction
    add_dec1 := add_dec1_error_correction
    add_dec2 := add_dec2_error_correction
    add_dec3 := add_dec3_error_correction
    add_dec4 := add_dec4_error_correction
    pow1_error_correction := pow1
    pow2_error_correction := pow2
    pow3_error_correction := pow3
    pow4_error_correction := pow4
    dec1_error_correction := dec1
    dec2_error_correction := dec2
    dec3_error_correction := dec3
    dec4_error_correction := dec4
  }
  when(reset.asBool) {
    WE1 := false.B
    WE2 := false.B
    W_Add1 := 0.U
    W_Add2 := 0.U
    input_byte1 := 0.U
    input_byte2 := 0.U
  } .otherwise {
    when(T_flag) {
      when(WE_transport) {
        WE1 := true.B
        WE2 := false.B
        W_Add1 := WrAdd_transport
        W_Add2 := 0.U
        input_byte1 := In_mem_Read_byte
        input_byte2 := 0.U
      } .otherwise {
        WE2 := true.B
        WE1 := false.B
        W_Add2 := WrAdd_transport
        W_Add1 := 0.U
        input_byte2 := In_mem_Read_byte
        input_byte1 := 0.U
      }
    } .otherwise {
      when(WE_transport) {
        WE1 := WE_error_correction
        WE2 := false.B
        W_Add1 := Address_error_correction
        W_Add2 := 0.U
        input_byte1 := correction_value
        input_byte2 := 0.U
      } .otherwise {
        WE2 := WE_error_correction
        WE1 := false.B
        W_Add2 := Address_error_correction
        W_Add1 := 0.U
        input_byte2 := correction_value
        input_byte1 := 0.U
      }
    }
  }
  when(reset.asBool) {
    RE1 := false.B
    RE2 := false.B
    R_Add1 := 0.U
    R_Add2 := 0.U
    initial_value := 0.U
    In_byte_out_stage := 0.U
  } .otherwise {
    when(R_flag) {
      when(WE_transport) {
        RE1 := RE_error_correction
        R_Add1 := Address_error_correction
        initial_value := out_byte1
      } .otherwise {
        RE2 := RE_error_correction
        R_Add2 := Address_error_correction
        initial_value := out_byte2
      }
    }
    when(out_flag) {
      when(RE_out_stage) {
        RE1 := true.B
        R_Add1 := RdAdd_out_stage
        In_byte_out_stage := out_byte1
      } .otherwise {
        RE2 := true.B
        R_Add2 := RdAdd_out_stage
        In_byte_out_stage := out_byte2
      }
    }
  }


}
