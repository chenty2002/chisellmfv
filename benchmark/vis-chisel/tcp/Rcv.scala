package llmverify

import chisel3._
import chisel3.util._

// TCP Receive Model
class TcpRcv extends Module {
  val io = IO(new Bundle {
    val seg_val = Input(Bool())
    val seg_seq = Input(UInt(4.W))
    val seg_len = Input(UInt(4.W))
    val rcv_ack = Output(Bool())
    val rcv_seq = Output(UInt(4.W))
    val rcv_buff = Output(UInt(4.W))
    // Debug outputs
    val debug_buf_empty = Output(Bool())
    val debug_data_rcv = Output(Bool())
  })
  
  // User Process
  val rcv_user = Module(new RcvUser())
  rcv_user.io.buf_empty := io.debug_buf_empty
  val data_rcv = rcv_user.io.data_rcv
  
  // TCP Receive Process
  val receiver = Module(new Receiver())
  receiver.io.data_rcv := data_rcv
  receiver.io.seg_val := io.seg_val
  receiver.io.seg_seq := io.seg_seq
  receiver.io.seg_len := io.seg_len
  io.rcv_ack := receiver.io.rcv_ack
  io.rcv_seq := receiver.io.rcv_seq
  io.rcv_buff := receiver.io.rcv_buff
  io.debug_buf_empty := receiver.io.buf_empty
  
  // Debug outputs
  io.debug_data_rcv := data_rcv
}

// User Process
object UserRcvStatus {
  def IDLE = 0.U(1.W)
  def READ = 1.U(1.W)
}

class RcvUser extends Module {
  val io = IO(new Bundle {
    val buf_empty = Input(Bool())
    val data_rcv = Output(Bool())
  })
  
  val state = RegInit(UserRcvStatus.IDLE)
  
  // Simple pseudo-random for non-deterministic behavior
  val lfsr = RegInit(1.U(8.W))
  lfsr := (lfsr << 1) | ((lfsr >> 7) ^ (lfsr >> 5) ^ (lfsr >> 4) ^ (lfsr >> 3))
  val r_state = Mux(lfsr(0) === 0.U, UserRcvStatus.IDLE, UserRcvStatus.READ)
  
  io.data_rcv := Mux(state === UserRcvStatus.IDLE, 0.U, 1.U)
  
  when(state === UserRcvStatus.IDLE) {
    when(io.buf_empty === 0.U) {
      state := r_state
    }.otherwise {
      state := UserRcvStatus.IDLE
    }
  }.elsewhen(state === UserRcvStatus.READ) {
    when(io.buf_empty === 0.U) {
      state := r_state
    }.otherwise {
      state := UserRcvStatus.IDLE
    }
  }
}

// TCP Receive Module
object AckState {
  def ACK_BUSY = 0.U(1.W)
  def ACK_IDLE = 1.U(1.W)
}

class Receiver extends Module {
  val io = IO(new Bundle {
    val buf_empty = Output(Bool())
    val data_rcv = Input(Bool())
    val seg_val = Input(Bool())
    val seg_seq = Input(UInt(4.W))
    val seg_len = Input(UInt(4.W))
    val rcv_ack = Output(Bool())
    val rcv_seq = Output(UInt(4.W))
    val rcv_buff = Output(UInt(4.W))
  })
  
  // Registers
  val rcv_nxt = RegInit(0.U(4.W))
  val rcv_wnd = RegInit(0.U(4.W))
  val ack_state = RegInit(AckState.ACK_IDLE)
  
  // Initialize with some randomness
  val lfsr_init = RegInit(1.U(8.W))
  when(reset.asBool) {
    rcv_nxt := lfsr_init(3,0)
    rcv_wnd := lfsr_init(7,4)
    ack_state := Mux(lfsr_init(0) === 0.U, AckState.ACK_BUSY, AckState.ACK_IDLE)
  }
  
  // Buffer empty logic
  io.buf_empty := (rcv_wnd === 0.U)
  
  // Random generation for non-deterministic behavior
  val lfsr = RegInit(1.U(8.W))
  lfsr := (lfsr << 1) | ((lfsr >> 7) ^ (lfsr >> 5) ^ (lfsr >> 4) ^ (lfsr >> 3))
  
  val try_rcv = lfsr(1)
  val try_ack = lfsr(2)
  val r_rcv_seq = lfsr(3,0)
  val r_rcv_buff = lfsr(7,4)
  
  // Receive Length Formula
  val seg_seq_plus_len = (io.seg_seq + io.seg_len) & 7.U
  val rcv_nxt_plus_wnd = (rcv_nxt + rcv_wnd) & 7.U
  val rcv_len = Mux((rcv_nxt + rcv_wnd) < (io.seg_seq + io.seg_len),
                    rcv_wnd, io.seg_len)
  
  // Update window for user reads
  when(io.data_rcv && (!io.buf_empty) && (rcv_wnd < 8.U)) {
    rcv_wnd := rcv_wnd + 1.U
  }
  
  // Receive segment and update window
  when(try_rcv && io.seg_val && (rcv_len > 0.U)) {
    rcv_wnd := rcv_wnd - rcv_len
  }
  
  // Update next sequence number
  when(try_rcv && io.seg_val && (rcv_len > 0.U)) {
    when((io.seg_seq <= rcv_nxt) &&
         ((rcv_nxt <= seg_seq_plus_len) &&
          (seg_seq_plus_len <= rcv_nxt_plus_wnd))) {
      rcv_nxt := (rcv_nxt + rcv_len) & 7.U
    }
  }
  
  // Acknowledge the sender
  io.rcv_ack := Mux(ack_state === AckState.ACK_BUSY, 0.U, 1.U)
  io.rcv_seq := Mux(ack_state === AckState.ACK_IDLE, rcv_nxt, r_rcv_seq)
  io.rcv_buff := Mux(ack_state === AckState.ACK_IDLE, rcv_wnd, r_rcv_buff)
  
  // Acknowledgment state machine
  when(ack_state === AckState.ACK_BUSY) {
    ack_state := AckState.ACK_IDLE
  }.elsewhen(ack_state === AckState.ACK_IDLE) {
    when(try_ack) {
      ack_state := AckState.ACK_BUSY
    }
  }
}

object RcvVerilogGenerator extends App {
  emitVerilog(new TcpRcv(), args)
}