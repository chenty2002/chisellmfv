package withw.i2c

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxInline
import _root_.circt.stage.ChiselStage

import java.nio.file.{Files, Paths}

/** Exact injected behavior selected for one complete Wit-HW I2C source cone. */
final case class I2CVariant(
    sourceStem: String,
    stickyBitCommandAck: Boolean = false,
    writePrescalerLowIntoHigh: Boolean = false,
    asyncPrescalerResetValue: Int = 0xffff,
    commandRegisterAddress: Int = 4,
    forceStartBClockLow: Boolean = false,
    issueReadAfterStartForWrite: Boolean = false
)

object I2CVariants {
  val all: Seq[I2CVariant] = Seq(
    I2CVariant("i2c_master_top.sync_reset"),
    // Bug 1: the bit-controller no longer clears cmd_ack by default.
    I2CVariant("i2c_master_bit_ctrl_buggy_1.sync_reset", stickyBitCommandAck = true),
    // Bug 2: a write to PRER low instead overwrites PRER high.
    I2CVariant("i2c_master_top_buggy_2.sync_reset", writePrescalerLowIntoHigh = true),
    // Bug 3: active-low arst_i resets PRER to 0 while wb_rst_i still resets it to 0xffff.
    I2CVariant("i2c_master_top_buggy_3.sync_reset", asyncPrescalerResetValue = 0x0000),
    // Bug 4: the command register accepts writes at address 0 instead of address 4.
    I2CVariant("i2c_master_top_buggy_4.sync_reset", commandRegisterAddress = 0),
    // Bug 5: START state B actively holds SCL low.
    I2CVariant("i2c_master_bit_ctrl_buggy_5.sync_reset", forceStartBClockLow = true),
    // Bug 6: after START, the write path sends a READ command.
    I2CVariant("i2c_master_byte_ctrl_buggy_6.sync_reset", issueReadAfterStartForWrite = true)
  )
}

private object I2CCommand {
  val Nop: UInt = "b0000".U(4.W)
  val Start: UInt = "b0001".U(4.W)
  val Stop: UInt = "b0010".U(4.W)
  val Write: UInt = "b0100".U(4.W)
  val Read: UInt = "b1000".U(4.W)
}

/** Reviewed procedural shell for the bit FSM's source full_case semantics. */
final class I2CBitFsmIO extends Bundle {
  val clock = Input(Clock())
  val rst = Input(Bool())
  val nReset = Input(Bool())
  val al = Input(Bool())
  val clk_en = Input(Bool())
  val cmd = Input(UInt(4.W))
  val din = Input(Bool())
  val c_state = Output(UInt(18.W))
  val cmd_ack = Output(Bool())
  val scl_oen = Output(Bool())
  val sda_oen = Output(Bool())
  val sda_chk = Output(Bool())
}

