package reeds

import chisel3._


class error_correction() extends Module with AsyncRegs {
  val poly_ready = IO(Input(Bool()))
  val O1 = IO(Input(UInt(8.W)))
  val O2 = IO(Input(UInt(8.W)))
  val O3 = IO(Input(UInt(8.W)))
  val O4 = IO(Input(UInt(8.W)))
  val O5 = IO(Input(UInt(8.W)))
  val O6 = IO(Input(UInt(8.W)))
  val O7 = IO(Input(UInt(8.W)))
  val O8 = IO(Input(UInt(8.W)))
  val O9 = IO(Input(UInt(8.W)))
  val O10 = IO(Input(UInt(8.W)))
  val O11 = IO(Input(UInt(8.W)))
  val O12 = IO(Input(UInt(8.W)))
  val O13 = IO(Input(UInt(8.W)))
  val O14 = IO(Input(UInt(8.W)))
  val O15 = IO(Input(UInt(8.W)))
  val O16 = IO(Input(UInt(8.W)))
  val P1 = IO(Input(UInt(8.W)))
  val P3 = IO(Input(UInt(8.W)))
  val P5 = IO(Input(UInt(8.W)))
  val P7 = IO(Input(UInt(8.W)))
  val roots_ready = IO(Input(Bool()))
  val root_count = IO(Input(UInt(4.W)))
  val r1 = IO(Input(UInt(8.W)))
  val r2 = IO(Input(UInt(8.W)))
  val r3 = IO(Input(UInt(8.W)))
  val r4 = IO(Input(UInt(8.W)))
  val r5 = IO(Input(UInt(8.W)))
  val r6 = IO(Input(UInt(8.W)))
  val r7 = IO(Input(UInt(8.W)))
  val r8 = IO(Input(UInt(8.W)))
  val pow1 = IO(Input(UInt(8.W)))
  val pow2 = IO(Input(UInt(8.W)))
  val pow3 = IO(Input(UInt(8.W)))
  val pow4 = IO(Input(UInt(8.W)))
  val dec1 = IO(Input(UInt(8.W)))
  val dec2 = IO(Input(UInt(8.W)))
  val dec3 = IO(Input(UInt(8.W)))
  val dec4 = IO(Input(UInt(8.W)))
  val add_pow1 = IO(Output(UInt(8.W)))
  val add_pow2 = IO(Output(UInt(8.W)))
  val add_pow3 = IO(Output(UInt(8.W)))
  val add_pow4 = IO(Output(UInt(8.W)))
  val add_dec1 = IO(Output(UInt(8.W)))
  val add_dec2 = IO(Output(UInt(8.W)))
  val add_dec3 = IO(Output(UInt(8.W)))
  val add_dec4 = IO(Output(UInt(8.W)))
  val RE = IO(Output(Bool()))
  val WE = IO(Output(Bool()))
  val Address = IO(Output(UInt(8.W)))
  val correction_value = IO(Output(UInt(8.W)))
  val initial_value = IO(Input(UInt(8.W)))
  val DONE = IO(Output(Bool()))
  // NOTE: The following statements are auto generated based on existing output reg of the original verilog source
  val add_pow1_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  add_pow1 := add_pow1_out_reg
  val add_pow2_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  add_pow2 := add_pow2_out_reg
  val add_pow3_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  add_pow3 := add_pow3_out_reg
  val add_pow4_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  add_pow4 := add_pow4_out_reg
  val Address_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  Address := Address_out_reg
  val correction_value_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  correction_value := correction_value_out_reg
  val DONE_out_reg = aReg(false.B) 
  DONE := DONE_out_reg

