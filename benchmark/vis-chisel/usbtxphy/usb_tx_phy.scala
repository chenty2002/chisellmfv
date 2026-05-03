package llmverify
import chisel3._
import chisel3.util._

object TxState extends ChiselEnum {
  val IDLE, SOP, DATA, EOP1, EOP2, WAIT = Value
}

class usb_tx_phy extends Module {
  val io = IO(new Bundle {
    val fs_ce = Input(Bool())
    val phy_mode = Input(Bool())
    
    // Transceiver Interface
    val txdp = Output(Bool())
    val txdn = Output(Bool())
    val txoe = Output(Bool())
    
    // UTMI Interface
    val DataOut_i = Input(UInt(8.W))
    val TxValid_i = Input(Bool())
    val TxReady_o = Output(Bool())
  })
  
  ///////////////////////////////////////////////////////////////////
  //
  // Local Wires and Registers
  //
  
  val TxReady_o = RegInit(false.B)
  val state = RegInit(TxState.IDLE)
  val tx_ready = RegInit(false.B)
  val tx_ready_d = RegInit(false.B)
  val ld_sop_d = RegInit(false.B)
  val ld_data_d = RegInit(false.B)
  val ld_eop_d = RegInit(false.B)
  val tx_ip = RegInit(false.B)
  val tx_ip_sync = RegInit(false.B)
  val bit_cnt = RegInit(0.U(3.W))
  val hold_reg = RegInit(0.U(8.W))
  val sd_raw_o = RegInit(false.B)
  val data_done = RegInit(false.B)
  val sft_done = RegInit(false.B)
  val sft_done_r = RegInit(false.B)
  val ld_data = RegInit(false.B)
  val one_cnt = RegInit(0.U(3.W))
  val sd_bs_o = RegInit(false.B)
  val sd_nrzi_o = RegInit(true.B)
  val append_eop = RegInit(false.B)
  val append_eop_sync1 = RegInit(false.B)
  val append_eop_sync2 = RegInit(false.B)
  val append_eop_sync3 = RegInit(false.B)
  val txdp_reg = RegInit(true.B)
  val txdn_reg = RegInit(false.B)
  val txoe_r1 = RegInit(false.B)
  val txoe_r2 = RegInit(false.B)
  val txoe_reg = RegInit(true.B)
  
  ///////////////////////////////////////////////////////////////////
  //
  // Combinational Logic (define all wires first)
  //
  
  // Bit stuffer combinational logic
  val stuff = (one_cnt === 6.U)
  val hold = stuff
  val sft_done_e = sft_done && !sft_done_r
  val eop_done = append_eop_sync3
  
  ///////////////////////////////////////////////////////////////////
  //
  // Misc Logic
  //
  
  tx_ready := tx_ready_d
  
  when(!reset.asBool) {
    TxReady_o := false.B
  }.otherwise {
    TxReady_o := tx_ready_d & io.TxValid_i
  }
  
  ld_data := ld_data_d
  
  ///////////////////////////////////////////////////////////////////
  //
  // Transmit in progress indicator
  //
  
  when(!reset.asBool) {
    tx_ip := false.B
  }.elsewhen(ld_sop_d) {
    tx_ip := true.B
  }.elsewhen(eop_done) {
    tx_ip := false.B
  }
  
  when(!reset.asBool) {
    tx_ip_sync := false.B
  }.elsewhen(io.fs_ce) {
    tx_ip_sync := tx_ip
  }
  
  // data_done helps us to catch cases where TxValid drops due to
  // packet end and then gets re-asserted as a new packet starts.
  // We might not see this because we are still transmitting.
  // data_done should solve those cases ...
  when(!reset.asBool) {
    data_done := false.B
  }.elsewhen(io.TxValid_i && !tx_ip) {
    data_done := true.B
  }.elsewhen(!io.TxValid_i) {
    data_done := false.B
  }
  
  ///////////////////////////////////////////////////////////////////
  //
  // Shift Register
  //
  
  when(!reset.asBool) {
    bit_cnt := 0.U
  }.elsewhen(!tx_ip_sync) {
    bit_cnt := 0.U
  }.elsewhen(io.fs_ce && !hold) {
    bit_cnt := bit_cnt + 1.U
  }
  
  when(!tx_ip_sync) {
    sd_raw_o := false.B
  }.otherwise {
    switch(bit_cnt) {
      is(0.U) { sd_raw_o := hold_reg(0) }
      is(1.U) { sd_raw_o := hold_reg(1) }
      is(2.U) { sd_raw_o := hold_reg(2) }
      is(3.U) { sd_raw_o := hold_reg(3) }
      is(4.U) { sd_raw_o := hold_reg(4) }
      is(5.U) { sd_raw_o := hold_reg(5) }
      is(6.U) { sd_raw_o := hold_reg(6) }
      is(7.U) { sd_raw_o := hold_reg(7) }
    }
  }
  
  sft_done := !hold && (bit_cnt === 7.U)
  sft_done_r := sft_done
  
  // Out Data Hold Register
  when(ld_sop_d) {
    hold_reg := 0x80.U
  }.elsewhen(ld_data) {
    hold_reg := io.DataOut_i
  }
  
