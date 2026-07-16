package reeds

import chisel3._


class lamda_roots() extends Module with AsyncRegs {
  val CE = IO(Input(Bool()))
  val Lc0 = IO(Input(UInt(8.W)))
  val Lc1 = IO(Input(UInt(8.W)))
  val Lc2 = IO(Input(UInt(8.W)))
  val Lc3 = IO(Input(UInt(8.W)))
  val Lc4 = IO(Input(UInt(8.W)))
  val Lc5 = IO(Input(UInt(8.W)))
  val Lc6 = IO(Input(UInt(8.W)))
  val Lc7 = IO(Input(UInt(8.W)))
  val Lc8 = IO(Input(UInt(8.W)))
  val add_GF_ascending = IO(Output(UInt(8.W)))
  val add_GF_dec0 = IO(Output(UInt(8.W)))
  val add_GF_dec1 = IO(Output(UInt(8.W)))
  val add_GF_dec2 = IO(Output(UInt(8.W)))
  val power = IO(Input(UInt(8.W)))
  val decimal0 = IO(Input(UInt(8.W)))
  val decimal1 = IO(Input(UInt(8.W)))
  val decimal2 = IO(Input(UInt(8.W)))
  val CEO = IO(Output(Bool()))
  val root_cnt = IO(Output(UInt(4.W)))
  val r1 = IO(Output(UInt(8.W)))
  val r2 = IO(Output(UInt(8.W)))
  val r3 = IO(Output(UInt(8.W)))
  val r4 = IO(Output(UInt(8.W)))
  val r5 = IO(Output(UInt(8.W)))
  val r6 = IO(Output(UInt(8.W)))
  val r7 = IO(Output(UInt(8.W)))
  val r8 = IO(Output(UInt(8.W)))
  // NOTE: The following statements are auto generated based on existing output reg of the original verilog source
  val add_GF_ascending_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  add_GF_ascending := add_GF_ascending_out_reg
  val add_GF_dec0_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  add_GF_dec0 := add_GF_dec0_out_reg
  val add_GF_dec1_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  add_GF_dec1 := add_GF_dec1_out_reg
  val add_GF_dec2_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  add_GF_dec2 := add_GF_dec2_out_reg
  val CEO_out_reg = aReg(false.B) 
  CEO := CEO_out_reg
  val root_cnt_out_reg = aReg(0.U.asTypeOf(UInt(4.W))) 
  root_cnt := root_cnt_out_reg
  val r1_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  r1 := r1_out_reg
  val r2_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  r2 := r2_out_reg
  val r3_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  r3 := r3_out_reg
  val r4_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  r4 := r4_out_reg
  val r5_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  r5 := r5_out_reg
  val r6_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  r6 := r6_out_reg
  val r7_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  r7 := r7_out_reg
  val r8_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  r8 := r8_out_reg

