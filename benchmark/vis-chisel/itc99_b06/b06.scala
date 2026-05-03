package llmverify

import chisel3._
import chisel3.util._

class b06 extends Module {
  val io = IO(new Bundle {
    val EQL = Input(Bool())
    val CONT_EQL = Input(Bool())
    val CC_MUX = Output(UInt(2.W))
    val USCITE = Output(UInt(2.W))
    val ENABLE_COUNT = Output(Bool())
    val ACKOUT = Output(Bool())
  })
  
  // Constants (parameters from Verilog)
  val cc_nop = 1.U(2.W)
  val cc_enin = 1.U(2.W)
  val cc_intr = 2.U(2.W)
  val cc_ackin = 3.U(2.W)
  val out_norm = 1.U(2.W)
  
  // State enumeration
  val s_init :: s_wait :: s_enin :: s_enin_w :: s_intr :: s_intr_1 :: s_intr_w :: Nil = Enum(7)
  
  // State register
  val state = RegInit(s_init)
  
  // Output registers
  val CC_MUX = RegInit(0.U(2.W))
  val USCITE = RegInit(0.U(2.W))
  val ENABLE_COUNT = RegInit(false.B)
  val ACKOUT = RegInit(false.B)
  
  // Connect outputs
  io.CC_MUX := CC_MUX
  io.USCITE := USCITE
  io.ENABLE_COUNT := ENABLE_COUNT
  io.ACKOUT := ACKOUT
  
  // State machine logic
  when(io.CONT_EQL) {
    ACKOUT := false.B
    ENABLE_COUNT := false.B
  }.otherwise {
    ACKOUT := true.B
    ENABLE_COUNT := true.B
  }
  
  switch(state) {
    is(s_init) {
      CC_MUX := cc_enin
      USCITE := out_norm
      state := s_wait
    }
    is(s_wait) {
      when(io.EQL) {
        USCITE := 0.U
        CC_MUX := cc_ackin
        state := s_enin
      }.otherwise {
        USCITE := out_norm
        CC_MUX := cc_intr
        state := s_intr_1
      }
    }
    is(s_intr_1) {
      when(io.EQL) {
        USCITE := 0.U
        CC_MUX := cc_ackin
        state := s_intr
      }.otherwise {
        USCITE := out_norm
        CC_MUX := cc_enin
        state := s_wait
      }
    }
    is(s_enin) {
      when(io.EQL) {
        USCITE := 0.U
        CC_MUX := cc_ackin
        state := s_enin
      }.otherwise {
        USCITE := 1.U
        ACKOUT := true.B
        ENABLE_COUNT := true.B
        CC_MUX := cc_enin
        state := s_enin_w
      }
    }
    is(s_enin_w) {
      when(io.EQL) {
        USCITE := 1.U
        CC_MUX := cc_enin
        state := s_enin_w
      }.otherwise {
        USCITE := out_norm
        CC_MUX := cc_enin
        state := s_wait
      }
    }
    is(s_intr) {
      when(io.EQL) {
        USCITE := 0.U
        CC_MUX := cc_ackin
        state := s_intr
      }.otherwise {
        USCITE := 3.U
        CC_MUX := cc_intr
        state := s_intr_w
      }
    }
    is(s_intr_w) {
      when(io.EQL) {
        USCITE := 3.U
        CC_MUX := cc_intr
        state := s_intr_w
      }.otherwise {
        USCITE := out_norm
        CC_MUX := cc_enin
        state := s_wait
      }
    }
  }
}

object VerilogGenerator extends App {
  emitVerilog(new b06(), args)
}