package llmverify

import chisel3._
import chisel3.util._

// Master-slave flip-flop with synchronous reset and enable
class MJ_S_FF_SNRE_D extends Module {
  val io = IO(new Bundle {
    val out = Output(Bool())
    val in = Input(Bool())
    val lenable = Input(Bool())
    val reset_l = Input(Bool())
  })
  
  val reg = RegInit(0.B)
  
  when(!io.reset_l) {
    reg := 0.B
  }.elsewhen(io.lenable) {
    reg := io.in
  }
  
  io.out := reg
}

// Flip-flop with synchronous reset and enable
class FF_SRE extends Module {
  val io = IO(new Bundle {
    val out = Output(Bool())
    val din = Input(Bool())
    val enable = Input(Bool())
    val reset_l = Input(Bool())
  })
  
  val ff = Module(new MJ_S_FF_SNRE_D())
  ff.io.in := io.din
  ff.io.lenable := io.enable
  ff.io.reset_l := io.reset_l
  
  io.out := ff.io.out
}

// Master-slave flip-flop with enable
class MJ_S_FF_SE_D extends Module {
  val io = IO(new Bundle {
    val out = Output(Bool())
    val in = Input(Bool())
    val lenable = Input(Bool())
  })
  
  val reg = RegInit(0.B)
  
  when(io.lenable) {
    reg := io.in
  }
  
  io.out := reg
}

// Single flip-flop with enable
class FF_SE extends Module {
  val io = IO(new Bundle {
    val out = Output(Bool())
    val din = Input(Bool())
    val enable = Input(Bool())
  })
  
  val ff = Module(new MJ_S_FF_SE_D())
  ff.io.in := io.din
  ff.io.lenable := io.enable
  
  io.out := ff.io.out
}

// 4-bit flip-flop with enable
class FF_SE_4 extends Module {
  val io = IO(new Bundle {
    val out = Output(UInt(4.W))
    val din = Input(UInt(4.W))
    val enable = Input(Bool())
  })
  
  val regs = RegInit(0.U(4.W))
  
  when(io.enable) {
    regs := io.din
  }
  
  io.out := regs
}

// BIU Data Path
class BIU_DPATH extends Module {
  val io = IO(new Bundle {
    val icu_addr = Input(UInt(32.W))
    val dcu_addr = Input(UInt(32.W))
    val dcu_dataout = Input(UInt(32.W))
    val biu_data = Output(UInt(32.W))
    val pj_addr = Output(UInt(32.W))
    val pj_data_in = Input(UInt(32.W))
    val pj_data_out = Output(UInt(32.W))
    val arb_select = Input(Bool())
  })
  
  io.pj_addr := Mux(io.arb_select, io.icu_addr, io.dcu_addr)
  io.pj_data_out := io.dcu_dataout
  io.biu_data := io.pj_data_in
}

// BIU Control
class BIU_CTL extends Module {
  val io = IO(new Bundle {
    val icu_req = Input(Bool())
    val icu_type = Input(UInt(4.W))
    val icu_size = Input(UInt(2.W))
    val biu_icu_ack = Output(UInt(2.W))
    val dcu_req = Input(Bool())
    val dcu_type = Input(UInt(4.W))
    val dcu_size = Input(UInt(2.W))
    val biu_dcu_ack = Output(UInt(2.W))
    val reset_l = Input(Bool())
    val pj_tv = Output(Bool())
    val pj_ack = Input(UInt(2.W))
    val pj_type = Output(UInt(4.W))
    val pj_size = Output(UInt(2.W))
    val arb_select = Output(Bool())
    val pj_ale = Output(Bool())
  })
  
  // State machine states - use UInt for simplicity
  val sIDLE = 0.U(5.W)
  val sREQ_ACTIVE = 1.U(5.W)
  val sFILL3 = 2.U(5.W)
  val sFILL2 = 3.U(5.W)
  val sFILL1 = 4.U(5.W)
  
  // Registers
  val arb_state = RegInit(sIDLE)
  
  // Combinational logic
  val arbiter_sel = io.icu_req && !io.dcu_req
  val arb_idle = (arb_state === sIDLE)
  
  // Arbiter select state register
  val arb_select_reg = Module(new FF_SRE())
  arb_select_reg.io.din := arbiter_sel
  arb_select_reg.io.enable := arb_idle
  arb_select_reg.io.reset_l := io.reset_l
  
  val arb_select = Mux(arb_idle, arbiter_sel, arb_select_reg.io.out)
  
  // Type state register
  val type_state_reg = Module(new FF_SE_4())
  type_state_reg.io.din := Mux(arb_select, io.icu_type, io.dcu_type)
  type_state_reg.io.enable := arb_idle
  
  val type_state = type_state_reg.io.out
  
  // Control signals
  val pj_tv = ((io.icu_req || io.dcu_req) && arb_idle) || (arb_state === sREQ_ACTIVE)
  val icu_tx = !type_state(2)
  val dcu_tx = type_state(2)
  
  io.biu_dcu_ack := Fill(2, dcu_tx) & io.pj_ack
  io.biu_icu_ack := Fill(2, icu_tx) & io.pj_ack
  io.pj_ale := !(pj_tv && arb_idle)
  