final class I2CBitFsm(variant: I2CVariant)
    extends BlackBox(
      Map(
        "STICKY_ACK" -> (if (variant.stickyBitCommandAck) 1 else 0),
        "FORCE_START_B_CLOCK_LOW" -> (if (variant.forceStartBClockLow) 1 else 0)
      )
    )
    with HasBlackBoxInline {
  val io = IO(new I2CBitFsmIO)

  setInline(
    "I2CBitFsm.sv",
    """
      |module I2CBitFsm #(
      |  parameter integer STICKY_ACK = 0,
      |  parameter integer FORCE_START_B_CLOCK_LOW = 0
      |) (
      |  input clock, rst, nReset, al, clk_en,
      |  input [3:0] cmd,
      |  input din,
      |  output reg [17:0] c_state,
      |  output reg cmd_ack, scl_oen, sda_oen, sda_chk
      |);
      |  localparam [3:0] CMD_START = 4'b0001, CMD_STOP = 4'b0010,
      |                   CMD_WRITE = 4'b0100, CMD_READ = 4'b1000;
      |  localparam [17:0] idle    = 18'b0_0000_0000_0000_0000,
      |                    start_a = 18'b0_0000_0000_0000_0001,
      |                    start_b = 18'b0_0000_0000_0000_0010,
      |                    start_c = 18'b0_0000_0000_0000_0100,
      |                    start_d = 18'b0_0000_0000_0000_1000,
      |                    start_e = 18'b0_0000_0000_0001_0000,
      |                    stop_a  = 18'b0_0000_0000_0010_0000,
      |                    stop_b  = 18'b0_0000_0000_0100_0000,
      |                    stop_c  = 18'b0_0000_0000_1000_0000,
      |                    stop_d  = 18'b0_0000_0001_0000_0000,
      |                    rd_a    = 18'b0_0000_0010_0000_0000,
      |                    rd_b    = 18'b0_0000_0100_0000_0000,
      |                    rd_c    = 18'b0_0000_1000_0000_0000,
      |                    rd_d    = 18'b0_0001_0000_0000_0000,
      |                    wr_a    = 18'b0_0010_0000_0000_0000,
      |                    wr_b    = 18'b0_0100_0000_0000_0000,
      |                    wr_c    = 18'b0_1000_0000_0000_0000,
      |                    wr_d    = 18'b1_0000_0000_0000_0000;
      |  always @(posedge clock)
      |    if (!nReset) begin
      |      c_state <= idle; cmd_ack <= 1'b0; scl_oen <= 1'b1;
      |      sda_oen <= 1'b1; sda_chk <= 1'b0;
      |    end else if (rst | al) begin
      |      c_state <= idle; cmd_ack <= 1'b0; scl_oen <= 1'b1;
      |      sda_oen <= 1'b1; sda_chk <= 1'b0;
      |    end else begin
      |      if (!STICKY_ACK) cmd_ack <= 1'b0;
      |      if (clk_en)
      |        case (c_state) // synopsys full_case parallel_case
      |          idle: begin
      |            case (cmd) // synopsys full_case parallel_case
      |              CMD_START: c_state <= start_a;
      |              CMD_STOP:  c_state <= stop_a;
      |              CMD_WRITE: c_state <= wr_a;
      |              CMD_READ:  c_state <= rd_a;
      |              default:   c_state <= idle;
      |            endcase
      |            scl_oen <= scl_oen; sda_oen <= sda_oen; sda_chk <= 1'b0;
      |          end
      |          start_a: begin
      |            c_state <= start_b; scl_oen <= scl_oen; sda_oen <= 1'b1; sda_chk <= 1'b0;
      |          end
      |          start_b: begin
      |            c_state <= start_c; scl_oen <= FORCE_START_B_CLOCK_LOW ? 1'b0 : 1'b1;
      |            sda_oen <= 1'b1; sda_chk <= 1'b0;
      |          end
      |          start_c: begin
      |            c_state <= start_d; scl_oen <= 1'b1; sda_oen <= 1'b0; sda_chk <= 1'b0;
      |          end
      |          start_d: begin
      |            c_state <= start_e; scl_oen <= 1'b1; sda_oen <= 1'b0; sda_chk <= 1'b0;
      |          end
      |          start_e: begin
      |            c_state <= idle; cmd_ack <= 1'b1; scl_oen <= 1'b0; sda_oen <= 1'b0; sda_chk <= 1'b0;
      |          end
      |          stop_a: begin
      |            c_state <= stop_b; scl_oen <= 1'b0; sda_oen <= 1'b0; sda_chk <= 1'b0;
      |          end
      |          stop_b: begin
      |            c_state <= stop_c; scl_oen <= 1'b1; sda_oen <= 1'b0; sda_chk <= 1'b0;
      |          end
      |          stop_c: begin
      |            c_state <= stop_d; scl_oen <= 1'b1; sda_oen <= 1'b0; sda_chk <= 1'b0;
      |          end
      |          stop_d: begin
      |            c_state <= idle; cmd_ack <= 1'b1; scl_oen <= 1'b1; sda_oen <= 1'b1; sda_chk <= 1'b0;
      |          end
      |          rd_a: begin
      |            c_state <= rd_b; scl_oen <= 1'b0; sda_oen <= 1'b1; sda_chk <= 1'b0;
      |          end
      |          rd_b: begin
      |            c_state <= rd_c; scl_oen <= 1'b1; sda_oen <= 1'b1; sda_chk <= 1'b0;
      |          end
      |          rd_c: begin
      |            c_state <= rd_d; scl_oen <= 1'b1; sda_oen <= 1'b1; sda_chk <= 1'b0;
      |          end
      |          rd_d: begin
      |            c_state <= idle; cmd_ack <= 1'b1; scl_oen <= 1'b0; sda_oen <= 1'b1; sda_chk <= 1'b0;
      |          end
      |          wr_a: begin
      |            c_state <= wr_b; scl_oen <= 1'b0; sda_oen <= din; sda_chk <= 1'b0;
      |          end
      |          wr_b: begin
      |            c_state <= wr_c; scl_oen <= 1'b1; sda_oen <= din; sda_chk <= 1'b0;
      |          end
      |          wr_c: begin
      |            c_state <= wr_d; scl_oen <= 1'b1; sda_oen <= din; sda_chk <= 1'b1;
      |          end
      |          wr_d: begin
      |            c_state <= idle; cmd_ack <= 1'b1; scl_oen <= 1'b0; sda_oen <= din; sda_chk <= 1'b0;
      |          end
      |        endcase
      |    end
      |endmodule
      |""".stripMargin
  )
}