  val one = aReg(false.B) 
  val two = aReg(false.B) 
  val V = aReg(255.U(8.W)) 
  val Vp = aReg(0.U.asTypeOf(UInt(8.W))) 
  val cnt9 = aReg(0.U.asTypeOf(UInt(4.W))) 
  val cnt3 = aReg(2.U(2.W)) 
  val add0 = aReg(0.U.asTypeOf(UInt(12.W))) 
  val add1 = aReg(0.U.asTypeOf(UInt(12.W))) 
  val add2 = aReg(0.U.asTypeOf(UInt(12.W))) 
  val Vx2 = aReg(0.U.asTypeOf(UInt(9.W))) 
  val Vx3 = aReg(0.U.asTypeOf(UInt(10.W))) 
  val Vx6 = aReg(0.U.asTypeOf(UInt(11.W))) 
  val X0 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val X1 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val X2 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val X3 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val GF1 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val GF2 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val GF3 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val GF4 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val GF5 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val GF6 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val GF7 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val GF8 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val add_GF_dec0_reg = aReg(0.U.asTypeOf(UInt(9.W))) 
  val add_GF_dec1_reg = aReg(0.U.asTypeOf(UInt(9.W))) 
  val add_GF_dec2_reg = aReg(0.U.asTypeOf(UInt(9.W))) 
  val add_GF_dec0_reg0 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val add_GF_dec1_reg0 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val add_GF_dec2_reg0 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val F0 = aReg(false.B) 
  val F1 = aReg(false.B) 
  val F2 = aReg(false.B) 
  val FF0 = aReg(false.B) 
  val FF1 = aReg(false.B) 
  val FF2 = aReg(false.B) 
  val FFF0 = aReg(false.B) 
  val FFF1 = aReg(false.B) 
  val FFF2 = aReg(false.B) 
  val L0 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val L1 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val L2 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val L3 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val L4 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val L5 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val L6 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val L7 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val L8 = aReg(0.U.asTypeOf(UInt(8.W))) 
  val yes = aReg(false.B) 
  val E = aReg(false.B) 
  val chk_flag = aReg(false.B) 
  val chk_cnt = aReg(0.U.asTypeOf(UInt(1.W))) 
  when(reset.asBool) {
    cnt3 := 2.U
    V := 255.U(8.W)
  } .otherwise {
    when(two) {
      when(cnt3 === 2.U) {
        cnt3 := 0.U
        V := V+1.U
      } .otherwise {
        cnt3 := cnt3+1.U
      }
    } .otherwise {
      cnt3 := 2.U
      V := 255.U(8.W)
    }
  }
  when(reset.asBool) {
    one := false.B
    two := false.B
    cnt9 := 0.U
    L0 := 0.U
    L1 := 0.U
    L2 := 0.U
    L3 := 0.U
    L4 := 0.U
    L5 := 0.U
    L6 := 0.U
    L7 := 0.U
    L8 := 0.U
    add_GF_dec0_reg := 0.U
    add_GF_dec1_reg := 0.U
    add_GF_dec2_reg := 0.U
    add_GF_dec0_reg0 := 0.U
    add_GF_dec1_reg0 := 0.U
    add_GF_dec2_reg0 := 0.U
    F0 := false.B
    F1 := false.B
    F2 := false.B
    FF0 := false.B
    FF1 := false.B
    FF2 := false.B
    FFF0 := false.B
    FFF1 := false.B
    FFF2 := false.B
    add0 := 0.U
    add1 := 0.U
    add2 := 0.U
    Vx2 := 0.U
    Vx3 := 0.U
    Vx6 := 0.U
    Vp := 0.U
    X0 := 0.U
    X1 := 0.U
    X2 := 0.U
    X3 := 0.U
    GF1 := 0.U
    GF2 := 0.U
    GF3 := 0.U
    GF4 := 0.U
    GF5 := 0.U
    GF6 := 0.U
    GF7 := 0.U
    GF8 := 0.U
    yes := false.B
    E := false.B
    chk_flag := false.B
    chk_cnt := 0.U
    root_cnt_out_reg := 0.U
    CEO_out_reg := false.B
    r1_out_reg := 0.U
    r2_out_reg := 0.U
    r3_out_reg := 0.U
    r4_out_reg := 0.U
    r5_out_reg := 0.U
    r6_out_reg := 0.U
    r7_out_reg := 0.U
    r8_out_reg := 0.U
    add_GF_dec0_out_reg := 0.U
    add_GF_dec1_out_reg := 0.U
    add_GF_dec2_out_reg := 0.U
    add_GF_ascending_out_reg := 0.U
  } .otherwise {
    when(CE) {
      one := true.B
      L0 := Lc0
      add_GF_ascending_out_reg := Lc1
      L2 := Lc2
      L3 := Lc3
      L4 := Lc4
      L5 := Lc5
      L6 := Lc6
      L7 := Lc7
      L8 := Lc8
      cnt9 := 7.U
    }
    when(one) {
      cnt9 := cnt9-1.U
      when(cnt9 === 7.U) {
        add_GF_ascending_out_reg := L2
      } .elsewhen (cnt9 === 6.U) {
        add_GF_ascending_out_reg := L3
        L1 := power
      } .elsewhen (cnt9 === 5.U) {
        add_GF_ascending_out_reg := L4
        L2 := power
      } .elsewhen (cnt9 === 4.U) {
        add_GF_ascending_out_reg := L5
        L3 := power
      } .elsewhen (cnt9 === 3.U) {
        add_GF_ascending_out_reg := L6
        L4 := power
      } .elsewhen (cnt9 === 2.U) {
        add_GF_ascending_out_reg := L7
        L5 := power
      } .elsewhen (cnt9 === 1.U) {
        add_GF_ascending_out_reg := L8
        L6 := power
      } .elsewhen (cnt9 === 0.U) {
        L7 := power
      } .elsewhen (cnt9 === 15.U) {
        L8 := power
        one := false.B
        two := true.B
        root_cnt_out_reg := 0.U
        X0 := "h55".U(8.W)
        X1 := "hAA".U(8.W)
        X2 := "hF1".U(8.W)
        X3 := "h55".U(8.W)
        GF1 := 0.U
        GF2 := 0.U
        GF3 := 0.U
        GF4 := 0.U
        GF5 := 0.U
        GF6 := 0.U
        GF7 := 0.U
        GF8 := 0.U
        r1_out_reg := 0.U
        r2_out_reg := 0.U
        r3_out_reg := 0.U
        r4_out_reg := 0.U
        r5_out_reg := 0.U
        r6_out_reg := 0.U
        r7_out_reg := 0.U
        r8_out_reg := 0.U
        chk_flag := false.B
        chk_cnt := 0.U
      } .otherwise {
        add_GF_ascending_out_reg := L2
      }
    }
    when(two) {
      when(cnt3 === 0.U) {
        add_GF_ascending_out_reg := V
      }
      Vp := power
      when(cnt3 === 0.U) {
        add0 := L1.pad(12) + Vp.pad(12)
        add1 := L2.pad(12) + Vp.pad(12) + Vp.pad(12)
        add2 := 0.U
        F0 := (L1.andR) || (Vp.andR)
        F1 := (L2.andR) || (Vp.andR)
        F2 := false.B
      } .elsewhen (cnt3 === 1.U) {
        add0 := L3.pad(12) + Vx3.pad(12)
        add1 := L4.pad(12) + Vx3.pad(12) + Vp.pad(12)
        add2 := L5.pad(12) + Vx3.pad(12) + Vx2.pad(12)
        F0 := (L3.andR) || (Vp.andR)
        F1 := (L4.andR) || (Vp.andR)
        F2 := (L5.andR) || (Vp.andR)
      } .elsewhen (cnt3 === 2.U) {
        add0 := L6.pad(12) + Vx6.pad(12)
        add1 := L7.pad(12) + Vx6.pad(12) + Vp.pad(12)
        add2 := L8.pad(12) + Vx6.pad(12) + Vx2.pad(12)
        F0 := (L6.andR) || (Vp.andR)
        F1 := (L7.andR) || (Vp.andR)
        F2 := (L8.andR) || (Vp.andR)
      } .otherwise {
        add0 := L1.pad(12) + Vp.pad(12)
        add1 := L2.pad(12) + Vp.pad(12) + Vp.pad(12)
        add2 := 0.U
        F0 := (L1.andR) || (Vp.andR)
        F1 := (L2.andR) || (Vp.andR)
        F2 := false.B
      }
      Vx2 := Vp.pad(9) + Vp.pad(9)
      Vx3 := Vp.pad(10) + Vp.pad(10) + Vp.pad(10)
      Vx6 := Vx3.pad(11) + Vx3.pad(11)
      add_GF_dec0_reg := add0(11,8).pad(9) + add0(7,0).pad(9)
      add_GF_dec1_reg := add1(11,8).pad(9) + add1(7,0).pad(9)
      add_GF_dec2_reg := add2(11,8).pad(9) + add2(7,0).pad(9)
      FF0 := F0
      FF1 := F1
      FF2 := F2
      add_GF_dec0_reg0 := add_GF_dec0_reg(8)+add_GF_dec0_reg(7,0)
      add_GF_dec1_reg0 := add_GF_dec1_reg(8)+add_GF_dec1_reg(7,0)
      add_GF_dec2_reg0 := add_GF_dec2_reg(8)+add_GF_dec2_reg(7,0)
      FFF0 := FF0
      FFF1 := FF1
      FFF2 := FF2
      add_GF_dec0_out_reg := Mux((FFF0), "h00".U(8.W), Mux((add_GF_dec0_reg0.andR), "h01".U(8.W), add_GF_dec0_reg0+1.U))
      add_GF_dec1_out_reg := Mux((FFF1), "h00".U(8.W), Mux((add_GF_dec1_reg0.andR), "h01".U(8.W), add_GF_dec1_reg0+1.U))
      add_GF_dec2_out_reg := Mux((FFF2), "h00".U(8.W), Mux((add_GF_dec2_reg0.andR), "h01".U(8.W), add_GF_dec2_reg0+1.U))
      X0 := L0
      GF1 := decimal0
      GF2 := decimal1
      X1 := (X0^GF1)^GF2
      GF3 := decimal0
      GF4 := decimal1
      GF5 := decimal2
      X2 := ((X1^GF3)^GF4)^GF5
      GF6 := decimal0
      GF7 := decimal1
      GF8 := decimal2
      X3 := ((X2^GF6)^GF7)^GF8
      when(((X3 === 0.U) && chk_flag) && (cnt3 === 0.U)) {
        root_cnt_out_reg := root_cnt_out_reg+1.U
        yes := true.B
      }
      when(yes) {
        yes := false.B
        when(root_cnt_out_reg === 1.U) {
          r1_out_reg := V-4.U(8.W)
        } .elsewhen (root_cnt_out_reg === 2.U) {
          r2_out_reg := V-4.U(8.W)
        } .elsewhen (root_cnt_out_reg === 3.U) {
          r3_out_reg := V-4.U(8.W)
        } .elsewhen (root_cnt_out_reg === 4.U) {
          r4_out_reg := V-4.U(8.W)
        } .elsewhen (root_cnt_out_reg === 5.U) {
          r5_out_reg := V-4.U(8.W)
        } .elsewhen (root_cnt_out_reg === 6.U) {
          r6_out_reg := V-4.U(8.W)
        } .elsewhen (root_cnt_out_reg === 7.U) {
          r7_out_reg := V-4.U(8.W)
        } .otherwise {
          r8_out_reg := V-4.U(8.W)
        }
      }
      when((((V-4.U(8.W)).andR) && E) && (cnt3 === 1.U)) {
        two := false.B
        CEO_out_reg := true.B
        E := false.B
      }
      when((V.andR) && (cnt3 === 0.U)) {
        E := true.B
      }
      when(cnt3 === 0.U) {
        when(chk_cnt.andR) {
          chk_cnt := 3.U(2.W)
          chk_flag := true.B
        } .otherwise {
          chk_cnt := chk_cnt+1.U
        }
      }
    }
    when(CEO_out_reg) {
      CEO_out_reg := false.B
    }
  }


}
