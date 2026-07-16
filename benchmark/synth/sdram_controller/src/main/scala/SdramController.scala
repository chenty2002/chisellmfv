package withw.sdramcontroller

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxInline
import _root_.circt.stage.ChiselStage

import java.nio.file.{Files, Paths}

/** Exact source-level mutations retained for one Wit-HW task variant. */
final case class SdramControllerVariant(
    sourceStem: String,
    readNop1Code: Int = 0x11,
    resetBusyValue: Boolean = false,
    mainResetOnReadDisable: Boolean = false
) {
  require(readNop1Code >= 0 && readNop1Code < 32)
}

object SdramControllerVariants {
  val all: Seq[SdramControllerVariant] = Seq(
    SdramControllerVariant("sdram_controller.no_tri_state"),
    // Bug 1: READ_NOP1 aliases READ_ACT. The first READ_ACT case item wins,
    // so the read engine repeatedly re-enters the same encoded state.
    SdramControllerVariant("sdram_controller_buggy_1.no_tri_state", readNop1Code = 0x10),
    // Bug 2: the main state block writes busy high during reset.
    SdramControllerVariant("sdram_controller_buggy_2.no_tri_state", resetBusyValue = true),
    // Bug 3: only the main block changes reset condition; refresh_cnt still
    // uses rst_n exactly as in the source.
    SdramControllerVariant("sdram_controller_buggy_3.no_tri_state", mainResetOnReadDisable = true)
  )
}

/** Procedural next-state shell preserving Verilog's X/Z condition behavior. */
final class SdramNextStateIO extends Bundle {
  val state = Input(UInt(5.W))
  val state_cnt = Input(UInt(4.W))
  val refresh_cnt = Input(UInt(10.W))
  val rd_enable = Input(Bool())
  val wr_enable = Input(Bool())
  val command = Input(UInt(8.W))
  val next_state = Output(UInt(5.W))
  val command_nxt = Output(UInt(8.W))
  val state_cnt_nxt = Output(UInt(4.W))
}

/** Keep the source's procedural if/case semantics for unknown selectors.
  *
  * Native Chisel mux lowering propagates an X/Z rd_enable or wr_enable in
  * IDLE, whereas a Verilog procedural if treats that condition as not true.
  * This reviewed helper is the smallest observable four-state boundary; all
  * state, counters, address logic, and datapath storage remain in Chisel.
  */