/** Reviewed procedural shell for the byte FSM's source full_case semantics. */
final class I2CByteFsmIO extends Bundle {
  val clock = Input(Clock())
  val rst = Input(Bool())
  val nReset = Input(Bool())
  val i2c_al = Input(Bool())
  val start = Input(Bool())
  val stop = Input(Bool())
  val read = Input(Bool())
  val write = Input(Bool())
  val ack_in = Input(Bool())
  val sr = Input(UInt(8.W))
  val cnt_done = Input(Bool())
  val core_ack = Input(Bool())
  val core_rxd = Input(Bool())
  val core_cmd = Output(UInt(4.W))
  val core_txd = Output(Bool())
  val shift = Output(Bool())
  val ld = Output(Bool())
  val cmd_ack = Output(Bool())
  val c_state = Output(UInt(5.W))
  val ack_out = Output(Bool())
}

final class I2CByteFsm(variant: I2CVariant)
    extends BlackBox(Map("READ_AFTER_START_FOR_WRITE" -> (if (variant.issueReadAfterStartForWrite) 1 else 0)))
    with HasBlackBoxInline {
  val io = IO(new I2CByteFsmIO)

  setInline(
    "I2CByteFsm.sv",
    """
      |module I2CByteFsm #(parameter integer READ_AFTER_START_FOR_WRITE = 0) (
      |  input clock, rst, nReset, i2c_al,
      |  input start, stop, read, write, ack_in,
      |  input [7:0] sr,
      |  input cnt_done, core_ack, core_rxd,
      |  output reg [3:0] core_cmd,
      |  output reg core_txd, shift, ld, cmd_ack,
      |  output reg [4:0] c_state,
      |  output reg ack_out
      |);
      |  localparam [3:0] CMD_NOP = 4'b0000, CMD_START = 4'b0001,
      |                   CMD_STOP = 4'b0010, CMD_WRITE = 4'b0100, CMD_READ = 4'b1000;
      |  localparam [4:0] ST_IDLE = 5'b00000, ST_START = 5'b00001,
      |                   ST_READ = 5'b00010, ST_WRITE = 5'b00100,
      |                   ST_ACK = 5'b01000, ST_STOP = 5'b10000;
      |  wire go = (read | write | stop) & ~cmd_ack;
      |  always @(posedge clock)
      |    if (!nReset) begin
      |      core_cmd <= CMD_NOP; core_txd <= 1'b0; shift <= 1'b0; ld <= 1'b0;
      |      cmd_ack <= 1'b0; c_state <= ST_IDLE; ack_out <= 1'b0;
      |    end else if (rst | i2c_al) begin
      |      core_cmd <= CMD_NOP; core_txd <= 1'b0; shift <= 1'b0; ld <= 1'b0;
      |      cmd_ack <= 1'b0; c_state <= ST_IDLE; ack_out <= 1'b0;
      |    end else begin
      |      core_txd <= sr[7]; shift <= 1'b0; ld <= 1'b0; cmd_ack <= 1'b0;
      |      case (c_state) // synopsys full_case parallel_case
      |        ST_IDLE: if (go) begin
      |          if (start) begin c_state <= ST_START; core_cmd <= CMD_START; end
      |          else if (read) begin c_state <= ST_READ; core_cmd <= CMD_READ; end
      |          else if (write) begin c_state <= ST_WRITE; core_cmd <= CMD_WRITE; end
      |          else begin c_state <= ST_STOP; core_cmd <= CMD_STOP; end
      |          ld <= 1'b1;
      |        end
      |        ST_START: if (core_ack) begin
      |          if (read) begin c_state <= ST_READ; core_cmd <= CMD_READ; end
      |          else begin c_state <= ST_WRITE; core_cmd <= READ_AFTER_START_FOR_WRITE ? CMD_READ : CMD_WRITE; end
      |          ld <= 1'b1;
      |        end
      |        ST_WRITE: if (core_ack)
      |          if (cnt_done) begin c_state <= ST_ACK; core_cmd <= CMD_READ; end
      |          else begin c_state <= ST_WRITE; core_cmd <= CMD_WRITE; shift <= 1'b1; end
      |        ST_READ: if (core_ack) begin
      |          if (cnt_done) begin c_state <= ST_ACK; core_cmd <= CMD_WRITE; end
      |          else begin c_state <= ST_READ; core_cmd <= CMD_READ; end
      |          shift <= 1'b1; core_txd <= ack_in;
      |        end
      |        ST_ACK: if (core_ack) begin
      |          if (stop) begin c_state <= ST_STOP; core_cmd <= CMD_STOP; end
      |          else begin c_state <= ST_IDLE; core_cmd <= CMD_NOP; cmd_ack <= 1'b1; end
      |          ack_out <= core_rxd; core_txd <= 1'b1;
      |        end else core_txd <= ack_in;
      |        ST_STOP: if (core_ack) begin
      |          c_state <= ST_IDLE; core_cmd <= CMD_NOP; cmd_ack <= 1'b1;
      |        end
      |      endcase
      |    end
      |endmodule
      |""".stripMargin
  )
}