  ///////////////////////////////////////////////////////////////////
  //
  // Bit Stuffer
  //
  
  when(!reset.asBool) {
    one_cnt := 0.U
  }.elsewhen(!tx_ip_sync) {
    one_cnt := 0.U
  }.elsewhen(io.fs_ce) {
    when(!sd_raw_o || stuff) {
      one_cnt := 0.U
    }.otherwise {
      one_cnt := one_cnt + 1.U
    }
  }
  
  when(!reset.asBool) {
    sd_bs_o := false.B
  }.elsewhen(io.fs_ce) {
    when(!tx_ip_sync) {
      sd_bs_o := false.B
    }.elsewhen(stuff) {
      sd_bs_o := false.B
    }.otherwise {
      sd_bs_o := sd_raw_o
    }
  }
  
  ///////////////////////////////////////////////////////////////////
  //
  // NRZI Encoder
  //
  
  when(!reset.asBool) {
    sd_nrzi_o := true.B
  }.elsewhen(!tx_ip_sync || !txoe_r1) {
    sd_nrzi_o := true.B
  }.elsewhen(io.fs_ce) {
    when(sd_bs_o) {
      sd_nrzi_o := sd_nrzi_o
    }.otherwise {
      sd_nrzi_o := ~sd_nrzi_o
    }
  }
  
  ///////////////////////////////////////////////////////////////////
  //
  // EOP append logic
  //
  
  when(!reset.asBool) {
    append_eop := false.B
  }.elsewhen(ld_eop_d) {
    append_eop := true.B
  }.elsewhen(append_eop_sync2) {
    append_eop := false.B
  }
  
  when(!reset.asBool) {
    append_eop_sync1 := false.B
  }.elsewhen(io.fs_ce) {
    append_eop_sync1 := append_eop
  }
  
  when(!reset.asBool) {
    append_eop_sync2 := false.B
  }.elsewhen(io.fs_ce) {
    append_eop_sync2 := append_eop_sync1
  }
  
  when(!reset.asBool) {
    append_eop_sync3 := false.B
  }.elsewhen(io.fs_ce) {
    append_eop_sync3 := append_eop_sync2
  }
  
  ///////////////////////////////////////////////////////////////////
  //
  // Output Enable Logic
  //
  
  when(!reset.asBool) {
    txoe_r1 := false.B
  }.elsewhen(io.fs_ce) {
    txoe_r1 := tx_ip_sync
  }
  
  when(!reset.asBool) {
    txoe_r2 := false.B
  }.elsewhen(io.fs_ce) {
    txoe_r2 := txoe_r1
  }
  
  when(!reset.asBool) {
    txoe_reg := true.B
  }.elsewhen(io.fs_ce) {
    txoe_reg := !(txoe_r1 || txoe_r2)
  }
  
  ///////////////////////////////////////////////////////////////////
  //
  // Output Registers
  //
  
  when(!reset.asBool) {
    txdp_reg := true.B
  }.elsewhen(io.fs_ce) {
    when(io.phy_mode) {
      txdp_reg := !append_eop_sync3 && sd_nrzi_o
    }.otherwise {
      txdp_reg := sd_nrzi_o
    }
  }
  
  when(!reset.asBool) {
    txdn_reg := false.B
  }.elsewhen(io.fs_ce) {
    when(io.phy_mode) {
      txdn_reg := !append_eop_sync3 && ~sd_nrzi_o
    }.otherwise {
      txdn_reg := append_eop_sync3
    }
  }
  
  ///////////////////////////////////////////////////////////////////
  //
  // Tx state machine
  //
  
  when(!reset.asBool) {
    state := TxState.IDLE
  }.otherwise {
    tx_ready_d := false.B
    ld_sop_d := false.B
    ld_data_d := false.B
    ld_eop_d := false.B
    
    switch(state) {
      is(TxState.IDLE) {
        when(io.TxValid_i) {
          ld_sop_d := true.B
          state := TxState.SOP
        }
      }
      is(TxState.SOP) {
        when(sft_done_e) {
          tx_ready_d := true.B
          ld_data_d := true.B
          state := TxState.DATA
        }
      }
      is(TxState.DATA) {
        when(!data_done && sft_done_e) {
          ld_eop_d := true.B
          state := TxState.EOP1
        }
        when(data_done && sft_done_e) {
          tx_ready_d := true.B
          ld_data_d := true.B
        }
      }
      is(TxState.EOP1) {
        when(eop_done) {
          state := TxState.EOP2
        }
      }
      is(TxState.EOP2) {
        when(!eop_done && io.fs_ce) {
          state := TxState.WAIT
        }
      }
      is(TxState.WAIT) {
        when(io.fs_ce) {
          state := TxState.IDLE
        }
      }
    }
  }
  
  // Connect outputs
  io.txdp := txdp_reg
  io.txdn := txdn_reg
  io.txoe := txoe_reg
  io.TxReady_o := TxReady_o
}

object VerilogGenerator extends App {
  emitVerilog(new usb_tx_phy(), args)
}