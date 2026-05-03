package llmverify

import chisel3._
import chisel3.util._

// Top Level TCP Connection Model
class TCP extends Module {
  val io = IO(new Bundle {
    // Debug outputs to preserve the design
    val debug_seg_val = Output(Bool())
    val debug_seg_seq = Output(UInt(4.W))
    val debug_seg_len = Output(UInt(4.W))
    val debug_rcv_ack = Output(Bool())
    val debug_rcv_seq = Output(UInt(4.W))
    val debug_rcv_buff = Output(UInt(4.W))
    val debug_seg_val_b = Output(Bool())
    val debug_seg_seq_b = Output(UInt(4.W))
    val debug_seg_len_b = Output(UInt(4.W))
    val debug_rcv_ack_b = Output(Bool())
    val debug_rcv_seq_b = Output(UInt(4.W))
    val debug_rcv_buff_b = Output(UInt(4.W))
  })
  
  // Internal signals
  val seg_val = Wire(Bool())
  val seg_seq = Wire(UInt(4.W))
  val seg_len = Wire(UInt(4.W))
  val rcv_ack = Wire(Bool())
  val rcv_seq = Wire(UInt(4.W))
  val rcv_buff = Wire(UInt(4.W))
  
  val seg_val_b = Wire(Bool())
  val seg_seq_b = Wire(UInt(4.W))
  val seg_len_b = Wire(UInt(4.W))
  val rcv_ack_b = Wire(Bool())
  val rcv_seq_b = Wire(UInt(4.W))
  val rcv_buff_b = Wire(UInt(4.W))
  
  // TCP Sender
  val tcp_snd = Module(new TcpSnd())
  tcp_snd.io.rcv_ack := rcv_ack_b
  tcp_snd.io.rcv_seq := rcv_seq_b
  tcp_snd.io.rcv_buff := rcv_buff_b
  seg_val := tcp_snd.io.seg_val
  seg_seq := tcp_snd.io.seg_seq
  seg_len := tcp_snd.io.seg_len
  
  // Network
  val dual_net = Module(new DualNet())
  dual_net.io.F_val := seg_val
  dual_net.io.F_data1 := seg_seq
  dual_net.io.F_data2 := seg_len
  seg_val_b := dual_net.io.F_val_b
  seg_seq_b := dual_net.io.F_data1_b
  seg_len_b := dual_net.io.F_data2_b
  dual_net.io.R_val := rcv_ack
  dual_net.io.R_data1 := rcv_seq
  dual_net.io.R_data2 := rcv_buff
  rcv_ack_b := dual_net.io.R_val_b
  rcv_seq_b := dual_net.io.R_data1_b
  rcv_buff_b := dual_net.io.R_data2_b
  
  // TCP Receiver
  val tcp_rcv = Module(new TcpRcv())
  tcp_rcv.io.seg_val := seg_val_b
  tcp_rcv.io.seg_seq := seg_seq_b
  tcp_rcv.io.seg_len := seg_len_b
  rcv_ack := tcp_rcv.io.rcv_ack
  rcv_seq := tcp_rcv.io.rcv_seq
  rcv_buff := tcp_rcv.io.rcv_buff
  
  // Connect debug outputs
  io.debug_seg_val := seg_val
  io.debug_seg_seq := seg_seq
  io.debug_seg_len := seg_len
  io.debug_rcv_ack := rcv_ack
  io.debug_rcv_seq := rcv_seq
  io.debug_rcv_buff := rcv_buff
  io.debug_seg_val_b := seg_val_b
  io.debug_seg_seq_b := seg_seq_b
  io.debug_seg_len_b := seg_len_b
  io.debug_rcv_ack_b := rcv_ack_b
  io.debug_rcv_seq_b := rcv_seq_b
  io.debug_rcv_buff_b := rcv_buff_b
}

// TCP Network model 
class DualNet extends Module {
  val io = IO(new Bundle {
    val F_val = Input(Bool())
    val F_data1 = Input(UInt(4.W))
    val F_data2 = Input(UInt(4.W))
    val F_val_b = Output(Bool())
    val F_data1_b = Output(UInt(4.W))
    val F_data2_b = Output(UInt(4.W))
    val R_val = Input(Bool())
    val R_data1 = Input(UInt(4.W))
    val R_data2 = Input(UInt(4.W))
    val R_val_b = Output(Bool())
    val R_data1_b = Output(UInt(4.W))
    val R_data2_b = Output(UInt(4.W))
  })
  
  val forward = Module(new Network())
  forward.io.val_in := io.F_val
  forward.io.data1_in := io.F_data1
  forward.io.data2_in := io.F_data2
  io.F_val_b := forward.io.val_b
  io.F_data1_b := forward.io.data1_b
  io.F_data2_b := forward.io.data2_b
  
  val reverse = Module(new Network())
  reverse.io.val_in := io.R_val
  reverse.io.data1_in := io.R_data1
  reverse.io.data2_in := io.R_data2
  io.R_val_b := reverse.io.val_b
  io.R_data1_b := reverse.io.data1_b
  io.R_data2_b := reverse.io.data2_b
}

object VerilogGenerator extends App {
  emitVerilog(new TCP(), args)
}