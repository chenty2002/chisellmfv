package llmverify

import chisel3._
import chisel3.util._

// Network state enumerations
object NwRcvStatus {
  def IDLE = 0.U(1.W)
}

object NwSndStatus {
  def IDLE = 0.U(1.W)
  def WRITE = 1.U(1.W)
}

class Network extends Module {
  val io = IO(new Bundle {
    val val_in = Input(Bool())
    val data1_in = Input(UInt(4.W))
    val data2_in = Input(UInt(4.W))
    val val_b = Output(Bool())
    val data1_b = Output(UInt(4.W))
    val data2_b = Output(UInt(4.W))
  })
  
  // Internal registers
  val val_A = RegInit(VecInit(Seq.fill(8)(0.U(8.W))))
  val data1_A = RegInit(VecInit(Seq.fill(8)(0.U(8.W))))
  val data2_A = RegInit(VecInit(Seq.fill(8)(0.U(8.W))))
  
  // Random generators for non-deterministic behavior
  val rnd_in_indx = Wire(UInt(3.W))
  val rnd_out_indx = Wire(UInt(3.W))
  val rnd_snd_data = Wire(Bool())
  val rnd_rcv_data = Wire(Bool())
  val rnd_r_val_b = Wire(Bool())
  val rnd_r_data1_b = Wire(UInt(4.W))
  val rnd_r_data2_b = Wire(UInt(4.W))
  
  // Simple pseudo-random generation
  val lfsr = RegInit(1.U(8.W))
  lfsr := (lfsr << 1) | ((lfsr >> 7) ^ (lfsr >> 5) ^ (lfsr >> 4) ^ (lfsr >> 3))
  
  rnd_in_indx := lfsr(2,0)
  rnd_out_indx := lfsr(5,3)
  rnd_snd_data := lfsr(0)
  rnd_rcv_data := lfsr(1)
  rnd_r_val_b := lfsr(2)
  rnd_r_data1_b := lfsr(6,3)
  rnd_r_data2_b := lfsr(7,4)
  
  // State registers
  val snd_state = RegInit(NwSndStatus.IDLE)
  val rcv_state = RegInit(NwRcvStatus.IDLE)
  
  // Output logic
  io.val_b := Mux((snd_state === NwSndStatus.IDLE) && (rnd_snd_data =/= 0.U), 0.U, rnd_r_val_b)
  io.data1_b := Mux((snd_state === NwSndStatus.IDLE) && (rnd_snd_data =/= 0.U), 0.U, rnd_r_data1_b)
  io.data2_b := Mux((snd_state === NwSndStatus.IDLE) && (rnd_snd_data =/= 0.U), 0.U, rnd_r_data2_b)
  
  // Sender state machine
  when(snd_state === NwSndStatus.IDLE) {
    when(rnd_snd_data === 0.U) {
      snd_state := NwSndStatus.IDLE
    }.elsewhen(rnd_snd_data =/= 0.U) {
      snd_state := NwSndStatus.WRITE
    }
  }.elsewhen(snd_state === NwSndStatus.WRITE) {
    snd_state := NwSndStatus.IDLE
    val_A := VecInit(Seq.fill(8)(0.U(8.W)))
  }
  
  // Receiver state machine
  when(rcv_state === NwRcvStatus.IDLE) {
    when(rnd_rcv_data === 0.U) {
      rcv_state := NwRcvStatus.IDLE
    }.elsewhen(rnd_rcv_data =/= 0.U) {
      when(io.val_in === 0.U) {
        rcv_state := NwRcvStatus.IDLE
      }.otherwise {
        rcv_state := NwRcvStatus.IDLE
        data1_A := VecInit(Seq.fill(8)(0.U(8.W)))
        data2_A := VecInit(Seq.fill(8)(0.U(8.W)))
      }
    }
  }
}

object NetworkVerilogGenerator extends App {
  emitVerilog(new Network(), args)
}