final class SdramNextState(variant: SdramControllerVariant)
    extends BlackBox(Map("READ_NOP1_CODE" -> variant.readNop1Code))
    with HasBlackBoxInline {
  val io = IO(new SdramNextStateIO)

  setInline(
    "SdramNextState.sv",
    """
      |module SdramNextState #(
      |  parameter [4:0] READ_NOP1_CODE = 5'b10001
      |) (
      |  input  [4:0] state,
      |  input  [3:0] state_cnt,
      |  input  [9:0] refresh_cnt,
      |  input        rd_enable,
      |  input        wr_enable,
      |  input  [7:0] command,
      |  output reg [4:0] next_state,
      |  output reg [7:0] command_nxt,
      |  output reg [3:0] state_cnt_nxt
      |);
      |  localparam IDLE = 5'b00000;
      |  localparam INIT_NOP1 = 5'b01000, INIT_PRE1 = 5'b01001,
      |             INIT_NOP1_1 = 5'b00101, INIT_REF1 = 5'b01010,
      |             INIT_NOP2 = 5'b01011, INIT_REF2 = 5'b01100,
      |             INIT_NOP3 = 5'b01101, INIT_LOAD = 5'b01110;
      |  localparam REF_PRE = 5'b00001, REF_NOP1 = 5'b00010,
      |             REF_REF = 5'b00011;
      |  localparam READ_ACT = 5'b10000, READ_CAS = 5'b10010,
      |             READ_NOP2 = 5'b10011, READ_READ = 5'b10100;
      |  localparam WRIT_ACT = 5'b11000, WRIT_NOP1 = 5'b11001,
      |             WRIT_CAS = 5'b11010;
      |  localparam CMD_PALL = 8'b10010001, CMD_REF = 8'b10001000,
      |             CMD_NOP = 8'b10111000, CMD_MRS = 8'b1000000x,
      |             CMD_BACT = 8'b10011xxx, CMD_READ = 8'b10101xx1,
      |             CMD_WRIT = 8'b10100xx1;
      |
      |  always @(*) begin
      |    state_cnt_nxt = 4'd0;
      |    command_nxt = CMD_NOP;
      |    if (state == IDLE)
      |      if (refresh_cnt >= 10'd519) begin
      |        next_state = REF_PRE;
      |        command_nxt = CMD_PALL;
      |      end else if (rd_enable) begin
      |        next_state = READ_ACT;
      |        command_nxt = CMD_BACT;
      |      end else if (wr_enable) begin
      |        next_state = WRIT_ACT;
      |        command_nxt = CMD_BACT;
      |      end else begin
      |        next_state = IDLE;
      |      end
      |    else if (!state_cnt)
      |      case (state)
      |        INIT_NOP1: begin next_state = INIT_PRE1; command_nxt = CMD_PALL; end
      |        INIT_PRE1: next_state = INIT_NOP1_1;
      |        INIT_NOP1_1: begin next_state = INIT_REF1; command_nxt = CMD_REF; end
      |        INIT_REF1: begin next_state = INIT_NOP2; state_cnt_nxt = 4'd7; end
      |        INIT_NOP2: begin next_state = INIT_REF2; command_nxt = CMD_REF; end
      |        INIT_REF2: begin next_state = INIT_NOP3; state_cnt_nxt = 4'd7; end
      |        INIT_NOP3: begin next_state = INIT_LOAD; command_nxt = CMD_MRS; end
      |        INIT_LOAD: begin next_state = 5'b01111; state_cnt_nxt = 4'd1; end
      |        REF_PRE: next_state = REF_NOP1;
      |        REF_NOP1: begin next_state = REF_REF; command_nxt = CMD_REF; end
      |        REF_REF: begin next_state = 5'b00100; state_cnt_nxt = 4'd7; end
      |        WRIT_ACT: begin next_state = WRIT_NOP1; state_cnt_nxt = 4'd1; end
      |        WRIT_NOP1: begin next_state = WRIT_CAS; command_nxt = CMD_WRIT; end
      |        WRIT_CAS: begin next_state = 5'b11011; state_cnt_nxt = 4'd1; end
      |        READ_ACT: begin next_state = READ_NOP1_CODE; state_cnt_nxt = 4'd1; end
      |        READ_NOP1_CODE: begin next_state = READ_CAS; command_nxt = CMD_READ; end
      |        READ_CAS: begin next_state = READ_NOP2; state_cnt_nxt = 4'd1; end
      |        READ_NOP2: next_state = READ_READ;
      |        default: next_state = IDLE;
      |      endcase
      |    else begin
      |      next_state = state;
      |      command_nxt = command;
      |    end
      |  end
      |endmodule
      |""".stripMargin
  )
}

/** Synthesizable translation of the no-tri-state Wit-HW SDRAM controller.
  *
  * All state uses plain Reg intentionally. The Verilog declarations have no
  * power-on initialization, reset is synchronous and explicit, and rd_ready_r
  * is never assigned by reset. Buggy_3 also leaves the main state group
  * untouched by rst_n. Using RegInit would change those startup semantics.
  */
final class SdramController(variant: SdramControllerVariant) extends Module {
  override def desiredName: String = "sdram_controller"

  val wr_addr = IO(Input(UInt(24.W)))
  val wr_data = IO(Input(UInt(16.W)))
  val wr_enable = IO(Input(Bool()))
  val rd_addr = IO(Input(UInt(24.W)))
  val rd_data = IO(Output(UInt(16.W)))
  val rd_ready = IO(Output(Bool()))
  val rd_enable = IO(Input(Bool()))
  val busy = IO(Output(Bool()))
  val rst_n = IO(Input(Bool()))

  val addr = IO(Output(UInt(13.W)))
  val bank_addr = IO(Output(UInt(2.W)))
  val data_out = IO(Output(UInt(16.W)))
  val data_in = IO(Input(UInt(16.W)))
  val data_oe = IO(Output(Bool()))
  val clock_enable = IO(Output(Bool()))
  val cs_n = IO(Output(Bool()))
  val ras_n = IO(Output(Bool()))
  val cas_n = IO(Output(Bool()))
  val we_n = IO(Output(Bool()))
  val data_mask_low = IO(Output(Bool()))
  val data_mask_high = IO(Output(Bool()))

  private val initNop1 = 0x08.U(5.W)
  private val initLoad = 0x0e.U(5.W)
  private val readAct = 0x10.U(5.W)
  private val readCas = 0x12.U(5.W)
  private val readRead = 0x14.U(5.W)
  private val writeAct = 0x18.U(5.W)
  private val writeCas = 0x1a.U(5.W)

