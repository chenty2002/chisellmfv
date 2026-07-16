package reeds

import chisel3._
import chisel3.util.Cat


class BM_lamda(val bug1: Boolean = false) extends Module with AsyncRegs {
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
  val Sm_ready = IO(Input(Bool()))
  val erasure_ready = IO(Input(Bool()))
  val erasure_cnt = IO(Input(UInt(4.W)))
  val pow1 = IO(Input(UInt(8.W)))
  val pow2 = IO(Input(UInt(8.W)))
  val dec1 = IO(Input(UInt(8.W)))
  val add_pow1 = IO(Output(UInt(8.W)))
  val add_pow2 = IO(Output(UInt(8.W)))
  val add_dec1 = IO(Output(UInt(8.W)))
  val L_ready = IO(Output(Bool()))
  val L1 = IO(Output(UInt(8.W)))
  val L2 = IO(Output(UInt(8.W)))
  val L3 = IO(Output(UInt(8.W)))
  val L4 = IO(Output(UInt(8.W)))
  val L5 = IO(Output(UInt(8.W)))
  val L6 = IO(Output(UInt(8.W)))
  val L7 = IO(Output(UInt(8.W)))
  val L8 = IO(Output(UInt(8.W)))
  // NOTE: The following statements are auto generated based on existing output reg of the original verilog source
  val add_pow1_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  add_pow1 := add_pow1_out_reg
  val add_pow2_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  add_pow2 := add_pow2_out_reg
  val L_ready_out_reg = aReg(false.B) 
  L_ready := L_ready_out_reg

