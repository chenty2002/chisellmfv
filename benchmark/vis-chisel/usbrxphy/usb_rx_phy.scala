package llmverify

import chisel3._
import chisel3.util._

// USB RX PHY States
object RxState {
  val FS_IDLE = 0.U(3.W)
  val K1 = 1.U(3.W)
  val J1 = 2.U(3.W)
  val K2 = 3.U(3.W)
  val J2 = 4.U(3.W)
  val K3 = 5.U(3.W)
  val J3 = 6.U(3.W)
  val K4 = 7.U(3.W)
}

class usb_rx_phy extends Module {
  val io = IO(new Bundle {
    val rst = Input(Bool())
    val fs_ce = Output(Bool())
    
    // Transceiver Interface
    val rxd = Input(Bool())
    val rxdp = Input(Bool())
    val rxdn = Input(Bool())
    
    // UTMI Interface
    val RxValid_o = Output(Bool())
    val RxActive_o = Output(Bool())
    val RxError_o = Output(Bool())
    val DataIn_o = Output(UInt(8.W))
    val RxEn_i = Input(Bool())
    val LineState = Output(UInt(2.W))
  })
  
  ///////////////////////////////////////////////////////////////////
  //
  // Local Wires and Registers
  //
  
  // Synchronization registers
  val rxd_t1 = RegNext(io.rxd, false.B)
  val rxd_s1 = RegNext(rxd_t1, false.B)
  val rxd_s = RegNext(rxd_s1, false.B)
  
  val rxdp_t1 = RegNext(io.rxdp, false.B)
  val rxdp_s1 = RegNext(rxdp_t1, false.B)
  val rxdp_s = RegNext(rxdp_s1, false.B)
  
  val rxdn_t1 = RegNext(io.rxdn, false.B)
  val rxdn_s1 = RegNext(rxdn_t1, false.B)
  val rxdn_s = RegNext(rxdn_s1, false.B)
  
  val synced_d = Wire(Bool())
  synced_d := false.B
  
  // Line state detection
  val k = Wire(Bool())
  val j = Wire(Bool())
  val se0 = Wire(Bool())
  k := !rxdp_s && rxdn_s
  j := rxdp_s && !rxdn_s
  se0 := !rxdp_s && !rxdn_s
  
  // Control registers
  val rx_en = RegNext(io.RxEn_i, false.B)
  val rx_active = RegInit(false.B)
  val bit_cnt = RegInit(0.U(3.W))
  val rx_valid1 = RegInit(false.B)
  val rx_valid = RegInit(false.B)
  val shift_en = RegInit(false.B)
  val sd_r = RegInit(false.B)
  val sd_nrzi = RegInit(false.B)
  val hold_reg = RegInit(0.U(8.W))
  val one_cnt = RegInit(0.U(3.W))
  
  // DPLL registers
  val dpll_state = RegInit(1.U(2.W))
  val fs_ce_d = RegInit(false.B)
  val fs_ce_reg = RegInit(false.B)
  val change = Wire(Bool())
  val rxdp_s1r = RegNext(rxdp_s1, false.B)
  val rxdn_s1r = RegNext(rxdn_s1, false.B)
  val lock_en = Wire(Bool())
  val fs_ce_r1 = RegInit(false.B)
  val fs_ce_r2 = RegInit(false.B)
  val fs_ce_r3 = RegInit(false.B)
  
  // Sync pattern FSM
  val fs_state = RegInit(RxState.FS_IDLE)
  val rx_valid_r = RegInit(false.B)
  
  ///////////////////////////////////////////////////////////////////
  //
  // Misc Logic
  //
  
  io.RxActive_o := rx_active
  io.RxValid_o := rx_valid
  io.RxError_o := false.B
  io.DataIn_o := hold_reg
  io.LineState := Cat(rxdp_s1, rxdn_s1)
  
  ///////////////////////////////////////////////////////////////////
  //
  // DPLL
  //
  
  // Allow locking only when we are receiving
  lock_en := rx_en
  
  // Edge detector
  change := (rxdp_s1r =/= rxdp_s1) || (rxdn_s1r =/= rxdn_s1)
  
  // DPLL FSM
  when(!io.rst) {
    dpll_state := 1.U
  }.otherwise {
    fs_ce_d := false.B
    switch(dpll_state) {
      is(0.U) {
        when(lock_en && change) {
          dpll_state := 0.U
        }.otherwise {
          dpll_state := 1.U
        }
      }
      is(1.U) {
        fs_ce_d := true.B
        when(lock_en && change) {
          dpll_state := 3.U
        }.otherwise {
          dpll_state := 2.U
        }
      }
      is(2.U) {
        when(lock_en && change) {
          dpll_state := 0.U
        }.otherwise {
          dpll_state := 3.U
        }
      }
      is(3.U) {
        when(lock_en && change) {
          dpll_state := 0.U
        }.otherwise {
          dpll_state := 0.U
        }
      }
    }
  }
  