  // Muxes for pj_type and pj_size
  io.pj_size := Mux(arb_select, io.icu_size, io.dcu_size)
  io.pj_type := Mux(arb_select, io.icu_type, io.dcu_type)
  
  io.arb_select := arb_select
  io.pj_tv := pj_tv
  
  // Number of acks calculation
  val num_acks = Wire(UInt(3.W))
  num_acks(0) := type_state(1)
  num_acks(1) := 0.B
  num_acks(2) := (type_state(3) || (type_state(2) && !type_state(1))) || 
                 !(type_state(1) || type_state(2) || type_state(3))
  
  // State machine next state logic
  val normal_ack = io.pj_ack(0)
  val error_ack = io.pj_ack(1)
  
  // State machine next state logic using UInt
  val nextState = Wire(UInt(5.W))
  
  switch(arb_state) {
    is(sIDLE) {
      nextState := Mux(pj_tv, sREQ_ACTIVE, sIDLE)
    }
    is(sREQ_ACTIVE) {
      when(error_ack || (normal_ack && num_acks(0))) {
        nextState := sIDLE
      }.elsewhen(normal_ack && num_acks(2)) {
        nextState := sFILL3
      }.elsewhen(normal_ack && num_acks(1)) {
        nextState := sFILL1
      }.otherwise {
        nextState := sREQ_ACTIVE
      }
    }
    is(sFILL3) {
      when(error_ack) {
        nextState := sIDLE
      }.elsewhen(normal_ack) {
        nextState := sFILL2
      }.otherwise {
        nextState := sFILL3
      }
    }
    is(sFILL2) {
      when(error_ack) {
        nextState := sIDLE
      }.elsewhen(normal_ack) {
        nextState := sFILL1
      }.otherwise {
        nextState := sFILL2
      }
    }
    is(sFILL1) {
      nextState := Mux(normal_ack || error_ack, sIDLE, sFILL1)
    }
  }
  
  // State update
  when(!io.reset_l) {
    arb_state := sIDLE
  }.otherwise {
    arb_state := nextState
  }
}

// Top-level BIU module
class BIU extends Module {
  val io = IO(new Bundle {
    val icu_req = Input(Bool())
    val icu_addr = Input(UInt(32.W))
    val icu_type = Input(UInt(4.W))
    val icu_size = Input(UInt(2.W))
    val biu_icu_ack = Output(UInt(2.W))
    val biu_data = Output(UInt(32.W))
    val dcu_req = Input(Bool())
    val dcu_addr = Input(UInt(32.W))
    val dcu_type = Input(UInt(4.W))
    val dcu_size = Input(UInt(2.W))
    val dcu_dataout = Input(UInt(32.W))
    val biu_dcu_ack = Output(UInt(2.W))
    val reset_l = Input(Bool())
    val pj_addr = Output(UInt(30.W))
    val pj_data_out = Output(UInt(32.W))
    val pj_data_in = Input(UInt(32.W))
    val pj_tv = Output(Bool())
    val pj_size = Output(UInt(2.W))
    val pj_type = Output(UInt(4.W))
    val pj_ack = Input(UInt(2.W))
    val pj_ale = Output(Bool())
  })
  
  // Arbiter select signal
  val arb_select = Wire(Bool())
  
  // BIU Control instance
  val biu_ctl = Module(new BIU_CTL())
  biu_ctl.io.icu_req := io.icu_req
  biu_ctl.io.icu_type := io.icu_type
  biu_ctl.io.icu_size := io.icu_size
  io.biu_icu_ack := biu_ctl.io.biu_icu_ack
  biu_ctl.io.dcu_req := io.dcu_req
  biu_ctl.io.dcu_type := io.dcu_type
  biu_ctl.io.dcu_size := io.dcu_size
  io.biu_dcu_ack := biu_ctl.io.biu_dcu_ack
  biu_ctl.io.reset_l := io.reset_l
  io.pj_tv := biu_ctl.io.pj_tv
  io.pj_type := biu_ctl.io.pj_type
  io.pj_size := biu_ctl.io.pj_size
  biu_ctl.io.pj_ack := io.pj_ack
  arb_select := biu_ctl.io.arb_select
  io.pj_ale := biu_ctl.io.pj_ale
  
  // BIU Data Path instance
  val biu_dpath = Module(new BIU_DPATH())
  biu_dpath.io.icu_addr := io.icu_addr
  biu_dpath.io.dcu_addr := io.dcu_addr
  biu_dpath.io.dcu_dataout := io.dcu_dataout
  io.biu_data := biu_dpath.io.biu_data
  val pj_addr_int = biu_dpath.io.pj_addr
  biu_dpath.io.pj_data_in := io.pj_data_in
  io.pj_data_out := biu_dpath.io.pj_data_out
  biu_dpath.io.arb_select := arb_select
  
  // Output pj_addr (30 bits)
  io.pj_addr := pj_addr_int(29, 0)
}

object VerilogGenerator extends App {
  emitVerilog(new BIU(), args)
}