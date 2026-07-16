package reeds

import chisel3._


class input_syndromes() extends Module with AsyncRegs {
  val CE = IO(Input(Bool()))
  val input_byte = IO(Input(UInt(8.W)))
  val R_Add = IO(Input(UInt(8.W)))
  val RE = IO(Input(Bool()))
  val S_Ready = IO(Output(Bool()))
  val s0 = IO(Output(UInt(8.W)))
  val s1 = IO(Output(UInt(8.W)))
  val s2 = IO(Output(UInt(8.W)))
  val s3 = IO(Output(UInt(8.W)))
  val s4 = IO(Output(UInt(8.W)))
  val s5 = IO(Output(UInt(8.W)))
  val s6 = IO(Output(UInt(8.W)))
  val s7 = IO(Output(UInt(8.W)))
  val s8 = IO(Output(UInt(8.W)))
  val s9 = IO(Output(UInt(8.W)))
  val s10 = IO(Output(UInt(8.W)))
  val s11 = IO(Output(UInt(8.W)))
  val s12 = IO(Output(UInt(8.W)))
  val s13 = IO(Output(UInt(8.W)))
  val s14 = IO(Output(UInt(8.W)))
  val s15 = IO(Output(UInt(8.W)))
  val Read_byte = IO(Output(UInt(8.W)))
  // NOTE: The following statements are auto generated based on existing output reg of the original verilog source
  val S_Ready_out_reg = aReg(false.B) 
  S_Ready := S_Ready_out_reg
  val s0_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  s0 := s0_out_reg
  val s1_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  s1 := s1_out_reg
  val s2_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  s2 := s2_out_reg
  val s3_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  s3 := s3_out_reg
  val s4_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  s4 := s4_out_reg
  val s5_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  s5 := s5_out_reg
  val s6_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  s6 := s6_out_reg
  val s7_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  s7 := s7_out_reg
  val s8_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  s8 := s8_out_reg
  val s9_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  s9 := s9_out_reg
  val s10_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  s10 := s10_out_reg
  val s11_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  s11 := s11_out_reg
  val s12_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  s12 := s12_out_reg
  val s13_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  s13 := s13_out_reg
  val s14_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  s14 := s14_out_reg
  val s15_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  s15 := s15_out_reg

  val WE = aReg(true.B) 
  val input_byte0 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val W_Add = aReg(204.U(8.W)) 
  val out_byte_0 = Wire(UInt(8.W)) 
  val out_byte_1 = Wire(UInt(8.W)) 
  Read_byte := Mux((RE), out_byte_0, out_byte_1)

  val mem_in_0 = Module(new DP_RAM(
      num_words = 205,
      address_width = 8,
      data_width = 8
  ))
  mem_in_0.we := WE
  mem_in_0.re := RE
  mem_in_0.address_read := R_Add
  mem_in_0.address_write := W_Add
  mem_in_0.data_in := input_byte0
  out_byte_0 := mem_in_0.data_out


  val mem_in_1 = Module(new DP_RAM(
      num_words = 205,
      address_width = 8,
      data_width = 8
  ))
  mem_in_1.we :=  !WE
  mem_in_1.re :=  !RE
  mem_in_1.address_read := R_Add
  mem_in_1.address_write := W_Add
  mem_in_1.data_in := input_byte0
  out_byte_1 := mem_in_1.data_out

  val CE0 = aReg(false.B) 
  val CE1 = aReg(false.B) 
  val Address_GF_ascending = aReg(0.U.asTypeOf(UInt(8.W))) 
  val out_GF_ascending = Wire(UInt(8.W)) 
  when(reset.asBool) {
    WE := true.B
    W_Add := 204.U
    input_byte0 := 0.U
    CE0 := false.B
    CE1 := false.B
    Address_GF_ascending := 0.U
  } .otherwise {
    CE0 := CE
    CE1 := CE0
    when(CE) {
      input_byte0 := input_byte
      Address_GF_ascending := input_byte
      when(W_Add === 0.U) {
        WE :=  ~WE
        W_Add := 203.U
      } .otherwise {
        W_Add := W_Add-1.U
      }
    }
  }


