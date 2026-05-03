package llmverify

import chisel3._
import chisel3.util._

class am2910 extends Module {
  val io = IO(new Bundle {
    val I = Input(UInt(4.W))
    val CCEN_BAR = Input(Bool())
    val CC_BAR = Input(Bool())
    val RLD_BAR = Input(Bool())
    val CI = Input(Bool())
    val OEbar = Input(Bool())
    val D = Input(UInt(12.W))
    val Y = Output(UInt(12.W))
    val PL_BAR = Output(Bool())
    val VECT_BAR = Output(Bool())
    val MAP_BAR = Output(Bool())
    val FULL_BAR = Output(Bool())
    
    // Additional outputs to preserve internal signals
    val RE_debug = Output(UInt(12.W))
    val uPC_debug = Output(UInt(12.W))
    val sp_debug = Output(UInt(3.W))
  })

  // Initialize registers
  val sp = RegInit(0.U(3.W))
  val RE = RegInit(0.U(12.W))
  val uPC = RegInit(0.U(12.W))
  val reg_file = RegInit(VecInit(Seq.fill(6)(0.U(12.W))))

  // Wire signals
  val CI_ext = Wire(UInt(12.W))
  val Y_temp = Wire(UInt(12.W))
  val write_address = Wire(UInt(3.W))
  val fail = Wire(Bool())
  val Rzero_bar = Wire(Bool())
  
  // Control signals
  val R_sel = Wire(Bool())
  val D_sel = Wire(Bool())
  val uPC_sel = Wire(Bool())
  val stack_sel = Wire(Bool())
  val decr = Wire(Bool())
  val load = Wire(Bool())
  val clear = Wire(Bool())
  val push = Wire(Bool())
  val pop = Wire(Bool())

  // CI_ext assignment
  CI_ext := Cat(0.U(11.W), io.CI)

  // Rzero_bar assignment
  Rzero_bar := RE.orR

  // fail signal
  fail := io.CC_BAR & ~io.CCEN_BAR

  // Control signal logic
  D_sel := (io.I === 2.U) |
           (Rzero_bar & (io.I === 9.U)) |
           (~Rzero_bar & fail & (io.I === 15.U)) |
           (~fail & ((io.I === 1.U) | (io.I === 3.U) | (io.I === 5.U) | (io.I === 7.U) |
                     (io.I === 11.U)))

  uPC_sel := (io.I === 4.U) | (io.I === 12.U) | (io.I === 14.U) |
             (fail & ((io.I === 1.U) | (io.I === 3.U) | (io.I === 6.U) | (io.I === 10.U) |
                      (io.I === 11.U) | (io.I === 14.U))) |
             (~Rzero_bar & ((io.I === 8.U) | (io.I === 9.U))) |
             (~fail & ((io.I === 15.U) | (io.I === 13.U)))

  stack_sel := (Rzero_bar & (io.I === 8.U)) |
               (~fail & (io.I === 10.U)) |
               (fail & (io.I === 13.U)) |
               (Rzero_bar & fail & (io.I === 15.U))

  R_sel := fail & ((io.I === 5.U) | (io.I === 7.U))

  push := (~fail & (io.I === 1.U)) | (io.I === 4.U) | (io.I === 5.U)

  pop := (~fail & ((io.I === 10.U) | (io.I === 11.U) | (io.I === 13.U) | (io.I === 15.U))) |
         (~Rzero_bar & ((io.I === 8.U) | (io.I === 15.U)))

  load := (io.I === 12.U) | (~fail & (io.I === 4.U))

  decr := Rzero_bar & ((io.I === 8.U) | (io.I === 9.U) | (io.I === 15.U))

  clear := io.I === 0.U

  // Y_temp multiplexer
  Y_temp := Mux(R_sel, RE,
           Mux(D_sel, io.D,
           Mux(uPC_sel, uPC,
           Mux(stack_sel, reg_file(sp),
           0.U(12.W)))))

  // Output assignments
  io.Y := Y_temp
  io.MAP_BAR := (io.I === 2.U)
  io.VECT_BAR := (io.I === 6.U)
  io.PL_BAR := (io.I === 2.U) | (io.I === 6.U)
  io.FULL_BAR := (sp === 5.U)

  // Debug outputs
  io.RE_debug := RE
  io.uPC_debug := uPC
  io.sp_debug := sp

  // write_address logic
  write_address := Mux(sp =/= 5.U, sp + 1.U, sp)

  // Sequential logic
  when(load | ~io.RLD_BAR) {
    RE := io.D
  }.elsewhen(decr & io.RLD_BAR) {
    RE := RE - 1.U
  }

  when(clear) {
    uPC := 0.U
  }.otherwise {
    uPC := Y_temp + CI_ext
  }

  when(pop && (sp =/= 0.U)) {
    sp := sp - 1.U
  }.elsewhen(push && (sp =/= 5.U)) {
    sp := sp + 1.U
  }.elsewhen(clear) {
    sp := 0.U
  }

  when(push) {
    reg_file(write_address) := uPC
  }
}

object VerilogGenerator extends App {
  emitVerilog(new am2910(), args)
}