/** Plain-case clocked read mux preserving hold behavior for X/Z addresses. */
final class I2CReadMuxIO extends Bundle {
  val clock = Input(Clock())
  val address = Input(UInt(3.W))
  val d0 = Input(UInt(8.W)); val d1 = Input(UInt(8.W)); val d2 = Input(UInt(8.W))
  val d3 = Input(UInt(8.W)); val d4 = Input(UInt(8.W)); val d5 = Input(UInt(8.W))
  val d6 = Input(UInt(8.W))
  val value = Output(UInt(8.W))
}

final class I2CReadMux extends BlackBox with HasBlackBoxInline {
  val io = IO(new I2CReadMuxIO)
  setInline(
    "I2CReadMux.sv",
    """
      |module I2CReadMux(
      |  input clock, input [2:0] address,
      |  input [7:0] d0, d1, d2, d3, d4, d5, d6,
      |  output reg [7:0] value
      |);
      |  always @(posedge clock)
      |    case (address) // synopsys parallel_case
      |      3'b000: value <= d0;
      |      3'b001: value <= d1;
      |      3'b010: value <= d2;
      |      3'b011: value <= d3;
      |      3'b100: value <= d4;
      |      3'b101: value <= d5;
      |      3'b110: value <= d6;
      |      3'b111: value <= 8'h00;
      |    endcase
      |endmodule
      |""".stripMargin
  )
}

/** Translation of i2c_master_bit_ctrl.sync_reset.v.
  *
  * All state uses plain Reg because the Verilog declarations have no startup
  * initialization. Both nReset and rst are synchronous in the selected
  * sync_reset source, despite the legacy nReset comments.
  */
final class I2CMasterBitCtrl(variant: I2CVariant) extends Module {
  override def desiredName: String = "i2c_master_bit_ctrl"

  val rst = IO(Input(Bool()))
  val nReset = IO(Input(Bool()))
  val ena = IO(Input(Bool()))
  val clk_cnt = IO(Input(UInt(16.W)))
  val cmd = IO(Input(UInt(4.W)))
  val cmd_ack = IO(Output(Bool()))
  val busy = IO(Output(Bool()))
  val al = IO(Output(Bool()))
  val din = IO(Input(Bool()))
  val dout = IO(Output(Bool()))
  val scl_i = IO(Input(Bool()))
  val scl_o = IO(Output(Bool()))
  val scl_oen = IO(Output(Bool()))
  val sda_i = IO(Input(Bool()))
  val sda_o = IO(Output(Bool()))
  val sda_oen = IO(Output(Bool()))