  val rom_instant = Module(new GF_matrix_ascending_binary)
  rom_instant.re := "b1".U(1.W)
  rom_instant.address_read := Address_GF_ascending
  out_GF_ascending := rom_instant.data_out

  val x_power_0 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val x1 = aReg(0.U.asTypeOf(UInt(9.W))) 
  val x_power_1 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val x2 = aReg(0.U.asTypeOf(UInt(9.W))) 
  val x_power_2 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val x3 = aReg(0.U.asTypeOf(UInt(9.W))) 
  val x_power_3 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val x4 = aReg(0.U.asTypeOf(UInt(9.W))) 
  val x_power_4 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val x5 = aReg(0.U.asTypeOf(UInt(9.W))) 
  val x_power_5 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val x6 = aReg(0.U.asTypeOf(UInt(9.W))) 
  val x_power_6 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val x7 = aReg(0.U.asTypeOf(UInt(9.W))) 
  val x_power_7 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val x8 = aReg(0.U.asTypeOf(UInt(9.W))) 
  val x_power_8 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val x9 = aReg(0.U.asTypeOf(UInt(9.W))) 
  val x_power_9 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val x10 = aReg(0.U.asTypeOf(UInt(9.W))) 
  val x_power_10 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val x11 = aReg(0.U.asTypeOf(UInt(9.W))) 
  val x_power_11 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val x12 = aReg(0.U.asTypeOf(UInt(9.W))) 
  val x_power_12 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val x13 = aReg(0.U.asTypeOf(UInt(9.W))) 
  val x_power_13 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val x14 = aReg(0.U.asTypeOf(UInt(9.W))) 
  val x_power_14 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val x15 = aReg(0.U.asTypeOf(UInt(9.W))) 
  val x_power_15 = aReg(0.U.asTypeOf(UInt(8.W))) 
  when(reset.asBool) {
    x_power_0 := 0.U
    x1 := 0.U
    x_power_1 := 0.U
    x2 := 0.U
    x_power_2 := 0.U
    x3 := 0.U
    x_power_3 := 0.U
    x4 := 0.U
    x_power_4 := 0.U
    x5 := 0.U
    x_power_5 := 0.U
    x6 := 0.U
    x_power_6 := 0.U
    x7 := 0.U
    x_power_7 := 0.U
    x8 := 0.U
    x_power_8 := 0.U
    x9 := 0.U
    x_power_9 := 0.U
    x10 := 0.U
    x_power_10 := 0.U
    x11 := 0.U
    x_power_11 := 0.U
    x12 := 0.U
    x_power_12 := 0.U
    x13 := 0.U
    x_power_13 := 0.U
    x14 := 0.U
    x_power_14 := 0.U
    x15 := 0.U
    x_power_15 := 0.U
  } .otherwise {
    when(CE) {
      when(x_power_0 === 0.U) {
        x_power_0 := 203.U
        x1 := 151.U
        x2 := 99.U
        x3 := 47.U
        x4 := 250.U
        x5 := 198.U
        x6 := 146.U
        x7 := 94.U
        x8 := 42.U
        x9 := 245.U
        x10 := 193.U
        x11 := 141.U
        x12 := 89.U
        x13 := 37.U
        x14 := 240.U
        x15 := 188.U
      } .otherwise {
        x_power_0 := x_power_0-1.U
        x1 := x_power_1.pad(9) - 2.U(9.W)
        x2 := x_power_2.pad(9) - 3.U(9.W)
        x3 := x_power_3.pad(9) - 4.U(9.W)
        x4 := x_power_4.pad(9) - 5.U(9.W)
        x5 := x_power_5.pad(9) - 6.U(9.W)
        x6 := x_power_6.pad(9) - 7.U(9.W)
        x7 := x_power_7.pad(9) - 8.U(9.W)
        x8 := x_power_8.pad(9) - 9.U(9.W)
        x9 := x_power_9.pad(9) - 10.U(9.W)
        x10 := x_power_10.pad(9) - 11.U(9.W)
        x11 := x_power_11.pad(9) - 12.U(9.W)
        x12 := x_power_12.pad(9) - 13.U(9.W)
        x13 := x_power_13.pad(9) - 14.U(9.W)
        x14 := x_power_14.pad(9) - 15.U(9.W)
        x15 := x_power_15.pad(9) - 16.U(9.W)
      }
    }
    x_power_1 := x1(7,0)-x1(8)
    x_power_2 := x2(7,0)-x2(8)
    x_power_3 := x3(7,0)-x3(8)
    x_power_4 := x4(7,0)-x4(8)
    x_power_5 := x5(7,0)-x5(8)
    x_power_6 := x6(7,0)-x6(8)
    x_power_7 := x7(7,0)-x7(8)
    x_power_8 := x8(7,0)-x8(8)
    x_power_9 := x9(7,0)-x9(8)
    x_power_10 := x10(7,0)-x10(8)
    x_power_11 := x11(7,0)-x11(8)
    x_power_12 := x12(7,0)-x12(8)
    x_power_13 := x13(7,0)-x13(8)
    x_power_14 := x14(7,0)-x14(8)
    x_power_15 := x15(7,0)-x15(8)
  }