  // Compensate for sync registers at the input
  fs_ce_r1 := fs_ce_d
  fs_ce_r2 := fs_ce_r1
  fs_ce_r3 := fs_ce_r2
  fs_ce_reg := fs_ce_r3
  io.fs_ce := fs_ce_reg
  
  ///////////////////////////////////////////////////////////////////
  //
  // Find Sync Pattern FSM
  //
  
  when(!io.rst) {
    fs_state := RxState.FS_IDLE
  }.otherwise {
    synced_d := false.B
    when(fs_ce_reg) {
      switch(fs_state) {
        is(RxState.FS_IDLE) {
          when(k && rx_en) {
            fs_state := RxState.K1
          }
        }
        is(RxState.K1) {
          when(j && rx_en) {
            fs_state := RxState.J1
          }.otherwise {
            fs_state := RxState.FS_IDLE
          }
        }
        is(RxState.J1) {
          when(k && rx_en) {
            fs_state := RxState.K2
          }.otherwise {
            fs_state := RxState.FS_IDLE
          }
        }
        is(RxState.K2) {
          when(j && rx_en) {
            fs_state := RxState.J2
          }.otherwise {
            fs_state := RxState.FS_IDLE
          }
        }
        is(RxState.J2) {
          when(k && rx_en) {
            fs_state := RxState.K3
          }.otherwise {
            fs_state := RxState.FS_IDLE
          }
        }
        is(RxState.K3) {
          when(j && rx_en) {
            fs_state := RxState.J3
          }.elsewhen(k && rx_en) {
            fs_state := RxState.K4 // Allow missing one J
          }.otherwise {
            fs_state := RxState.FS_IDLE
          }
        }
        is(RxState.J3) {
          when(k && rx_en) {
            fs_state := RxState.K4
          }.otherwise {
            fs_state := RxState.FS_IDLE
          }
        }
        is(RxState.K4) {
          when(k) {
            synced_d := true.B
          }
          fs_state := RxState.FS_IDLE
        }
      }
    }
  }
  
  ///////////////////////////////////////////////////////////////////
  //
  // Generate RxActive
  //
  
  when(!io.rst) {
    rx_active := false.B
  }.otherwise {
    when(synced_d && rx_en) {
      rx_active := true.B
    }.elsewhen(se0 && rx_valid_r) {
      rx_active := false.B
    }
  }
  
  when(rx_valid) {
    rx_valid_r := true.B
  }.elsewhen(fs_ce_reg) {
    rx_valid_r := false.B
  }
  
  ///////////////////////////////////////////////////////////////////
  //
  // NRZI Decoder
  //
  
  when(fs_ce_reg) {
    sd_r := rxd_s
  }
  
  when(!io.rst) {
    sd_nrzi := false.B
  }.otherwise {
    when(rx_active && fs_ce_reg) {
      sd_nrzi := !(rxd_s ^ sd_r)
    }
  }
  
  ///////////////////////////////////////////////////////////////////
  //
  // Bit Stuff Detect
  //
  
  val drop_bit = Wire(Bool())
  drop_bit := (one_cnt === 6.U)
  
  when(!io.rst) {
    one_cnt := 0.U
  }.otherwise {
    when(!shift_en) {
      one_cnt := 0.U
    }.elsewhen(fs_ce_reg) {
      when(!sd_nrzi || drop_bit) {
        one_cnt := 0.U
      }.otherwise {
        one_cnt := one_cnt + 1.U
      }
    }
  }
  
  ///////////////////////////////////////////////////////////////////
  //
  // Serial => Parallel converter
  //
  
  when(fs_ce_reg) {
    shift_en := synced_d || rx_active
  }
  
  when(fs_ce_reg && shift_en && !drop_bit) {
    hold_reg := Cat(sd_nrzi, hold_reg(7, 1))
  }
  
  ///////////////////////////////////////////////////////////////////
  //
  // Generate RxValid
  //
  
  when(!io.rst) {
    bit_cnt := 0.U
  }.otherwise {
    when(!shift_en) {
      bit_cnt := 0.U
    }.elsewhen(fs_ce_reg && !drop_bit) {
      bit_cnt := bit_cnt + 1.U
    }
  }
  
  when(!io.rst) {
    rx_valid1 := false.B
  }.otherwise {
    when(fs_ce_reg && !drop_bit && (bit_cnt === 7.U)) {
      rx_valid1 := true.B
    }.elsewhen(rx_valid1 && fs_ce_reg && !drop_bit) {
      rx_valid1 := false.B
    }
  }
  
  rx_valid := !drop_bit && rx_valid1 && fs_ce_reg
}

object VerilogGenerator extends App {
  emitVerilog(new usb_rx_phy(), args)
}