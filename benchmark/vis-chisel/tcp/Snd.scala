package llmverify

import chisel3._
import chisel3.util._

// TCP Sender model
class TcpSnd extends Module {
  val io = IO(new Bundle {
    val rcv_ack = Input(Bool())
    val rcv_seq = Input(UInt(4.W))
    val rcv_buff = Input(UInt(4.W))
    val seg_val = Output(Bool())
    val seg_seq = Output(UInt(4.W))
    val seg_len = Output(UInt(4.W))
    // Debug outputs
    val debug_buf_full = Output(Bool())
    val debug_data_snd = Output(Bool())
  })
  
  // User Process
  val snd_user = Module(new SndUser())
  snd_user.io.buf_full := io.debug_buf_full
  val data_snd = snd_user.io.data_snd
  
  // TCP Send Process
  val sender = Module(new Sender())
  sender.io.data_snd := data_snd
  sender.io.rcv_ack := io.rcv_ack
  sender.io.rcv_seq := io.rcv_seq
  sender.io.rcv_buff := io.rcv_buff
  io.seg_val := sender.io.seg_val
  io.seg_seq := sender.io.seg_seq
  io.seg_len := sender.io.seg_len
  io.debug_buf_full := sender.io.buf_full
  
  // Debug outputs
  io.debug_data_snd := data_snd
}

// User Process
object UserSndStatus {
  def IDLE = 0.U(1.W)
  def SEND = 1.U(1.W)
}

class SndUser extends Module {
  val io = IO(new Bundle {
    val buf_full = Input(Bool())
    val data_snd = Output(Bool())
  })
  
  val state = RegInit(UserSndStatus.IDLE)
  
  // Simple pseudo-random for non-deterministic behavior
  val lfsr = RegInit(1.U(8.W))
  lfsr := (lfsr << 1) | ((lfsr >> 7) ^ (lfsr >> 5) ^ (lfsr >> 4) ^ (lfsr >> 3))
  val r_state = Mux(lfsr(0) === 0.U, UserSndStatus.IDLE, UserSndStatus.SEND)
  
  io.data_snd := Mux(state === UserSndStatus.IDLE, 0.U, 1.U)
  
  when(state === UserSndStatus.IDLE) {
    state := r_state
  }.elsewhen(state === UserSndStatus.SEND) {
    when(io.buf_full === 0.U) {
      state := r_state
    }.otherwise {
      state := UserSndStatus.IDLE
    }
  }
}

// TCP Send Module
object SendState {
  def SND_BUSY = 0.U(1.W)
  def SND_IDLE = 1.U(1.W)
}

class Sender extends Module {
  val io = IO(new Bundle {
    val buf_full = Output(Bool())
    val data_snd = Input(Bool())
    val rcv_ack = Input(Bool())
    val rcv_seq = Input(UInt(4.W))
    val rcv_buff = Input(UInt(4.W))
    val seg_val = Output(Bool())
    val seg_seq = Output(UInt(4.W))
    val seg_len = Output(UInt(4.W))
  })
  
  val MAX_SND = 15.U(4.W)
  
  // Registers
  val snd_una = RegInit(0.U(4.W))
  val snd_nxt = RegInit(0.U(4.W))
  val snd_wnd = RegInit(0.U(4.W))
  val rcv_wnd = RegInit(7.U(4.W))
  val send_state = RegInit(SendState.SND_IDLE)
  
  // Initialize with some randomness
  val lfsr_init = RegInit(1.U(8.W))
  when(reset.asBool) {
    snd_una := lfsr_init(3,0)
    snd_nxt := lfsr_init(7,4)
    snd_wnd := lfsr_init(3,0)
    send_state := Mux(lfsr_init(0) === 0.U, SendState.SND_BUSY, SendState.SND_IDLE)
  }
  
  // Buffer full logic
  io.buf_full := (snd_wnd === 7.U)
  
  // Update send window
  when(io.data_snd && (!io.buf_full)) {
    snd_wnd := snd_wnd + 1.U
  }
  
  // Calculate next sequence number mapping
  val map_nxt = Mux(snd_nxt < snd_una, snd_nxt + 8.U, snd_nxt)
  
  // Calculate segment length
  val seg_len_calc = ((snd_una + io.rcv_buff) - map_nxt)
  io.seg_len := Mux(io.rcv_buff < seg_len_calc, io.rcv_buff, seg_len_calc)
  
  // Random generation for non-deterministic behavior
  val lfsr = RegInit(1.U(8.W))
  lfsr := (lfsr << 1) | ((lfsr >> 7) ^ (lfsr >> 5) ^ (lfsr >> 4) ^ (lfsr >> 3))
  
  val r_seg_val = lfsr(0)
  val r_seg_seq = lfsr(3,0)
  val try_snd = lfsr(1)
  val try_rcv = lfsr(2)
  
  // Segment output logic
  io.seg_val := Mux(send_state === SendState.SND_BUSY, 0.U, 
                   Mux(try_snd && (io.seg_len > 0.U), 1.U, r_seg_val))
  io.seg_seq := Mux(send_state === SendState.SND_BUSY, r_seg_seq,
                   Mux(try_snd && (io.seg_len > 0.U), snd_nxt, r_seg_seq))
  
  // Send state machine
  when(send_state === SendState.SND_BUSY) {
    send_state := SendState.SND_IDLE
  }.elsewhen(send_state === SendState.SND_IDLE) {
    when(try_snd && (io.seg_len > 0.U)) {
      snd_nxt := (snd_nxt + io.seg_len) & 7.U
      send_state := SendState.SND_BUSY
    }
  }
  
  // Process acknowledgements
  when(try_rcv && io.rcv_ack) {
    when(io.rcv_seq > snd_una) {
      snd_una := io.rcv_seq
    }
    rcv_wnd := io.rcv_buff
  }
}

object SndVerilogGenerator extends App {
  emitVerilog(new TcpSnd(), args)
}