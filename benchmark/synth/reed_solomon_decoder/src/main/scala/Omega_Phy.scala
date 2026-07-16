package reeds

import chisel3._


class Omega_Phy() extends Module with AsyncRegs {
  val Sm_ready = IO(Input(Bool()))
  val Sm1 = IO(Input(UInt(8.W)))
  val Sm2 = IO(Input(UInt(8.W)))
  val Sm3 = IO(Input(UInt(8.W)))
  val Sm4 = IO(Input(UInt(8.W)))
  val Sm5 = IO(Input(UInt(8.W)))
  val Sm6 = IO(Input(UInt(8.W)))
  val Sm7 = IO(Input(UInt(8.W)))
  val Sm8 = IO(Input(UInt(8.W)))
  val Sm9 = IO(Input(UInt(8.W)))
  val Sm10 = IO(Input(UInt(8.W)))
  val Sm11 = IO(Input(UInt(8.W)))
  val Sm12 = IO(Input(UInt(8.W)))
  val Sm13 = IO(Input(UInt(8.W)))
  val Sm14 = IO(Input(UInt(8.W)))
  val Sm15 = IO(Input(UInt(8.W)))
  val Sm16 = IO(Input(UInt(8.W)))
  val L_ready = IO(Input(Bool()))
  val L1 = IO(Input(UInt(8.W)))
  val L2 = IO(Input(UInt(8.W)))
  val L3 = IO(Input(UInt(8.W)))
  val L4 = IO(Input(UInt(8.W)))
  val L5 = IO(Input(UInt(8.W)))
  val L6 = IO(Input(UInt(8.W)))
  val L7 = IO(Input(UInt(8.W)))
  val L8 = IO(Input(UInt(8.W)))
  val pow1 = IO(Input(UInt(8.W)))
  val pow2 = IO(Input(UInt(8.W)))
  val pow3 = IO(Input(UInt(8.W)))
  val dec1 = IO(Input(UInt(8.W)))
  val add_pow1 = IO(Output(UInt(8.W)))
  val add_pow2 = IO(Output(UInt(8.W)))
  val add_pow3 = IO(Output(UInt(8.W)))
  val add_dec1 = IO(Output(UInt(8.W)))
  val poly_ready = IO(Output(Bool()))
  val O1 = IO(Output(UInt(8.W)))
  val O2 = IO(Output(UInt(8.W)))
  val O3 = IO(Output(UInt(8.W)))
  val O4 = IO(Output(UInt(8.W)))
  val O5 = IO(Output(UInt(8.W)))
  val O6 = IO(Output(UInt(8.W)))
  val O7 = IO(Output(UInt(8.W)))
  val O8 = IO(Output(UInt(8.W)))
  val O9 = IO(Output(UInt(8.W)))
  val O10 = IO(Output(UInt(8.W)))
  val O11 = IO(Output(UInt(8.W)))
  val O12 = IO(Output(UInt(8.W)))
  val O13 = IO(Output(UInt(8.W)))
  val O14 = IO(Output(UInt(8.W)))
  val O15 = IO(Output(UInt(8.W)))
  val O16 = IO(Output(UInt(8.W)))
  val P1 = IO(Output(UInt(8.W)))
  val P3 = IO(Output(UInt(8.W)))
  val P5 = IO(Output(UInt(8.W)))
  val P7 = IO(Output(UInt(8.W)))
  // NOTE: The following statements are auto generated based on existing output reg of the original verilog source
  val add_pow1_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  add_pow1 := add_pow1_out_reg
  val add_pow2_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  add_pow2 := add_pow2_out_reg
  val add_pow3_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  add_pow3 := add_pow3_out_reg
  val poly_ready_out_reg = aReg(false.B) 
  poly_ready := poly_ready_out_reg
  val P1_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  P1 := P1_out_reg
  val P3_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  P3 := P3_out_reg
  val P5_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  P5 := P5_out_reg
  val P7_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  P7 := P7_out_reg

