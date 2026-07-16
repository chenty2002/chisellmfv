package reeds

import chisel3._


class GF_mult_add_syndromes() extends Module with AsyncRegs {
  val CE = IO(Input(Bool()))
  val ip1 = IO(Input(UInt(8.W)))
  val ip2 = IO(Input(UInt(8.W)))
  val count_in = IO(Input(UInt(3.W)))
  val S_Ready = IO(Output(Bool()))
  val S = IO(Output(UInt(8.W)))
  // NOTE: The following statements are auto generated based on existing output reg of the original verilog source
  val S_Ready_out_reg = aReg(false.B) 
  S_Ready := S_Ready_out_reg
  val S_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  S := S_out_reg

  val add_inputs = aReg(0.U.asTypeOf(UInt(9.W))) 
  val out_GF_mult_0 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val address_GF_dec = aReg(0.U.asTypeOf(UInt(8.W))) 
  val out_GF_dec = Wire(UInt(8.W)) 
  val xor_reg0 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val xor_reg1 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val xor_reg2 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val xor_reg3 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val xor_reg4 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val xor_reg5 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val xor_reg6 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val xor_reg7 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val cnt203_0 = aReg(204.U(8.W)) 
  val cnt203_1 = aReg(204.U(8.W)) 
  val cnt203_2 = aReg(204.U(8.W)) 
  val cnt203_3 = aReg(204.U(8.W)) 
  val cnt203_4 = aReg(204.U(8.W)) 
  val cnt203_5 = aReg(204.U(8.W)) 
  val cnt203_6 = aReg(204.U(8.W)) 
  val cnt203_7 = aReg(204.U(8.W)) 
  val CE1 = aReg(true.B) 

  val rom_instant = Module(new GF_matrix_dec)
  rom_instant.re := CE || CE1
  rom_instant.address_read := address_GF_dec
  out_GF_dec := rom_instant.data_out

  val ip1_0 = aReg(255.U(8.W)) 
  val count_in0 = aReg(0.U.asTypeOf(UInt(3.W))) 
  val count_in1 = aReg(0.U.asTypeOf(UInt(3.W))) 
  val count_in2 = aReg(0.U.asTypeOf(UInt(3.W))) 
  val count_in3 = aReg(0.U.asTypeOf(UInt(3.W))) 
  val F = aReg(true.B) 
  when(reset.asBool) {
    add_inputs := 0.U
    out_GF_mult_0 := 0.U
    address_GF_dec := 0.U
    xor_reg0 := 0.U
    xor_reg1 := 0.U
    xor_reg2 := 0.U
    xor_reg3 := 0.U
    xor_reg4 := 0.U
    xor_reg5 := 0.U
    xor_reg6 := 0.U
    xor_reg7 := 0.U
    cnt203_0 := 204.U
    cnt203_1 := 204.U
    cnt203_2 := 204.U
    cnt203_3 := 204.U
    cnt203_4 := 204.U
    cnt203_5 := 204.U
    cnt203_6 := 204.U
    cnt203_7 := 204.U
    S_Ready_out_reg := false.B
    S_out_reg := 0.U
    F := true.B
    ip1_0 := "hFF".U(8.W)
    count_in0 := 0.U
    count_in1 := 0.U
    count_in2 := 0.U
    count_in3 := 0.U
    CE1 := true.B
  } .otherwise {
    when(CE) {
      CE1 := false.B
      ip1_0 := ip1
      count_in0 := count_in
      add_inputs := ip1.pad(9) + ip2.pad(9)
      out_GF_mult_0 := add_inputs(7,0)+add_inputs(8)
      count_in1 := count_in0
      when(ip1_0.andR) {
        F := true.B
      } .otherwise {
        F := false.B
      }
      count_in2 := count_in1
      address_GF_dec := Mux((F), "h00".U(8.W), Mux((out_GF_mult_0.andR), "h01".U(8.W), out_GF_mult_0+1.U))
      count_in3 := count_in2
      when(count_in3 === 4.U) {
        xor_reg4 := xor_reg4^out_GF_dec
      } .elsewhen (count_in3 === 5.U) {
        xor_reg5 := xor_reg5^out_GF_dec
      } .elsewhen (count_in3 === 6.U) {
        xor_reg6 := xor_reg6^out_GF_dec
      } .elsewhen (count_in3 === 7.U) {
        xor_reg7 := xor_reg7^out_GF_dec
      } .elsewhen (count_in3 === 0.U) {
        xor_reg0 := xor_reg0^out_GF_dec
      } .elsewhen (count_in3 === 1.U) {
        xor_reg1 := xor_reg1^out_GF_dec
      } .elsewhen (count_in3 === 2.U) {
        xor_reg2 := xor_reg2^out_GF_dec
      } .otherwise {
        xor_reg3 := xor_reg3^out_GF_dec
      }
      when(S_Ready_out_reg) {
        S_Ready_out_reg := false.B
      }
      when(count_in === 0.U) {
        when(cnt203_0 === 0.U) {
          S_Ready_out_reg := true.B
          cnt203_0 := 203.U
          S_out_reg := xor_reg0
          xor_reg0 := 0.U
        } .otherwise {
          cnt203_0 := cnt203_0-1.U
        }
      } .elsewhen (count_in === 1.U) {
        when(cnt203_1 === 0.U) {
          cnt203_1 := 203.U
          S_out_reg := xor_reg1
          xor_reg1 := 0.U
        } .otherwise {
          cnt203_1 := cnt203_1-1.U
        }
      } .elsewhen (count_in === 2.U) {
        when(cnt203_2 === 0.U) {
          cnt203_2 := 203.U
          S_out_reg := xor_reg2
          xor_reg2 := 0.U
        } .otherwise {
          cnt203_2 := cnt203_2-1.U
        }
      } .elsewhen (count_in === 3.U) {
        when(cnt203_3 === 0.U) {
          cnt203_3 := 203.U
          S_out_reg := xor_reg3
          xor_reg3 := 0.U
        } .otherwise {
          cnt203_3 := cnt203_3-1.U
        }
      } .elsewhen (count_in === 4.U) {
        when(cnt203_4 === 0.U) {
          cnt203_4 := 203.U
          S_out_reg := xor_reg4
          xor_reg4 := 0.U
        } .otherwise {
          cnt203_4 := cnt203_4-1.U
        }
      } .elsewhen (count_in === 5.U) {
        when(cnt203_5 === 0.U) {
          cnt203_5 := 203.U
          S_out_reg := xor_reg5
          xor_reg5 := 0.U
        } .otherwise {
          cnt203_5 := cnt203_5-1.U
        }
      } .elsewhen (count_in === 6.U) {
        when(cnt203_6 === 0.U) {
          cnt203_6 := 203.U
          S_out_reg := xor_reg6
          xor_reg6 := 0.U
        } .otherwise {
          cnt203_6 := cnt203_6-1.U
        }
      } .otherwise {
        when(cnt203_7 === 0.U) {
          cnt203_7 := 203.U
          S_out_reg := xor_reg7
          xor_reg7 := 0.U
        } .otherwise {
          cnt203_7 := cnt203_7-1.U
        }
      }
    }
  }


}