  val x_power0 = Wire(UInt(8.W)) 
  val x_power1 = Wire(UInt(8.W)) 
  val x_power2 = Wire(UInt(8.W)) 
  val x_power3 = Wire(UInt(8.W)) 
  val x_power4 = Wire(UInt(8.W)) 
  val x_power5 = Wire(UInt(8.W)) 
  val x_power6 = Wire(UInt(8.W)) 
  val x_power7 = Wire(UInt(8.W)) 
  val x_power8 = Wire(UInt(8.W)) 
  val x_power9 = Wire(UInt(8.W)) 
  val x_power10 = Wire(UInt(8.W)) 
  val x_power11 = Wire(UInt(8.W)) 
  val x_power12 = Wire(UInt(8.W)) 
  val x_power13 = Wire(UInt(8.W)) 
  val x_power14 = Wire(UInt(8.W)) 
  val x_power15 = Wire(UInt(8.W)) 
  x_power0 := x_power_0
  x_power1 := x_power_1
  x_power2 := Mux((x_power_2.andR), "h00".U(8.W), x_power_2)
  x_power3 := x_power_3
  x_power4 := Mux((x_power_4.andR), "h00".U(8.W), x_power_4)
  x_power5 := Mux((x_power_5.andR), "h00".U(8.W), x_power_5)
  x_power6 := x_power_6
  x_power7 := x_power_7
  x_power8 := Mux((x_power_8.andR), "h00".U(8.W), x_power_8)
  x_power9 := Mux((x_power_9.andR), "h00".U(8.W), x_power_9)
  x_power10 := x_power_10
  x_power11 := Mux((x_power_11.andR), "h00".U(8.W), x_power_11)
  x_power12 := x_power_12
  x_power13 := x_power_13
  x_power14 := Mux((x_power_14.andR), "h00".U(8.W), x_power_14)
  x_power15 := x_power_15
  val CE_GF_mult_add = aReg(false.B) 
  val ip1_0 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val ip2_0 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val ip1_1 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val ip2_1 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val count_in = aReg(7.U(3.W)) 
  val S_Ready_0 = Wire(Bool()) 
  val s_unit0 = Wire(UInt(8.W)) 
  val s_unit1 = Wire(UInt(8.W)) 

  val unit0 = Module(new GF_mult_add_syndromes)
  unit0.reset := reset
  unit0.CE := CE_GF_mult_add
  unit0.ip1 := ip1_0
  unit0.ip2 := ip2_0
  unit0.count_in := count_in
  S_Ready_0 := unit0.S_Ready
  s_unit0 := unit0.S