  val cSCL = Reg(UInt(2.W)); cSCL.suggestName("cSCL")
  val cSDA = Reg(UInt(2.W)); cSDA.suggestName("cSDA")
  val fSCL = Reg(UInt(3.W)); fSCL.suggestName("fSCL")
  val fSDA = Reg(UInt(3.W)); fSDA.suggestName("fSDA")
  val sSCL = Reg(Bool()); sSCL.suggestName("sSCL")
  val sSDA = Reg(Bool()); sSDA.suggestName("sSDA")
  val dSCL = Reg(Bool()); dSCL.suggestName("dSCL")
  val dSDA = Reg(Bool()); dSDA.suggestName("dSDA")
  val dsclOen = Reg(Bool()); dsclOen.suggestName("dscl_oen")
  val clkEn = Reg(Bool()); clkEn.suggestName("clk_en")
  val slaveWait = Reg(Bool()); slaveWait.suggestName("slave_wait")
  val cnt = Reg(UInt(16.W)); cnt.suggestName("cnt")
  val filterCnt = Reg(UInt(14.W)); filterCnt.suggestName("filter_cnt")
  val staCondition = Reg(Bool()); staCondition.suggestName("sta_condition")
  val stoCondition = Reg(Bool()); stoCondition.suggestName("sto_condition")
  val cmdStop = Reg(Bool()); cmdStop.suggestName("cmd_stop")
  val busyReg = Reg(Bool()); busyReg.suggestName("busy_reg")
  val alReg = Reg(Bool()); alReg.suggestName("al_reg")
  val doutReg = Reg(Bool()); doutReg.suggestName("dout_reg")

  val bitFsm = Module(new I2CBitFsm(variant)).suggestName("bit_fsm")
  bitFsm.io.clock := clock
  bitFsm.io.rst := rst
  bitFsm.io.nReset := nReset
  bitFsm.io.al := alReg
  bitFsm.io.clk_en := clkEn
  bitFsm.io.cmd := cmd
  bitFsm.io.din := din
  val cState = bitFsm.io.c_state
  val sdaChk = bitFsm.io.sda_chk
  val cmdAckReg = bitFsm.io.cmd_ack
  val sclOenReg = bitFsm.io.scl_oen
  val sdaOenReg = bitFsm.io.sda_oen

  // These source registers are observably arbitrary before their first write.
  dontTouch(dsclOen)
  dontTouch(doutReg)

  dsclOen := sclOenReg
  when(!nReset) {
    slaveWait := false.B
  }.otherwise {
    slaveWait := (sclOenReg && !dsclOen && !sSCL) || (slaveWait && !sSCL)
  }

  val sclSync = dSCL && !sSCL && sclOenReg
  when(!nReset) {
    cnt := 0.U
    clkEn := true.B
  }.elsewhen(rst || !cnt.orR || !ena || sclSync) {
    cnt := clk_cnt
    clkEn := true.B
  }.elsewhen(slaveWait) {
    cnt := cnt
    clkEn := false.B
  }.otherwise {
    cnt := cnt -% 1.U
    clkEn := false.B
  }

  when(!nReset) {
    cSCL := 0.U
    cSDA := 0.U
  }.elsewhen(rst) {
    cSCL := 0.U
    cSDA := 0.U
  }.otherwise {
    cSCL := Cat(cSCL(0), scl_i)
    cSDA := Cat(cSDA(0), sda_i)
  }

  when(!nReset) {
    filterCnt := 0.U
  }.elsewhen(rst || !ena) {
    filterCnt := 0.U
  }.elsewhen(!filterCnt.orR) {
    filterCnt := clk_cnt >> 2
  }.otherwise {
    filterCnt := filterCnt -% 1.U
  }

  when(!nReset) {
    fSCL := "b111".U
    fSDA := "b111".U
  }.elsewhen(rst) {
    fSCL := "b111".U
    fSDA := "b111".U
  }.elsewhen(!filterCnt.orR) {
    fSCL := Cat(fSCL(1, 0), cSCL(1))
    fSDA := Cat(fSDA(1, 0), cSDA(1))
  }