  val WE_0 = aReg(false.B) 
  val RE_0 = aReg(false.B) 
  val r_cnt = aReg(0.U.asTypeOf(UInt(4.W))) 
  val rd = asyncResetByteArray(9, "rd")
  val rp = asyncResetByteArray(9, "rp")
  val O = asyncResetByteArray(17, "O")
  val P = asyncResetByteArray(5, "P")
  val eL = asyncResetByteArray(9, "eL")
  val eV = asyncResetByteArray(9, "eV")
  val V = aReg(0.U.asTypeOf(UInt(8.W))) 
  val Vx2 = aReg(0.U.asTypeOf(UInt(9.W))) 
  val Vx3 = aReg(0.U.asTypeOf(UInt(10.W))) 
  val Vx6 = aReg(0.U.asTypeOf(UInt(11.W))) 
  val Vx7 = aReg(0.U.asTypeOf(UInt(11.W))) 
  val Vx8 = aReg(0.U.asTypeOf(UInt(11.W))) 
  val Vx9 = aReg(0.U.asTypeOf(UInt(12.W))) 
  val add1 = aReg(0.U.asTypeOf(UInt(12.W))) 
  val add2 = aReg(0.U.asTypeOf(UInt(12.W))) 
  val add3 = aReg(0.U.asTypeOf(UInt(12.W))) 
  val add4 = aReg(0.U.asTypeOf(UInt(12.W))) 
  val add_1 = aReg(0.U(9.W))
  val add_2 = aReg(0.U(9.W))
  val add_3 = aReg(0.U(9.W))
  val add_4 = aReg(0.U(9.W))
  val IS_255_1 = aReg(false.B) 
  val IS_255_2 = aReg(false.B) 
  val IS_255_3 = aReg(false.B) 
  val IS_255_4 = aReg(false.B) 
  val IS_255_1_delayed = aReg(false.B) 
  val IS_255_2_delayed = aReg(false.B) 
  val IS_255_3_delayed = aReg(false.B) 
  val IS_255_4_delayed = aReg(false.B) 
  val div1 = aReg(false.B) 
  val OV = aReg(0.U.asTypeOf(UInt(8.W))) 
  val PV = aReg(0.U.asTypeOf(UInt(8.W))) 
  val cnt = aReg(0.U.asTypeOf(UInt(4.W))) 
  val op_cnt = aReg(0.U.asTypeOf(UInt(4.W))) 
  val state1: UInt = "b0000001".U(7.W)
  val state2: UInt = "b0000010".U(7.W)
  val state3: UInt = "b0000100".U(7.W)
  val state4: UInt = "b0001000".U(7.W)
  val state5: UInt = "b0010000".U(7.W)
  val state6: UInt = "b0100000".U(7.W)
  val state7: UInt = "b1000000".U(7.W)
  val (state, stateNext) = initialAReg(state1)
  val in_range = aReg(false.B) 
  RE := RE_0
  WE := Mux(((WE_0 && (Address_out_reg < 188.U)) && in_range), true.B, false.B)
  add_dec1 := Mux((IS_255_1_delayed), "h00".U(8.W), Mux((add_1(7,0).andR && !add_1(8)), "h01".U(8.W), Mux((div1), (add_1(7,0)-add_1(8))+1.U, (add_1(7,0)+add_1(8))+1.U)))
  add_dec2 := Mux((IS_255_2_delayed), "h00".U(8.W), Mux(add_2(7,0).andR, "h01".U(8.W), (add_2(7,0)+add_2(8))+1.U))
  add_dec3 := Mux((IS_255_3_delayed), "h00".U(8.W), Mux(add_3(7,0).andR, "h01".U(8.W), (add_3(7,0)+add_3(8))+1.U))
  add_dec4 := Mux((IS_255_4_delayed), "h00".U(8.W), Mux(add_4(7,0).andR, "h01".U(8.W), (add_4(7,0)+add_4(8))+1.U))
  when(reset.asBool) {
    for(k <- 1 to 16){
      O.write(k, 0.U)
    }
    for(k <- 1 to 8){
      rd.write(k, 0.U)
      rp.write(k, 0.U)
    }
    for(k <- 1 to 4){
      P.write(k, 0.U)
    }
    for(k <- 1 to 8){
      eL.write(k, 0.U)
      eV.write(k, 0.U)
    }
    WE_0 := false.B
    RE_0 := false.B
    r_cnt := 0.U
    V := 0.U
    Vx2 := 0.U
    Vx3 := 0.U
    Vx6 := 0.U
    Vx7 := 0.U
    Vx8 := 0.U
    Vx9 := 0.U
    add1 := 0.U
    add2 := 0.U
    add3 := 0.U
    add4 := 0.U
    add_1 := 0.U
    add_2 := 0.U
    add_3 := 0.U
    add_4 := 0.U
    IS_255_1 := false.B
    IS_255_2 := false.B
    IS_255_3 := false.B
    IS_255_4 := false.B
    IS_255_1_delayed := false.B
    IS_255_2_delayed := false.B
    IS_255_3_delayed := false.B
    IS_255_4_delayed := false.B
    div1 := false.B
    in_range := false.B
    cnt := 0.U
    op_cnt := 0.U
    add_pow1_out_reg := 0.U
    add_pow2_out_reg := 0.U
    add_pow3_out_reg := 0.U
    add_pow4_out_reg := 0.U
    Address_out_reg := 0.U
    correction_value_out_reg := 0.U
    DONE_out_reg := false.B
    OV := 0.U
    PV := 0.U
    stateNext := state1
  } .otherwise {
    when(state === state1) {
      for(k <- 1 to 8){
        eL.write(k, 188.U)
        eV.write(k, 0.U)
      }
      when(poly_ready) {
        O.write(1, O1)
        O.write(2, O2)
        O.write(3, O3)
        O.write(4, O4)
        O.write(5, O5)
        O.write(6, O6)
        O.write(7, O7)
        O.write(8, O8)
        O.write(9, O9)
        O.write(10, O10)
        O.write(11, O11)
        O.write(12, O12)
        O.write(13, O13)
        O.write(14, O14)
        O.write(15, O15)
        O.write(16, O16)
        P.write(1, P1)
        P.write(2, P3)
        P.write(3, P5)
        P.write(4, P7)
      }
      when(roots_ready) {
        r_cnt := root_count
        rd.write(1, r1)
        rd.write(2, r2)
        rd.write(3, r3)
        rd.write(4, r4)
        rd.write(5, r5)
        rd.write(6, r6)
        rd.write(7, r7)
        rd.write(8, r8)
        stateNext := state2
      }
    } .elsewhen (state === state2) {
      when(cnt === 3.U) {
        cnt := 1.U
        stateNext := state3
      } .otherwise {
        cnt := cnt+1.U
      }
      when(cnt === 0.U) {
        add_pow1_out_reg := rd(1)
        add_pow2_out_reg := rd(2)
        add_pow3_out_reg := rd(3)
        add_pow4_out_reg := rd(4)
      } .elsewhen (cnt === 1.U) {
        add_pow1_out_reg := rd(5)
        add_pow2_out_reg := rd(6)
        add_pow3_out_reg := rd(7)
        add_pow4_out_reg := rd(8)
      } .elsewhen (cnt === 2.U) {
        rp.write(1, pow1)
        rp.write(2, pow2)
        rp.write(3, pow3)
        rp.write(4, pow4)
      } .otherwise {
        rp.write(5, pow1)
        rp.write(6, pow2)
        rp.write(7, pow3)
        rp.write(8, pow4)
      }
    } .elsewhen (state === state3) {
      when(cnt === r_cnt) {
        cnt := 0.U
        stateNext := state4
        op_cnt := 0.U
      } .otherwise {
        cnt := cnt+1.U
      }
      eL.write(cnt, Mux((rp(cnt) === 0.U), 0.U, 255.U-rp(cnt)))
      eV.write(cnt, rp(cnt))
    } .elsewhen (state === state4) {
      when(cnt === 0.U) {
        op_cnt := op_cnt+1.U
        cnt := 1.U
        WE_0 := false.B
      } .otherwise {
        when(op_cnt > r_cnt) {
          in_range := false.B
        } .otherwise {
          in_range := true.B
        }
        when(op_cnt === 9.U) {
          DONE_out_reg := true.B
          cnt := 0.U
          stateNext := state7
        } .otherwise {
          stateNext := state5
          Address_out_reg := 203.U-eL(op_cnt)
          RE_0 := true.B
          V := eV(op_cnt)
          Vx2 := eV(op_cnt).pad(9) + eV(op_cnt).pad(9)
          Vx3 := eV(op_cnt).pad(10) + eV(op_cnt).pad(10) + eV(op_cnt).pad(10)
          cnt := 0.U
          div1 := false.B
          OV := O(1)
          PV := P(1)
          correction_value_out_reg := 0.U
        }
      }
    } .elsewhen (state === state5) {
      when(cnt === 7.U) {
        cnt := 0.U
        stateNext := state6
      } .otherwise {
        cnt := cnt+1.U
      }
      RE_0 := false.B
      Vx6 := Vx3.pad(11) + Vx3.pad(11)
      Vx7 := Vx6 + V.pad(11)
      Vx8 := Vx6 + Vx2.pad(11)
      Vx9 := Vx6.pad(12) + Vx3.pad(12)
      IS_255_1_delayed := IS_255_1
      IS_255_2_delayed := IS_255_2
      IS_255_3_delayed := IS_255_3
      IS_255_4_delayed := IS_255_4
      add_1 := add1(11,8).pad(9) + add1(7,0).pad(9)
      add_2 := add2(11,8).pad(9) + add2(7,0).pad(9)
      add_3 := add3(11,8).pad(9) + add3(7,0).pad(9)
      add_4 := add4(11,8).pad(9) + add4(7,0).pad(9)
      when(cnt === 0.U) {
        add1 := O(2).pad(12) + V.pad(12)
        add2 := O(3).pad(12) + Vx2.pad(12)
        add3 := O(4).pad(12) + Vx3.pad(12)
        add4 := O(5).pad(12) + Vx3.pad(12) + V.pad(12)
        IS_255_1 := Mux(((O(2).andR) || (V.andR)), true.B, false.B)
        IS_255_2 := Mux(((O(3).andR) || (V.andR)), true.B, false.B)
        IS_255_3 := Mux(((O(4).andR) || (V.andR)), true.B, false.B)
        IS_255_4 := Mux(((O(5).andR) || (V.andR)), true.B, false.B)
      } .elsewhen (cnt === 1.U) {
        add1 := O(6).pad(12) + Vx2.pad(12) + Vx3.pad(12)
        add2 := O(7).pad(12) + Vx6.pad(12)
        add3 := O(8).pad(12) + Vx6.pad(12) + V.pad(12)
        add4 := O(9).pad(12) + Vx6.pad(12) + Vx2.pad(12)
        IS_255_1 := Mux(((O(6).andR) || (V.andR)), true.B, false.B)
        IS_255_2 := Mux(((O(7).andR) || (V.andR)), true.B, false.B)
        IS_255_3 := Mux(((O(8).andR) || (V.andR)), true.B, false.B)
        IS_255_4 := Mux(((O(9).andR) || (V.andR)), true.B, false.B)
      } .elsewhen (cnt === 2.U) {
        add1 := O(10).pad(12) + Vx9
        add2 := O(11).pad(12) + Vx9 + V.pad(12)
        add3 := O(12).pad(12) + Vx9 + Vx2.pad(12)
        add4 := O(13).pad(12) + Vx9 + Vx3.pad(12)
        IS_255_1 := Mux(((O(10).andR) || (V.andR)), true.B, false.B)
        IS_255_2 := Mux(((O(11).andR) || (V.andR)), true.B, false.B)
        IS_255_3 := Mux(((O(12).andR) || (V.andR)), true.B, false.B)
        IS_255_4 := Mux(((O(13).andR) || (V.andR)), true.B, false.B)
      } .elsewhen (cnt === 3.U) {
        add1 := O(14).pad(12) + Vx6.pad(12) + Vx7.pad(12)
        add2 := O(15).pad(12) + Vx6.pad(12) + Vx8.pad(12)
        add3 := O(16).pad(12) + Vx6.pad(12) + Vx9
        add4 := 0.U
        IS_255_1 := Mux(((O(14).andR) || (V.andR)), true.B, false.B)
        IS_255_2 := Mux(((O(15).andR) || (V.andR)), true.B, false.B)
        IS_255_3 := Mux(((O(16).andR) || (V.andR)), true.B, false.B)
        IS_255_4 := true.B
      } .otherwise {
        add1 := P(2).pad(12) + Vx2.pad(12)
        add2 := P(3).pad(12) + Vx3.pad(12) + V.pad(12)
        add3 := P(4).pad(12) + Vx6.pad(12)
        add4 := 0.U
        IS_255_1 := Mux(((P(2).andR) || (V.andR)), true.B, false.B)
        IS_255_2 := Mux(((P(3).andR) || (V.andR)), true.B, false.B)
        IS_255_3 := Mux(((P(4).andR) || (V.andR)), true.B, false.B)
        IS_255_4 := true.B
      }
      when((cnt > 2.U) && (cnt < 7.U)) {
        OV := (((OV^dec1)^dec2)^dec3)^dec4
      }
      when(cnt === 7.U) {
        PV := (((PV^dec1)^dec2)^dec3)^dec4
      }
    } .elsewhen (state === state6) {
      when(cnt === 4.U) {
        cnt := 0.U
        WE_0 := true.B
        stateNext := state4
      } .otherwise {
        cnt := cnt+1.U
      }
      div1 := true.B
      add_pow1_out_reg := OV
      add_pow2_out_reg := PV
      add_1 := pow1.pad(9) - pow2.pad(9)
      IS_255_1_delayed := Mux(((pow1.andR) || (pow2.andR)), true.B, false.B)
      correction_value_out_reg := initial_value^dec1
    } .otherwise {
      stateNext := state1
      DONE_out_reg := false.B
      cnt := 0.U
    }
  }


}
