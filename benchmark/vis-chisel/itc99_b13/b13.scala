package llmverify

import chisel3._
import chisel3.util._

class b13 extends Module {
  val io = IO(new Bundle {
    val eoc = Input(Bool())
    val soc = Output(Bool())
    val load_dato = Output(Bool())
    val add_mpx2 = Output(Bool())
    val canale = Output(UInt(4.W))
    val mux_en = Output(Bool())
    val data_in = Input(UInt(8.W))
    val dsr = Input(Bool())
    val error = Output(Bool())
    val data_out = Output(Bool())
  })

  // State machine definitions
  object State1 extends ChiselEnum {
    val GP001, GP010, GP011, GP100, GP100w, GP101, GP110, GP111 = Value
  }

  object State2 extends ChiselEnum {
    val GP01, GP10, GP11, GP11w = Value
  }

  object State3 extends ChiselEnum {
    val G_IDLE, G_LOAD, G_SEND, G_WAIT_END = Value
  }

  object Bit extends ChiselEnum {
    val START_BIT, STOP_BIT, BIT0, BIT1, BIT2, BIT3, BIT4, BIT5, BIT6, BIT7 = Value
  }

  // State registers
  val S1 = RegInit(State1.GP001)
  val S2 = RegInit(State2.GP01)
  val itfc_state = RegInit(State3.G_IDLE)
  val next_bit = RegInit(Bit.START_BIT)

  // Control registers
  val mpx = RegInit(false.B)
  val rdy = RegInit(false.B)
  val send_data = RegInit(false.B)
  val confirm = RegInit(false.B)
  val shot = RegInit(false.B)
  val send_en = RegInit(false.B)
  val tre = RegInit(false.B)

  // Data registers
  val out_reg = RegInit(0.U(8.W))
  val tx_end = RegInit(false.B)
  val send = RegInit(false.B)
  val load = RegInit(false.B)
  val tx_conta = RegInit(0.U(10.W))
  val conta_tmp = RegInit(0.U(4.W))

  // Output registers
  val socReg = RegInit(false.B)
  val load_datoReg = RegInit(false.B)
  val add_mpx2Reg = RegInit(false.B)
  val mux_enReg = RegInit(false.B)
  val errorReg = RegInit(false.B)
  val data_outReg = RegInit(false.B)
  val canaleReg = RegInit(0.U(4.W))

  // State machine S1
  switch(S1) {
    is(State1.GP001) {
      mux_enReg := true.B
      S1 := State1.GP010
    }
    is(State1.GP010) {
      S1 := State1.GP011
    }
    is(State1.GP011) {
      socReg := true.B
      S1 := State1.GP101
    }
    is(State1.GP101) {
      when(io.eoc) {
        S1 := State1.GP101
      }.otherwise {
        load_datoReg := true.B
        S1 := State1.GP110
        mux_enReg := false.B
      }
    }
    is(State1.GP110) {
      load_datoReg := false.B
      socReg := false.B
      conta_tmp := conta_tmp + 1.U
      when(conta_tmp === 8.U) {
        conta_tmp := 0.U
      }
      canaleReg := conta_tmp
      S1 := State1.GP111
    }
    is(State1.GP111) {
      send_data := true.B
      S1 := State1.GP100w
    }
    is(State1.GP100w) {
      S1 := State1.GP100
    }
    is(State1.GP100) {
      when(!rdy) {
        S1 := State1.GP100
      }.otherwise {
        S1 := State1.GP001
        send_data := false.B
      }
    }
  }

  // State machine S2
  switch(S2) {
    is(State2.GP01) {
      when(send_data) {
        rdy := true.B
        S2 := State2.GP10
      }.otherwise {
        S2 := State2.GP01
      }
    }
    is(State2.GP10) {
      shot := true.B
      S2 := State2.GP11
    }
    is(State2.GP11) {
      when(!confirm) {
        shot := false.B
        S2 := State2.GP11
      }.otherwise {
        when(!mpx) {
          add_mpx2Reg := true.B
          mpx := true.B
          S2 := State2.GP10
        }.otherwise {
          mpx := false.B
          rdy := false.B
          S2 := State2.GP11w
        }
      }
    }
    is(State2.GP11w) {
      S2 := State2.GP01
    }
  }

  // State machine itfc_state
  switch(itfc_state) {
    is(State3.G_IDLE) {
      when(shot) {
        load := true.B
        confirm := false.B
        itfc_state := State3.G_LOAD
      }.otherwise {
        confirm := false.B
        itfc_state := State3.G_IDLE
      }
    }
    is(State3.G_LOAD) {
      load := false.B
      send := true.B
      itfc_state := State3.G_SEND
    }
    is(State3.G_SEND) {
      send := false.B
      itfc_state := State3.G_WAIT_END
    }
    is(State3.G_WAIT_END) {
      when(tx_end) {
        confirm := true.B
        itfc_state := State3.G_IDLE
      }
    }
  }

  // UART transmission logic
  when(tx_end) {
    send_en := false.B
    tre := true.B
  }

  when(load) {
    when(!tre) {
      out_reg := io.data_in
      tre := true.B
      errorReg := false.B
    }.otherwise {
      errorReg := true.B
    }
  }

  when(send) {
    when(!tre || !io.dsr) {
      errorReg := true.B
    }.otherwise {
      errorReg := false.B
      send_en := true.B
    }
  }

  // Bit transmission logic
  val DelayTime = 104.U
  tx_end := false.B
  data_outReg := true.B

  when(send_en) {
    when(tx_conta > DelayTime) {
      switch(next_bit) {
        is(Bit.START_BIT) {
          data_outReg := false.B
          next_bit := Bit.BIT0
        }
        is(Bit.BIT0) {
          data_outReg := out_reg(7)
          next_bit := Bit.BIT1
        }
        is(Bit.BIT1) {
          data_outReg := out_reg(6)
          next_bit := Bit.BIT2
        }
        is(Bit.BIT2) {
          data_outReg := out_reg(5)
          next_bit := Bit.BIT3
        }
        is(Bit.BIT3) {
          data_outReg := out_reg(4)
          next_bit := Bit.BIT4
        }
        is(Bit.BIT4) {
          data_outReg := out_reg(3)
          next_bit := Bit.BIT5
        }
        is(Bit.BIT5) {
          data_outReg := out_reg(2)
          next_bit := Bit.BIT6
        }
        is(Bit.BIT6) {
          data_outReg := out_reg(1)
          next_bit := Bit.BIT7
        }
        is(Bit.BIT7) {
          data_outReg := out_reg(0)
          next_bit := Bit.STOP_BIT
        }
        is(Bit.STOP_BIT) {
          data_outReg := true.B
          next_bit := Bit.START_BIT
          tx_end := true.B
        }
      }
      tx_conta := 0.U
    }.otherwise {
      tx_conta := tx_conta + 1.U
    }
  }

  // Connect outputs
  io.soc := socReg
  io.load_dato := load_datoReg
  io.add_mpx2 := add_mpx2Reg
  io.canale := canaleReg
  io.mux_en := mux_enReg
  io.error := errorReg
  io.data_out := data_outReg
}

object VerilogGenerator extends App {
  emitVerilog(new b13(), args)
}