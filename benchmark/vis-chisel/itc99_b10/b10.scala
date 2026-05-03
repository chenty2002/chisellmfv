package llmverify

import chisel3._
import chisel3.util._

class b10 extends Module {
  val io = IO(new Bundle {
    val r_button = Input(Bool())
    val g_button = Input(Bool())
    val key = Input(Bool())
    val start = Input(Bool())
    val test = Input(Bool())
    val cts = Output(Bool())
    val ctr = Output(Bool())
    val rts = Input(Bool())
    val rtr = Input(Bool())
    val v_in = Input(UInt(4.W))
    val v_out = Output(UInt(4.W))
  })

  // State enumeration
  val sSTARTUP :: sSTANDBY :: sGET_IN :: sSTART_TX :: sSEND :: sTX_2_RX :: sRECEIVE :: sRX_2_TX :: sEND_TX :: sTEST_1 :: sTEST_2 :: Nil = Enum(11)
  
  // State register
  val stato = RegInit(sSTARTUP)
  
  // Registers - use Bool for single-bit signals
  val voto0 = RegInit(false.B)
  val voto1 = RegInit(false.B)
  val voto2 = RegInit(false.B)
  val voto3 = RegInit(false.B)
  val sign = RegInit(0.U(4.W))
  val last_g = RegInit(false.B)
  val last_r = RegInit(false.B)
  val cts = RegInit(false.B)
  val ctr = RegInit(false.B)
  val v_out = RegInit(0.U(4.W))
  
  // State machine logic
  switch(stato) {
    is(sSTARTUP) {
      voto0 := false.B
      voto1 := false.B
      voto2 := false.B
      voto3 := false.B
      cts := false.B
      ctr := false.B
      when(!io.test) {
        sign := 0.U
        stato := sTEST_1
      }.otherwise {
        stato := sSTANDBY
      }
    }
    
    is(sSTANDBY) {
      when(io.start) {
        voto0 := false.B
        voto1 := false.B
        voto2 := false.B
        voto3 := false.B
        stato := sGET_IN
      }
      cts := io.rtr
    }
    
    is(sGET_IN) {
      when(!io.start) {
        stato := sSTART_TX
      }.elsewhen(io.key) {
        voto0 := io.key
        when((io.g_button ^ last_g) & io.g_button) {
          voto1 := ~voto1
        }
        when((io.r_button ^ last_r) & io.r_button) {
          voto2 := ~voto2
        }
        last_g := io.g_button
        last_r := io.r_button
      }.otherwise {
        voto0 := false.B
        voto1 := false.B
        voto2 := false.B
        voto3 := false.B
      }
    }
    
    is(sSTART_TX) {
      voto3 := voto0 ^ voto1 ^ voto2
      stato := sSEND
      voto0 := false.B
    }
    
    is(sSEND) {
      when(io.rtr) {
        v_out := Cat(voto3, voto2, voto1, voto0)
        cts := true.B
        when(!voto0 && voto1 && voto2 && !voto3) {
          stato := sEND_TX
        }.otherwise {
          stato := sTX_2_RX
        }
      }
    }
    
    is(sTX_2_RX) {
      when(!io.rts) {
        ctr := true.B
        stato := sRECEIVE
      }
    }
    
    is(sRECEIVE) {
      when(io.rts) {
        val v_in_bits = io.v_in
        voto3 := v_in_bits(3).asBool
        voto2 := v_in_bits(2).asBool
        voto1 := v_in_bits(1).asBool
        voto0 := v_in_bits(0).asBool
        ctr := false.B
        stato := sRX_2_TX
      }
    }
    
    is(sRX_2_TX) {
      when(!io.rtr) {
        cts := false.B
        stato := sSEND
      }
    }
    
    is(sEND_TX) {
      when(!io.rtr) {
        cts := false.B
        stato := sSTANDBY
      }
    }
    
    is(sTEST_1) {
      val v_in_bits = io.v_in
      voto3 := v_in_bits(3).asBool
      voto2 := v_in_bits(2).asBool
      voto1 := v_in_bits(1).asBool
      voto0 := v_in_bits(0).asBool
      sign := "b1000".U
      when(voto0 && voto1 && voto2 && voto3) {
        stato := sTEST_2
      }
    }
    
    is(sTEST_2) {
      voto0 := ~sign(0).asBool
      voto0 := sign(1).asBool  // probably buggy as in original
      voto0 := sign(2).asBool
      voto0 := ~sign(3).asBool
      stato := sSEND
    }
  }
  
  // Connect outputs
  io.cts := cts
  io.ctr := ctr
  io.v_out := v_out
}

object VerilogGenerator extends App {
  emitVerilog(new b10(), args)
}