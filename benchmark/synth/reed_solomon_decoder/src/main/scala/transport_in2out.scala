package reeds

import chisel3._


class transport_in2out() extends Module with AsyncRegs {
  val S_Ready = IO(Input(Bool()))
  val RE = IO(Output(Bool()))
  val WE = IO(Output(Bool()))
  val RdAdd = IO(Output(UInt(8.W)))
  val WrAdd = IO(Output(UInt(8.W)))
  val Wr_done = IO(Output(Bool()))
  // NOTE: The following statements are auto generated based on existing output reg of the original verilog source
  val RE_out_reg = aReg(false.B) 
  RE := RE_out_reg
  val WE_out_reg = aReg(false.B) 
  WE := WE_out_reg
  val RdAdd_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  RdAdd := RdAdd_out_reg
  val WrAdd_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  WrAdd := WrAdd_out_reg
  val Wr_done_out_reg = aReg(false.B) 
  Wr_done := Wr_done_out_reg

  val cnt = aReg(false.B) 
  val state = aReg(false.B) 
  when(reset.asBool) {
    WE_out_reg := false.B
    RE_out_reg := false.B
    RdAdd_out_reg := 0.U
    WrAdd_out_reg := 0.U
    Wr_done_out_reg := false.B
    state := false.B
    cnt := false.B
  } .otherwise {
    when(state === true.B) {
      cnt :=  ~cnt
      when(cnt) {
        WrAdd_out_reg := WrAdd_out_reg+1.U
        when(WrAdd_out_reg === 186.U) {
          state := false.B
          Wr_done_out_reg := true.B
        }
      } .otherwise {
        RdAdd_out_reg := RdAdd_out_reg-1.U
      }
    } .otherwise {
      Wr_done_out_reg := false.B
      when(S_Ready) {
        state := true.B
        RE_out_reg :=  ~RE_out_reg
        WE_out_reg :=  ~WE_out_reg
        RdAdd_out_reg := 204.U
        WrAdd_out_reg := 255.U
        cnt := false.B
      }
    }
  }


}