  val unit1 = Module(new GF_mult_add_syndromes)
  unit1.reset := reset
  unit1.CE := CE_GF_mult_add
  unit1.ip1 := ip1_1
  unit1.ip2 := ip2_1
  unit1.count_in := count_in
  s_unit1 := unit1.S
  when(reset.asBool) {
    CE_GF_mult_add := false.B
    count_in := 7.U
    ip1_0 := 0.U
    ip2_0 := 0.U
    ip1_1 := 0.U
    ip2_1 := 0.U
  } .otherwise {
    when(CE1) {
      CE_GF_mult_add := true.B
      count_in := 0.U
      ip1_0 := out_GF_ascending
      ip1_1 := out_GF_ascending
    }
    when((count_in.andR) && ( !CE1)) {
      count_in := 7.U(3.W)
      CE_GF_mult_add := false.B
    } .otherwise {
      count_in := count_in+1.U
    }
    when(count_in === 0.U) {
      ip2_0 := x_power2
      ip2_1 := x_power3
    } .elsewhen (count_in === 1.U) {
      ip2_0 := x_power4
      ip2_1 := x_power5
    } .elsewhen (count_in === 2.U) {
      ip2_0 := x_power6
      ip2_1 := x_power7
    } .elsewhen (count_in === 3.U) {
      ip2_0 := x_power8
      ip2_1 := x_power9
    } .elsewhen (count_in === 4.U) {
      ip2_0 := x_power10
      ip2_1 := x_power11
    } .elsewhen (count_in === 5.U) {
      ip2_0 := x_power12
      ip2_1 := x_power13
    } .elsewhen (count_in === 6.U) {
      ip2_0 := x_power14
      ip2_1 := x_power15
    } .otherwise {
      ip2_0 := x_power0
      ip2_1 := x_power1
    }
  }

  val cnt8 = aReg(7.U(3.W)) 
  when(reset.asBool) {
    cnt8 := 7.U
    S_Ready_out_reg := false.B
    s0_out_reg := 0.U
    s1_out_reg := 0.U
    s2_out_reg := 0.U
    s3_out_reg := 0.U
    s4_out_reg := 0.U
    s5_out_reg := 0.U
    s6_out_reg := 0.U
    s7_out_reg := 0.U
    s8_out_reg := 0.U
    s9_out_reg := 0.U
    s10_out_reg := 0.U
    s11_out_reg := 0.U
    s12_out_reg := 0.U
    s13_out_reg := 0.U
    s14_out_reg := 0.U
    s15_out_reg := 0.U
  } .otherwise {
    when(S_Ready_0) {
      cnt8 := 0.U
    }
    when((cnt8.andR) && ( !S_Ready_0)) {
      cnt8 := 7.U(3.W)
    } .otherwise {
      cnt8 := cnt8+1.U
    }
    when(cnt8 === 0.U) {
      s2_out_reg := s_unit0
      s3_out_reg := s_unit1
    } .elsewhen (cnt8 === 1.U) {
      s4_out_reg := s_unit0
      s5_out_reg := s_unit1
    } .elsewhen (cnt8 === 2.U) {
      s6_out_reg := s_unit0
      s7_out_reg := s_unit1
    } .elsewhen (cnt8 === 3.U) {
      s8_out_reg := s_unit0
      s9_out_reg := s_unit1
    } .elsewhen (cnt8 === 4.U) {
      s10_out_reg := s_unit0
      s11_out_reg := s_unit1
    } .elsewhen (cnt8 === 5.U) {
      s12_out_reg := s_unit0
      s13_out_reg := s_unit1
    } .elsewhen (cnt8 === 6.U) {
      s14_out_reg := s_unit0
      s15_out_reg := s_unit1
      S_Ready_out_reg := true.B
    } .otherwise {
      s0_out_reg := s_unit0
      s1_out_reg := s_unit1
    }
    when(S_Ready_out_reg) {
      S_Ready_out_reg := false.B
    }
  }


}