  val state1: UInt = "b0000000000000000001".U(19.W)
  val state2: UInt = "b0000000000000000010".U(19.W)
  val state10: UInt = "b0000000000000000100".U(19.W)
  val state11: UInt = "b0000000000000001000".U(19.W)
  val state12: UInt = "b0000000000000010000".U(19.W)
  val state13: UInt = "b0000000000000100000".U(19.W)
  val state14: UInt = "b0000000000001000000".U(19.W)
  val state15: UInt = "b0000000000010000000".U(19.W)
  val state16: UInt = "b0000000000100000000".U(19.W)
  val state17: UInt = "b0000000001000000000".U(19.W)
  val state18: UInt = "b0000000010000000000".U(19.W)
  val state19: UInt = "b0000000100000000000".U(19.W)
  val state20: UInt = "b0000001000000000000".U(19.W)
  val state21: UInt = "b0000010000000000000".U(19.W)
  val state22: UInt = "b0000100000000000000".U(19.W)
  val state23: UInt = "b0001000000000000000".U(19.W)
  val state24: UInt = "b0010000000000000000".U(19.W)
  val state25: UInt = "b0100000000000000000".U(19.W)
  val state26: UInt = "b1000000000000000000".U(19.W)
  val (state, stateNext) = initialAReg(state1)
  val Sp = asyncResetByteArray(16, "Sp")
  val L = asyncResetByteArray(9, "L")
  val Lp = asyncResetByteArray(9, "Lp")
  val O = asyncResetByteArray(17, "O")
  O1 := O(1)
  O2 := O(2)
  O3 := O(3)
  O4 := O(4)
  O5 := O(5)
  O6 := O(6)
  O7 := O(7)
  O8 := O(8)
  O9 := O(9)
  O10 := O(10)
  O11 := O(11)
  O12 := O(12)
  O13 := O(13)
  O14 := O(14)
  O15 := O(15)
  O16 := O(16)
  val cnt = aReg(0.U.asTypeOf(UInt(4.W))) 
  val cnt1 = aReg(0.U.asTypeOf(UInt(4.W))) 
  val cnt2 = aReg(0.U.asTypeOf(UInt(4.W))) 
  val add_1 = aReg(0.U.asTypeOf(UInt(9.W))) 
  val F1 = aReg(false.B) 
  add_dec1 := Mux((F1), "h00".U(8.W), Mux((add_1(7,0).andR), "h01".U(8.W), (add_1(7,0)+add_1(8))+1.U))
  when(reset.asBool) {
    poly_ready_out_reg := false.B
    P1_out_reg := 0.U
    P3_out_reg := 0.U
    P5_out_reg := 0.U
    P7_out_reg := 0.U
    add_pow1_out_reg := 0.U
    add_pow2_out_reg := 0.U
    add_pow3_out_reg := 0.U
    add_1 := 0.U
    F1 := false.B
    for(k <- 1 to 15){
      Sp.write(k, 0.U)
      O.write(k, 0.U)
    }
    for(k <- 1 to 8){
      L.write(k, 0.U)
      Lp.write(k, 0.U)
    }
    O.write(16, 0.U)
    cnt := 0.U
    cnt1 := 0.U
    cnt2 := 0.U
    stateNext := state1
  } .otherwise {
    when(state === state1) {
      when(Sm_ready) {
        O.write(1, Sm1)
        O.write(2, Sm2)
        O.write(3, Sm3)
        O.write(4, Sm4)
        O.write(5, Sm5)
        O.write(6, Sm6)
        O.write(7, Sm7)
        O.write(8, Sm8)
        O.write(9, Sm9)
        O.write(10, Sm10)
        O.write(11, Sm11)
        O.write(12, Sm12)
        O.write(13, Sm13)
        O.write(14, Sm14)
        O.write(15, Sm15)
        O.write(16, Sm16)
      }
      when(L_ready) {
        L.write(1, L1)
        L.write(2, L2)
        L.write(3, L3)
        L.write(4, L4)
        L.write(5, L5)
        L.write(6, L6)
        L.write(7, L7)
        L.write(8, L8)
        P1_out_reg := L1
        P3_out_reg := 255.U
        P5_out_reg := 255.U
        P7_out_reg := 255.U
        stateNext := state2
      }
      cnt := 0.U
    } .elsewhen (state === state2) {
      when(cnt === 9.U) {
        stateNext := state10
        cnt := 0.U
      } .otherwise {
        cnt := cnt+1.U
      }
      when(cnt === 0.U) {
        add_pow1_out_reg := O(1)
        add_pow2_out_reg := O(2)
        add_pow3_out_reg := O(3)
      } .elsewhen (cnt === 1.U) {
        add_pow1_out_reg := O(4)
        add_pow2_out_reg := O(5)
        add_pow3_out_reg := O(6)
      } .elsewhen (cnt === 2.U) {
        add_pow1_out_reg := O(7)
        add_pow2_out_reg := O(8)
        add_pow3_out_reg := O(9)
        Sp.write(1, pow1)
        Sp.write(2, pow2)
        Sp.write(3, pow3)
      } .elsewhen (cnt === 3.U) {
        add_pow1_out_reg := O(10)
        add_pow2_out_reg := O(11)
        add_pow3_out_reg := O(12)
        Sp.write(4, pow1)
        Sp.write(5, pow2)
        Sp.write(6, pow3)
      } .elsewhen (cnt === 4.U) {
        add_pow1_out_reg := O(13)
        add_pow2_out_reg := O(14)
        add_pow3_out_reg := O(15)
        Sp.write(7, pow1)
        Sp.write(8, pow2)
        Sp.write(9, pow3)
      } .elsewhen (cnt === 5.U) {
        add_pow1_out_reg := L(1)
        add_pow2_out_reg := L(2)
        add_pow3_out_reg := L(3)
        Sp.write(10, pow1)
        Sp.write(11, pow2)
        Sp.write(12, pow3)
      } .elsewhen (cnt === 6.U) {
        add_pow1_out_reg := L(4)
        add_pow2_out_reg := L(5)
        add_pow3_out_reg := L(6)
        Sp.write(13, pow1)
        Sp.write(14, pow2)
        Sp.write(15, pow3)
      } .elsewhen (cnt === 7.U) {
        add_pow1_out_reg := L(7)
        add_pow2_out_reg := L(8)
        Lp.write(1, pow1)
        Lp.write(2, pow2)
        Lp.write(3, pow3)
        P3_out_reg := pow3
      } .elsewhen (cnt === 8.U) {
        Lp.write(4, pow1)
        Lp.write(5, pow2)
        Lp.write(6, pow3)
        P5_out_reg := pow2
      } .otherwise {
        Lp.write(7, pow1)
        Lp.write(8, pow2)
        P7_out_reg := pow1
      }
    } .elsewhen (state === state10) {
      when(cnt === 2.U) {
        stateNext := state11
        cnt := 0.U
        cnt1 := 1.U
        cnt2 := 2.U
      } .otherwise {
        cnt := cnt+1.U
      }
      add_1 := Sp(1).pad(9) + Lp(1).pad(9)
      F1 := Mux(((Sp(1).andR) || (Lp(1).andR)), true.B, false.B)
      when(cnt === 2.U) {
        O.write(2, O(2)^dec1)
      }
    } .elsewhen (state === state11) {
      when(cnt === 2.U) {
        cnt := 0.U
        O.write(3, O(3)^dec1)
      } .otherwise {
        cnt := cnt+1.U
      }
      when(cnt === 2.U) {
        when(cnt2 === 1.U) {
          cnt2 := 3.U
          cnt1 := 1.U
          stateNext := state12
        } .otherwise {
          cnt2 := cnt2-1.U
          cnt1 := cnt1+1.U
        }
      }
      add_1 := Lp(cnt1).pad(9) + Sp(cnt2).pad(9)
      F1 := Mux(((Lp(cnt1).andR) || (Sp(cnt2).andR)), true.B, false.B)
    } .elsewhen (state === state12) {
      when(cnt === 2.U) {
        cnt := 0.U
        O.write(4, O(4)^dec1)
      } .otherwise {
        cnt := cnt+1.U
      }
      when(cnt === 2.U) {
        when(cnt2 === 1.U) {
          cnt2 := 4.U
          cnt1 := 1.U
          stateNext := state13
        } .otherwise {
          cnt2 := cnt2-1.U
          cnt1 := cnt1+1.U
        }
      }
      add_1 := Lp(cnt1).pad(9) + Sp(cnt2).pad(9)
      F1 := Mux(((Lp(cnt1).andR) || (Sp(cnt2).andR)), true.B, false.B)
    } .elsewhen (state === state13) {
      when(cnt === 2.U) {
        cnt := 0.U
        O.write(5, O(5)^dec1)
      } .otherwise {
        cnt := cnt+1.U
      }
      when(cnt === 2.U) {
        when(cnt2 === 1.U) {
          cnt2 := 5.U
          cnt1 := 1.U
          stateNext := state14
        } .otherwise {
          cnt2 := cnt2-1.U
          cnt1 := cnt1+1.U
        }
      }
      add_1 := Lp(cnt1).pad(9) + Sp(cnt2).pad(9)
      F1 := Mux(((Lp(cnt1).andR) || (Sp(cnt2).andR)), true.B, false.B)
    } .elsewhen (state === state14) {
      when(cnt === 2.U) {
        cnt := 0.U
        O.write(6, O(6)^dec1)
      } .otherwise {
        cnt := cnt+1.U
      }
      when(cnt === 2.U) {
        when(cnt2 === 1.U) {
          cnt2 := 6.U
          cnt1 := 1.U
          stateNext := state15
        } .otherwise {
          cnt2 := cnt2-1.U
          cnt1 := cnt1+1.U
        }
      }
      add_1 := Lp(cnt1).pad(9) + Sp(cnt2).pad(9)
      F1 := Mux(((Lp(cnt1).andR) || (Sp(cnt2).andR)), true.B, false.B)
    } .elsewhen (state === state15) {
      when(cnt === 2.U) {
        cnt := 0.U
        O.write(7, O(7)^dec1)
      } .otherwise {
        cnt := cnt+1.U
      }
      when(cnt === 2.U) {
        when(cnt2 === 1.U) {
          cnt2 := 7.U
          cnt1 := 1.U
          stateNext := state16
        } .otherwise {
          cnt2 := cnt2-1.U
          cnt1 := cnt1+1.U
        }
      }
      add_1 := Lp(cnt1).pad(9) + Sp(cnt2).pad(9)
      F1 := Mux(((Lp(cnt1).andR) || (Sp(cnt2).andR)), true.B, false.B)
    } .elsewhen (state === state16) {
      when(cnt === 2.U) {
        cnt := 0.U
        O.write(8, O(8)^dec1)
      } .otherwise {
        cnt := cnt+1.U
      }
      when(cnt === 2.U) {
        when(cnt2 === 1.U) {
          cnt2 := 8.U
          cnt1 := 1.U
          stateNext := state17
        } .otherwise {
          cnt2 := cnt2-1.U
          cnt1 := cnt1+1.U
        }
      }
      add_1 := Lp(cnt1).pad(9) + Sp(cnt2).pad(9)
      F1 := Mux(((Lp(cnt1).andR) || (Sp(cnt2).andR)), true.B, false.B)
    } .elsewhen (state === state17) {
      when(cnt === 2.U) {
        cnt := 0.U
        O.write(9, O(9)^dec1)
      } .otherwise {
        cnt := cnt+1.U
      }
      when(cnt === 2.U) {
        when(cnt2 === 1.U) {
          cnt2 := 9.U
          cnt1 := 1.U
          stateNext := state18
        } .otherwise {
          cnt2 := cnt2-1.U
          cnt1 := cnt1+1.U
        }
      }
      add_1 := Lp(cnt1).pad(9) + Sp(cnt2).pad(9)
      F1 := Mux(((Lp(cnt1).andR) || (Sp(cnt2).andR)), true.B, false.B)
    } .elsewhen (state === state18) {
      when(cnt === 2.U) {
        cnt := 0.U
        O.write(10, O(10)^dec1)
      } .otherwise {
        cnt := cnt+1.U
      }
      when(cnt === 2.U) {
        when(cnt2 === 2.U) {
          cnt2 := 10.U
          cnt1 := 1.U
          stateNext := state19
        } .otherwise {
          cnt2 := cnt2-1.U
          cnt1 := cnt1+1.U
        }
      }
      add_1 := Lp(cnt1).pad(9) + Sp(cnt2).pad(9)
      F1 := Mux(((Lp(cnt1).andR) || (Sp(cnt2).andR)), true.B, false.B)
    } .elsewhen (state === state19) {
      when(cnt === 2.U) {
        cnt := 0.U
        O.write(11, O(11)^dec1)
      } .otherwise {
        cnt := cnt+1.U
      }
      when(cnt === 2.U) {
        when(cnt2 === 3.U) {
          cnt2 := 11.U
          cnt1 := 1.U
          stateNext := state20
        } .otherwise {
          cnt2 := cnt2-1.U
          cnt1 := cnt1+1.U
        }
      }
      add_1 := Lp(cnt1).pad(9) + Sp(cnt2).pad(9)
      F1 := Mux(((Lp(cnt1).andR) || (Sp(cnt2).andR)), true.B, false.B)
    } .elsewhen (state === state20) {
      when(cnt === 2.U) {
        cnt := 0.U
        O.write(12, O(12)^dec1)
      } .otherwise {
        cnt := cnt+1.U
      }
      when(cnt === 2.U) {
        when(cnt2 === 4.U) {
          cnt2 := 12.U
          cnt1 := 1.U
          stateNext := state21
        } .otherwise {
          cnt2 := cnt2-1.U
          cnt1 := cnt1+1.U
        }
      }
      add_1 := Lp(cnt1).pad(9) + Sp(cnt2).pad(9)
      F1 := Mux(((Lp(cnt1).andR) || (Sp(cnt2).andR)), true.B, false.B)
    } .elsewhen (state === state21) {
      when(cnt === 2.U) {
        cnt := 0.U
        O.write(13, O(13)^dec1)
      } .otherwise {
        cnt := cnt+1.U
      }
      when(cnt === 2.U) {
        when(cnt2 === 5.U) {
          cnt2 := 13.U
          cnt1 := 1.U
          stateNext := state22
        } .otherwise {
          cnt2 := cnt2-1.U
          cnt1 := cnt1+1.U
        }
      }
      add_1 := Lp(cnt1).pad(9) + Sp(cnt2).pad(9)
      F1 := Mux(((Lp(cnt1).andR) || (Sp(cnt2).andR)), true.B, false.B)
    } .elsewhen (state === state22) {
      when(cnt === 2.U) {
        cnt := 0.U
        O.write(14, O(14)^dec1)
      } .otherwise {
        cnt := cnt+1.U
      }
      when(cnt === 2.U) {
        when(cnt2 === 6.U) {
          cnt2 := 14.U
          cnt1 := 1.U
          stateNext := state23
        } .otherwise {
          cnt2 := cnt2-1.U
          cnt1 := cnt1+1.U
        }
      }
      add_1 := Lp(cnt1).pad(9) + Sp(cnt2).pad(9)
      F1 := Mux(((Lp(cnt1).andR) || (Sp(cnt2).andR)), true.B, false.B)
    } .elsewhen (state === state23) {
      when(cnt === 2.U) {
        cnt := 0.U
        O.write(15, O(15)^dec1)
      } .otherwise {
        cnt := cnt+1.U
      }
      when(cnt === 2.U) {
        when(cnt2 === 7.U) {
          cnt2 := 15.U
          cnt1 := 1.U
          stateNext := state24
        } .otherwise {
          cnt2 := cnt2-1.U
          cnt1 := cnt1+1.U
        }
      }
      add_1 := Lp(cnt1).pad(9) + Sp(cnt2).pad(9)
      F1 := Mux(((Lp(cnt1).andR) || (Sp(cnt2).andR)), true.B, false.B)
    } .elsewhen (state === state24) {
      when(cnt === 2.U) {
        cnt := 0.U
        O.write(16, O(16)^dec1)
      } .otherwise {
        cnt := cnt+1.U
      }
      when(cnt === 2.U) {
        when(cnt2 === 8.U) {
          cnt2 := 0.U
          cnt1 := 0.U
          stateNext := state25
        } .otherwise {
          cnt2 := cnt2-1.U
          cnt1 := cnt1+1.U
        }
      }
      add_1 := Lp(cnt1).pad(9) + Sp(cnt2).pad(9)
      F1 := Mux(((Lp(cnt1).andR) || (Sp(cnt2).andR)), true.B, false.B)
    } .elsewhen (state === state25) {
      when(cnt === 6.U) {
        stateNext := state26
        cnt := 0.U
        poly_ready_out_reg := true.B
      } .otherwise {
        cnt := cnt+1.U
      }
      when(cnt === 0.U) {
        add_pow1_out_reg := O(2)
        add_pow2_out_reg := O(3)
        add_pow3_out_reg := O(4)
      } .elsewhen (cnt === 1.U) {
        add_pow1_out_reg := O(5)
        add_pow2_out_reg := O(6)
        add_pow3_out_reg := O(7)
      } .elsewhen (cnt === 2.U) {
        add_pow1_out_reg := O(8)
        add_pow2_out_reg := O(9)
        add_pow3_out_reg := O(10)
        O.write(2, pow1)
        O.write(3, pow2)
        O.write(4, pow3)
      } .elsewhen (cnt === 3.U) {
        add_pow1_out_reg := O(11)
        add_pow2_out_reg := O(12)
        add_pow3_out_reg := O(13)
        O.write(5, pow1)
        O.write(6, pow2)
        O.write(7, pow3)
      } .elsewhen (cnt === 4.U) {
        add_pow1_out_reg := O(14)
        add_pow2_out_reg := O(15)
        add_pow3_out_reg := O(16)
        O.write(8, pow1)
        O.write(9, pow2)
        O.write(10, pow3)
      } .elsewhen (cnt === 5.U) {
        O.write(11, pow1)
        O.write(12, pow2)
        O.write(13, pow3)
      } .otherwise {
        O.write(14, pow1)
        O.write(15, pow2)
        O.write(16, pow3)
      }
    } .otherwise {
      poly_ready_out_reg := false.B
      stateNext := state1
    }
  }


}