  val filteredSCL = (fSCL(2) && fSCL(1)) || (fSCL(1) && fSCL(0)) || (fSCL(2) && fSCL(0))
  val filteredSDA = (fSDA(2) && fSDA(1)) || (fSDA(1) && fSDA(0)) || (fSDA(2) && fSDA(0))
  when(!nReset) {
    sSCL := true.B
    sSDA := true.B
    dSCL := true.B
    dSDA := true.B
  }.elsewhen(rst) {
    sSCL := true.B
    sSDA := true.B
    dSCL := true.B
    dSDA := true.B
  }.otherwise {
    sSCL := filteredSCL
    sSDA := filteredSDA
    dSCL := sSCL
    dSDA := sSDA
  }

  when(!nReset) {
    staCondition := false.B
    stoCondition := false.B
  }.elsewhen(rst) {
    staCondition := false.B
    stoCondition := false.B
  }.otherwise {
    staCondition := !sSDA && dSDA && sSCL
    stoCondition := sSDA && !dSDA && sSCL
  }

  when(!nReset) {
    busyReg := false.B
  }.elsewhen(rst) {
    busyReg := false.B
  }.otherwise {
    busyReg := (staCondition || busyReg) && !stoCondition
  }

  when(!nReset) {
    cmdStop := false.B
  }.elsewhen(rst) {
    cmdStop := false.B
  }.elsewhen(clkEn) {
    cmdStop := cmd === I2CCommand.Stop
  }

  when(!nReset) {
    alReg := false.B
  }.elsewhen(rst) {
    alReg := false.B
  }.otherwise {
    alReg := (sdaChk && !sSDA && sdaOenReg) || (cState.orR && stoCondition && !cmdStop)
  }

  when(sSCL && !dSCL) {
    doutReg := sSDA
  }

  cmd_ack := cmdAckReg
  busy := busyReg
  al := alReg
  dout := doutReg
  scl_o := false.B
  scl_oen := sclOenReg
  sda_o := false.B
  sda_oen := sdaOenReg
}

/** Translation of i2c_master_byte_ctrl.sync_reset.v. */
final class I2CMasterByteCtrl(variant: I2CVariant) extends Module {
  override def desiredName: String = "i2c_master_byte_ctrl"

  val rst = IO(Input(Bool()))
  val nReset = IO(Input(Bool()))
  val ena = IO(Input(Bool()))
  val clk_cnt = IO(Input(UInt(16.W)))
  val start = IO(Input(Bool()))
  val stop = IO(Input(Bool()))
  val read = IO(Input(Bool()))
  val write = IO(Input(Bool()))
  val ack_in = IO(Input(Bool()))
  val din = IO(Input(UInt(8.W)))
  val cmd_ack = IO(Output(Bool()))
  val ack_out = IO(Output(Bool()))
  val dout = IO(Output(UInt(8.W)))
  val i2c_busy = IO(Output(Bool()))
  val i2c_al = IO(Output(Bool()))
  val scl_i = IO(Input(Bool()))
  val scl_o = IO(Output(Bool()))
  val scl_oen = IO(Output(Bool()))
  val sda_i = IO(Input(Bool()))
  val sda_o = IO(Output(Bool()))
  val sda_oen = IO(Output(Bool()))

  val sr = Reg(UInt(8.W)); sr.suggestName("sr")
  val dcnt = Reg(UInt(3.W)); dcnt.suggestName("dcnt")

  val bitController = Module(new I2CMasterBitCtrl(variant)).suggestName("bit_controller")
  val byteFsm = Module(new I2CByteFsm(variant)).suggestName("byte_fsm")
  bitController.rst := rst
  bitController.nReset := nReset
  bitController.ena := ena
  bitController.clk_cnt := clk_cnt
  bitController.cmd := byteFsm.io.core_cmd
  bitController.din := byteFsm.io.core_txd
  bitController.scl_i := scl_i
  bitController.sda_i := sda_i

