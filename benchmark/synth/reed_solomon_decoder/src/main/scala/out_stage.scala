package reeds

import chisel3._


class out_stage(val bug3: Boolean = false) extends Module with AsyncRegs {
  val DONE = IO(Input(Bool()))
  val RE = IO(Output(Bool()))
  val RdAdd = IO(Output(UInt(8.W)))
  val In_byte = IO(Input(UInt(8.W)))
  val Out_byte = IO(Output(UInt(8.W)))
  val CEO = IO(Output(Bool()))
  val Valid_out = IO(Output(Bool()))
  val out_done = IO(Output(Bool()))
  // NOTE: The following statements are auto generated based on existing output reg of the original verilog source
  val RE_out_reg = aReg(false.B) 
  RE := RE_out_reg
  val RdAdd_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  RdAdd := RdAdd_out_reg
  val Out_byte_out_reg = aReg(0.U.asTypeOf(UInt(8.W))) 
  Out_byte := Out_byte_out_reg
  val CEO_out_reg = aReg(false.B) 
  CEO := CEO_out_reg
  val Valid_out_out_reg = aReg(false.B) 
  Valid_out := Valid_out_out_reg
  val out_done_out_reg = aReg(false.B) 
  out_done := out_done_out_reg

  val CE = aReg(false.B) 
  val cnt8 = aReg(0.U.asTypeOf(UInt(3.W))) 
  val state = aReg(false.B) 
  val F = aReg(false.B) 
  when(reset.asBool) {
    CE := false.B
    cnt8 := 0.U
    CEO_out_reg := false.B
  } .otherwise {
    cnt8 := cnt8+1.U
    CEO_out_reg := CE
    when(cnt8.andR) {
      CE := true.B
    } .otherwise {
      CE := false.B
    }
  }
  when(reset.asBool) {
    RE_out_reg := false.B
    RdAdd_out_reg := 0.U
    out_done_out_reg := false.B
    state := false.B
    Valid_out_out_reg := false.B
    Out_byte_out_reg := 0.U
    F := false.B
  } .otherwise {
    when(state === true.B) {
      when(CE) {
        when(RdAdd_out_reg === 187.U) {
          state := false.B
          out_done_out_reg := true.B
        } .otherwise {
          RdAdd_out_reg := RdAdd_out_reg+1.U
        }
        Out_byte_out_reg := In_byte
        Valid_out_out_reg := true.B
      }
    } .otherwise {
      when(CE) {
        // buggy_3 drives Valid_out high in the CE-qualified idle path.
        Valid_out_out_reg := bug3.B
      }
      out_done_out_reg := false.B
      when(DONE) {
        F := true.B
        RE_out_reg :=  ~RE_out_reg
        RdAdd_out_reg := 0.U
      }
      when(F && CE) {
        state := true.B
        F := false.B
      }
    }
  }


}