  val L = asyncResetByteArray(10, "L")
  val Lt = asyncResetByteArray(10, "Lt")
  val T = asyncResetByteArray(11, "T")
  val D = aReg(0.U.asTypeOf(UInt(8.W))) 
  val K = aReg(0.U.asTypeOf(UInt(5.W))) 
  val N = aReg(0.U.asTypeOf(UInt(4.W))) 
  val e_cnt = aReg(0.U.asTypeOf(UInt(4.W))) 
  val S = asyncResetByteArray(17, "S")
  val add_1 = aReg(0.U(9.W))
  val IS_255_1 = aReg(false.B) 
  val div1 = aReg(false.B) 
  val cnt = aReg(0.U.asTypeOf(UInt(4.W))) 
  val Step1: UInt = "b00000001".U(8.W)
  val Step2: UInt = "b00000010".U(8.W)
  val Step3: UInt = "b00000100".U(8.W)
  val Step4: UInt = "b00001000".U(8.W)
  val Step5: UInt = "b00010000".U(8.W)
  val Step6: UInt = "b00100000".U(8.W)
  val Step7: UInt = "b01000000".U(8.W)
  val Step8: UInt = "b10000000".U(8.W)
  val const_timing = aReg(0.U.asTypeOf(UInt(9.W))) 
  private val stepPair = initialAReg(Step1)
  val Step = stepPair._1
  val StepNext = stepPair._2
  L1 := L(2)
  L2 := L(3)
  L3 := L(4)
  L4 := L(5)
  L5 := L(6)
  L6 := L(7)
  L7 := L(8)
  L8 := L(9)
  add_dec1 := Mux((IS_255_1), "h00".U(8.W), Mux((add_1(7,0).andR && !add_1(8)), "h01".U(8.W), Mux((div1), (add_1(7,0)-add_1(8))+1.U, (add_1(7,0)+add_1(8))+1.U)))
  when(reset.asBool) {
    add_1 := 0.U
    IS_255_1 := false.B
    div1 := false.B
    add_pow1_out_reg := 0.U
    add_pow2_out_reg := 0.U
    e_cnt := 0.U
    S.write(1, 0.U)
    S.write(2, 0.U)
    S.write(3, 0.U)
    S.write(4, 0.U)
    S.write(5, 0.U)
    S.write(6, 0.U)
    S.write(7, 0.U)
    S.write(8, 0.U)
    S.write(9, 0.U)
    S.write(10, 0.U)
    S.write(11, 0.U)
    S.write(12, 0.U)
    S.write(13, 0.U)
    S.write(14, 0.U)
    S.write(15, 0.U)
    S.write(16, 0.U)
    L.write(1, 0.U)
    L.write(2, 0.U)
    L.write(3, 0.U)
    L.write(4, 0.U)
    L.write(5, 0.U)
    L.write(6, 0.U)
    L.write(7, 0.U)
    L.write(8, 0.U)
    L.write(9, 0.U)
    Lt.write(1, 0.U)
    Lt.write(2, 0.U)
    Lt.write(3, 0.U)
    Lt.write(4, 0.U)
    Lt.write(5, 0.U)
    Lt.write(6, 0.U)
    Lt.write(7, 0.U)
    Lt.write(8, 0.U)
    Lt.write(9, 0.U)
    T.write(1, 0.U)
    T.write(2, 0.U)
    T.write(3, 0.U)
    T.write(4, 0.U)
    T.write(5, 0.U)
    T.write(6, 0.U)
    T.write(7, 0.U)
    T.write(8, 0.U)
    T.write(9, 0.U)
    T.write(10, 0.U)
    D := 0.U
    K := 0.U
    N := 0.U
    cnt := 0.U
    StepNext := Step1
    L_ready_out_reg := false.B
    const_timing := 0.U
  } .otherwise {
    when(Step === Step2) {
      K := K+1.U
      StepNext := Step3
    } .elsewhen (Step === Step3) {
      when(N === 0.U) {
        D := S(K+e_cnt)
        when(S(K+e_cnt) === 0.U) {
          StepNext := Step6
        } .otherwise {
          StepNext := Step4
        }
      } .otherwise {
        when(cnt === N+4.U) {
          cnt := 0.U
          when((D^dec1) === 0.U) {
            StepNext := Step6
          } .otherwise {
            StepNext := Step4
          }
        } .otherwise {
          cnt := cnt+1.U
        }
        when(cnt === 0.U) {
          D := S(K+e_cnt)
        } .elsewhen (cnt < 5.U) {
          add_pow1_out_reg := L(cnt+1.U)
          add_pow2_out_reg := S((K+e_cnt)-cnt)
          div1 := false.B
          add_1 := Cat(0.U(1.W), pow1) + Cat(0.U(1.W), pow2)
          IS_255_1 := Mux(((pow1.andR) || (pow2.andR)), true.B, false.B)
        } .otherwise {
          add_pow1_out_reg := L(cnt+1.U)
          add_pow2_out_reg := S((K+e_cnt)-cnt)
          div1 := false.B
          add_1 := Cat(0.U(1.W), pow1) + Cat(0.U(1.W), pow2)
          IS_255_1 := Mux(((pow1.andR) || (pow2.andR)), true.B, false.B)
          D := D^dec1
        }
      }
    } .elsewhen (Step === Step4) {
      when(cnt === 11.U-e_cnt(3,1)) {
        cnt := 0.U
        StepNext := Step5
      } .otherwise {
        cnt := cnt+1.U
      }
      add_pow1_out_reg := T(cnt+2.U)
      add_pow2_out_reg := D
      div1 := false.B
      add_1 := Cat(0.U(1.W), pow1) + Cat(0.U(1.W), pow2)
      IS_255_1 := Mux(((pow1.andR) || (pow2.andR)), true.B, false.B)
      when(cnt > 3.U) {
        Lt.write(cnt-2.U, L(cnt-2.U)^dec1)
      }
    } .elsewhen (Step === Step5) {
      when(Cat(N, "b0".U(1.W)) >= K) {
        StepNext := Step6
        L.write(1, Lt(1))
        L.write(2, Lt(2))
        L.write(3, Lt(3))
        L.write(4, Lt(4))
        L.write(5, Lt(5))
        L.write(6, Lt(6))
        L.write(7, Lt(7))
        L.write(8, Lt(8))
        L.write(9, Lt(9))
      } .otherwise {
        when(cnt === 12.U-e_cnt(3,1)) {
          cnt := 0.U
          StepNext := Step6
          N := K-N
          L.write(1, Lt(1))
          L.write(2, Lt(2))
          L.write(3, Lt(3))
          L.write(4, Lt(4))
          L.write(5, Lt(5))
          L.write(6, Lt(6))
          L.write(7, Lt(7))
          L.write(8, Lt(8))
          L.write(9, Lt(9))
        } .otherwise {
          cnt := cnt+1.U
        }
        add_pow1_out_reg := L(cnt+1.U)
        add_pow2_out_reg := D
        div1 := true.B
        add_1 := Cat(0.U(1.W), pow1) - Cat(0.U(1.W), pow2)
        IS_255_1 := Mux(((pow1.andR) || (pow2.andR)), true.B, false.B)
        when(cnt > 3.U) {
          T.write(cnt-3.U, dec1)
        }
      }
    } .elsewhen (Step === Step6) {
      StepNext := Step7
      T.write(1, 0.U)
      T.write(2, T(1))
      T.write(3, T(2))
      T.write(4, T(3))
      T.write(5, T(4))
      T.write(6, T(5))
      T.write(7, T(6))
      T.write(8, T(7))
      T.write(9, T(8))
      T.write(10, T(9))
    } .elsewhen (Step === Step7) {
      when(K < (16.U-e_cnt)) {
        StepNext := Step2
      } .otherwise {
        StepNext := Step8
      }
    } .elsewhen (Step === Step8) {
      when(const_timing === 0.U) {
        L_ready_out_reg := true.B
        StepNext := Step1
      }
    } .otherwise {
      L.write(1, 1.U)
      L.write(2, 0.U)
      L.write(3, 0.U)
      L.write(4, 0.U)
      L.write(5, 0.U)
      L.write(6, 0.U)
      L.write(7, 0.U)
      L.write(8, 0.U)
      L.write(9, 0.U)
      Lt.write(1, 1.U)
      Lt.write(2, 0.U)
      Lt.write(3, 0.U)
      Lt.write(4, 0.U)
      Lt.write(5, 0.U)
      Lt.write(6, 0.U)
      Lt.write(7, 0.U)
      Lt.write(8, 0.U)
      Lt.write(9, 0.U)
      T.write(1, 0.U)
      T.write(2, 1.U)
      T.write(3, 0.U)
      T.write(4, 0.U)
      T.write(5, 0.U)
      T.write(6, 0.U)
      T.write(7, 0.U)
      T.write(8, 0.U)
      T.write(9, 0.U)
      T.write(10, 0.U)
      D := 0.U
      K := 0.U
      N := 0.U
      cnt := 0.U
      L_ready_out_reg := false.B
      when(erasure_ready) {
        e_cnt := erasure_cnt
      }
      when(Sm_ready) {
        StepNext := Step2
        S.write(1, Sm1)
        S.write(2, Sm2)
        S.write(3, Sm3)
        S.write(4, Sm4)
        S.write(5, Sm5)
        S.write(6, Sm6)
        S.write(7, Sm7)
        S.write(8, Sm8)
        S.write(9, Sm9)
        S.write(10, Sm10)
        S.write(11, Sm11)
        S.write(12, Sm12)
        S.write(13, Sm13)
        S.write(14, Sm14)
        S.write(15, Sm15)
        S.write(16, Sm16)
      }
    }
    when(Step === Step1) {
      // buggy_1 retains Verilog's 8'd500 truncation before assignment to 9 bits.
      const_timing := (if (bug1) 244.U else 500.U)
    } .otherwise {
      const_timing := const_timing-1.U
    }
  }


}