  val coreAck = bitController.cmd_ack
  val coreRxd = bitController.dout
  val cntDone = !dcnt.orR
  byteFsm.io.clock := clock
  byteFsm.io.rst := rst
  byteFsm.io.nReset := nReset
  byteFsm.io.i2c_al := bitController.al
  byteFsm.io.start := start
  byteFsm.io.stop := stop
  byteFsm.io.read := read
  byteFsm.io.write := write
  byteFsm.io.ack_in := ack_in
  byteFsm.io.sr := sr
  byteFsm.io.cnt_done := cntDone
  byteFsm.io.core_ack := coreAck
  byteFsm.io.core_rxd := coreRxd
  val shift = byteFsm.io.shift
  val ld = byteFsm.io.ld

  when(!nReset) {
    sr := 0.U
  }.elsewhen(rst) {
    sr := 0.U
  }.elsewhen(ld) {
    sr := din
  }.elsewhen(shift) {
    sr := Cat(sr(6, 0), coreRxd)
  }

  when(!nReset) {
    dcnt := 0.U
  }.elsewhen(rst) {
    dcnt := 0.U
  }.elsewhen(ld) {
    dcnt := 7.U
  }.elsewhen(shift) {
    dcnt := dcnt -% 1.U
  }

  cmd_ack := byteFsm.io.cmd_ack
  ack_out := byteFsm.io.ack_out
  dout := sr
  i2c_busy := bitController.busy
  i2c_al := bitController.al
  scl_o := bitController.scl_o
  scl_oen := bitController.scl_oen
  sda_o := bitController.sda_o
  sda_oen := bitController.sda_oen
}

/** Complete top-level translation of the selected synchronous-reset I2C cone. */
final class I2CMasterTop(variant: I2CVariant) extends Module {
  override def desiredName: String = "i2c_master_top"

  val wb_rst_i = IO(Input(Bool()))
  val arst_i = IO(Input(Bool()))
  val wb_adr_i = IO(Input(UInt(3.W)))
  val wb_dat_i = IO(Input(UInt(8.W)))
  val wb_dat_o = IO(Output(UInt(8.W)))
  val wb_we_i = IO(Input(Bool()))
  val wb_stb_i = IO(Input(Bool()))
  val wb_cyc_i = IO(Input(Bool()))
  val wb_ack_o = IO(Output(Bool()))
  val wb_inta_o = IO(Output(Bool()))
  val scl_pad_i = IO(Input(Bool()))
  val scl_pad_o = IO(Output(Bool()))
  val scl_padoen_o = IO(Output(Bool()))
  val sda_pad_i = IO(Input(Bool()))
  val sda_pad_o = IO(Output(Bool()))
  val sda_padoen_o = IO(Output(Bool()))

  val prer = Reg(UInt(16.W)); prer.suggestName("prer")
  val ctr = Reg(UInt(8.W)); ctr.suggestName("ctr")
  val txr = Reg(UInt(8.W)); txr.suggestName("txr")
  val cr = Reg(UInt(8.W)); cr.suggestName("cr")
  val rxack = Reg(Bool()); rxack.suggestName("rxack")
  val tip = Reg(Bool()); tip.suggestName("tip")
  val irqFlag = Reg(Bool()); irqFlag.suggestName("irq_flag")
  val alReg = Reg(Bool()); alReg.suggestName("al")
  val wbAckReg = Reg(Bool()); wbAckReg.suggestName("wb_ack_o_reg")
  val wbIntaReg = Reg(Bool()); wbIntaReg.suggestName("wb_inta_o_reg")

  dontTouch(wbAckReg)

  val rstI = arst_i
  val wbWacc = wb_we_i && wbAckReg
  val coreEn = ctr(7)
  val ien = ctr(6)
  val sta = cr(7)
  val sto = cr(6)
  val rd = cr(5)
  val wr = cr(4)
  val ack = cr(3)
  val iack = cr(0)

  val byteController = Module(new I2CMasterByteCtrl(variant)).suggestName("byte_controller")
  byteController.rst := wb_rst_i
  byteController.nReset := rstI
  byteController.ena := coreEn
  byteController.clk_cnt := prer
  byteController.start := sta
  byteController.stop := sto
  byteController.read := rd
  byteController.write := wr
  byteController.ack_in := ack
  byteController.din := txr
  byteController.scl_i := scl_pad_i
  byteController.sda_i := sda_pad_i