  private val cmdNop = "b10111000".U(8.W)

  val haddr_r = Reg(UInt(24.W))
  val wr_data_r = Reg(UInt(16.W))
  val rd_data_r = Reg(UInt(16.W))
  val busy_r = Reg(Bool())
  val rd_ready_r = Reg(Bool())
  val state_cnt = Reg(UInt(4.W))
  val refresh_cnt = Reg(UInt(10.W))
  val command = Reg(UInt(8.W))
  val state = Reg(UInt(5.W))

  val nextState = Module(new SdramNextState(variant))
  nextState.io.state := state
  nextState.io.state_cnt := state_cnt
  nextState.io.refresh_cnt := refresh_cnt
  nextState.io.rd_enable := rd_enable
  nextState.io.wr_enable := wr_enable
  nextState.io.command := command

  val mainReset = if (variant.mainResetOnReadDisable) !rd_enable else !rst_n
  when(mainReset) {
    state := initNop1
    command := cmdNop
    state_cnt := 15.U
    haddr_r := 0.U
    wr_data_r := 0.U
    rd_data_r := 0.U
    busy_r := variant.resetBusyValue.B
  }.otherwise {
    state := nextState.io.next_state
    command := nextState.io.command_nxt
    when(state_cnt === 0.U) {
      state_cnt := nextState.io.state_cnt_nxt
    }.otherwise {
      state_cnt := state_cnt - 1.U
    }
    when(wr_enable) {
      wr_data_r := wr_data
    }
    when(state === readRead) {
      rd_data_r := data_in
      rd_ready_r := true.B
    }.otherwise {
      rd_ready_r := false.B
    }
    busy_r := state(4)
    when(rd_enable) {
      haddr_r := rd_addr
    }.elsewhen(wr_enable) {
      haddr_r := wr_addr
    }
  }

  when(!rst_n) {
    refresh_cnt := 0.U
  }.elsewhen(state === 0x04.U) {
    refresh_cnt := 0.U
  }.otherwise {
    refresh_cnt := refresh_cnt +% 1.U
  }

  val bank_addr_r = WireDefault(0.U(2.W))
  val addr_r = WireDefault(0.U(13.W))
  when(state === readAct || state === writeAct) {
    bank_addr_r := haddr_r(23, 22)
    addr_r := haddr_r(21, 9)
  }.elsewhen(state === readCas || state === writeCas) {
    bank_addr_r := haddr_r(23, 22)
    addr_r := Cat(0.U(2.W), 1.U(1.W), 0.U(1.W), haddr_r(8, 0))
  }.elsewhen(state === initLoad) {
    addr_r := "b0001000110000".U(13.W)
  }

  rd_data := rd_data_r
  rd_ready := rd_ready_r
  busy := busy_r
  data_oe := state === writeCas
  data_out := wr_data_r
  data_mask_low := !state(4)
  data_mask_high := !state(4)
  bank_addr := Mux(state(4), bank_addr_r, command(2, 1))
  addr := Mux(state(4) || state === initLoad, addr_r, Cat(0.U(2.W), command(0), 0.U(10.W)))
  clock_enable := command(7)
  cs_n := command(6)
  ras_n := command(5)
  cas_n := command(4)
  we_n := command(3)
}

object EmitSdramControllerVariants extends App {
  val outputRoot = Paths.get(args.headOption.getOrElse("generated")).toAbsolutePath

  SdramControllerVariants.all.foreach { variant =>
    ChiselStage.emitSystemVerilogFile(
      new SdramController(variant),
      args = Array("--target-dir", outputRoot.resolve(variant.sourceStem).toString),
      firtoolOpts = Array(
        "--disable-all-randomization",
        "--strip-debug-info",
        "--lowering-options=disallowLocalVariables,disallowPackedArrays"
      )
    )

    // Keep the inline procedural helper in the analyzed deliverable while
    // removing only firtool's trailing non-Verilog resource-file payload.
    val topPath = outputRoot.resolve(variant.sourceStem).resolve("sdram_controller.sv")
    val emitted = Files.readString(topPath)
    val fileListMarker =
      "\n// ----- 8< ----- FILE \"firrtl_black_box_resource_files.f\" ----- 8< -----"
    val markerIndex = emitted.indexOf(fileListMarker)
    require(markerIndex >= 0, s"missing inline-resource marker in $topPath")
    Files.writeString(topPath, emitted.substring(0, markerIndex).stripTrailing + "\n")
  }
}