  val done = byteController.cmd_ack
  val irxack = byteController.ack_out
  val rxr = byteController.dout
  val i2cBusy = byteController.i2c_busy
  val i2cAl = byteController.i2c_al
  val sr = Cat(rxack, i2cBusy, alReg, 0.U(3.W), tip, irqFlag)

  val readMux = Module(new I2CReadMux).suggestName("read_mux")
  readMux.io.clock := clock
  readMux.io.address := wb_adr_i
  readMux.io.d0 := prer(7, 0)
  readMux.io.d1 := prer(15, 8)
  readMux.io.d2 := ctr
  readMux.io.d3 := rxr
  readMux.io.d4 := sr
  readMux.io.d5 := txr
  readMux.io.d6 := cr

  wbAckReg := wb_cyc_i && wb_stb_i && !wbAckReg

  when(!rstI) {
    prer := variant.asyncPrescalerResetValue.U(16.W)
    ctr := 0.U
    txr := 0.U
  }.elsewhen(wb_rst_i) {
    prer := "hffff".U
    ctr := 0.U
    txr := 0.U
  }.elsewhen(wbWacc) {
    switch(wb_adr_i) {
      is("b000".U) {
        if (variant.writePrescalerLowIntoHigh) {
          prer := Cat(wb_dat_i, prer(7, 0))
        } else {
          prer := Cat(prer(15, 8), wb_dat_i)
        }
      }
      is("b001".U) { prer := Cat(wb_dat_i, prer(7, 0)) }
      is("b010".U) { ctr := wb_dat_i }
      is("b011".U) { txr := wb_dat_i }
    }
  }

  when(!rstI) {
    cr := 0.U
  }.elsewhen(wb_rst_i) {
    cr := 0.U
  }.elsewhen(wbWacc) {
    when(coreEn && wb_adr_i === variant.commandRegisterAddress.U(3.W)) {
      cr := wb_dat_i
    }
  }.otherwise {
    cr := Cat(Mux(done || i2cAl, 0.U(4.W), cr(7, 4)), cr(3), 0.U(3.W))
  }

  when(!rstI) {
    alReg := false.B
    rxack := false.B
    tip := false.B
    irqFlag := false.B
  }.elsewhen(wb_rst_i) {
    alReg := false.B
    rxack := false.B
    tip := false.B
    irqFlag := false.B
  }.otherwise {
    alReg := i2cAl || (alReg && !sta)
    rxack := irxack
    tip := rd || wr
    irqFlag := (done || i2cAl || irqFlag) && !iack
  }

  when(!rstI) {
    wbIntaReg := false.B
  }.elsewhen(wb_rst_i) {
    wbIntaReg := false.B
  }.otherwise {
    wbIntaReg := irqFlag && ien
  }

  wb_dat_o := readMux.io.value
  wb_ack_o := wbAckReg
  wb_inta_o := wbIntaReg
  scl_pad_o := byteController.scl_o
  scl_padoen_o := byteController.scl_oen
  sda_pad_o := byteController.sda_o
  sda_padoen_o := byteController.sda_oen
}

object EmitI2CVariants extends App {
  val outputRoot = Paths.get(args.headOption.getOrElse("generated")).toAbsolutePath

  I2CVariants.all.foreach { variant =>
    val targetDir = outputRoot.resolve(variant.sourceStem)
    ChiselStage.emitSystemVerilogFile(
      new I2CMasterTop(variant),
      args = Array("--target-dir", targetDir.toString),
      firtoolOpts = Array(
        "--disable-all-randomization",
        "--strip-debug-info",
        "--lowering-options=disallowLocalVariables,disallowPackedArrays"
      )
    )

    // Retain the three reviewed inline modules while removing firtool's raw
    // file-list resource trailer from the final SystemVerilog deliverable.
    val topPath = targetDir.resolve("i2c_master_top.sv")
    val emitted = Files.readString(topPath)
    val fileListMarker =
      "\n// ----- 8< ----- FILE \"firrtl_black_box_resource_files.f\" ----- 8< -----"
    val markerIndex = emitted.indexOf(fileListMarker)
    require(markerIndex >= 0, s"missing inline-resource marker in $topPath")
    Files.writeString(topPath, emitted.substring(0, markerIndex).stripTrailing + "\n")
  }
}
