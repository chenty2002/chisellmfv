package llmverify

import chisel3._
import chisel3.util._

// Constants
object EthernetConstants {
  val myFALSE = 0.U(1.W)
  val myTRUE = 1.U(1.W)
  
  val Fail = 0.U(2.W)
  val Success = 1.U(2.W)
  val NA = 2.U(2.W)
  
  val Jam = 0.U(2.W)
  val F = 1.U(2.W)
  val ND = 2.U(2.W)
  
  val NoReq = 0.U(1.W)
  val Req = 1.U(1.W)
}

// Simple LFSR implementation for pseudo-random number generation
class SimpleLFSR(width: Int) extends Module {
  val io = IO(new Bundle {
    val out = Output(UInt(width.W))
  })
  
  val lfsr = RegInit(1.U(width.W))
  
  // Simple LFSR with taps at positions width-1 and width-2
  val feedback = lfsr(width-1) ^ lfsr(width-2)
  lfsr := Cat(lfsr(width-2, 0), feedback)
  
  io.out := lfsr
}

class BitTransmitter extends Module {
  import EthernetConstants._
  
  val io = IO(new Bundle {
    val send_bt = Input(UInt(2.W))
    val finished_bt = Output(Bool())
    val send_pls = Output(UInt(2.W))
  })
  
  val sendpls = RegInit(ND)
  val finishedbt = RegInit(myFALSE)
  
  io.send_pls := sendpls
  io.finished_bt := finishedbt
  
  when(finishedbt === myTRUE) {
    finishedbt := myFALSE
  }
  
  when(io.send_bt === F) {
    sendpls := F
  }.elsewhen(io.send_bt === Jam) {
    sendpls := Jam
  }.elsewhen(sendpls === F || sendpls === Jam) {
    finishedbt := myTRUE
    sendpls := ND
  }
}

class FrameTransmitter extends Module {
  import EthernetConstants._
  
  val io = IO(new Bundle {
    val llc_f_ready = Input(Bool())
    val okay = Output(UInt(2.W))
    val send_bt = Output(UInt(2.W))
    val finished_bt = Input(Bool())
    val CS = Input(Bool())
    val CD = Input(Bool())
  })
  
  val sendbt = RegInit(ND)
  val oka = RegInit(NA)
  val delay_reg = RegInit(0.U(6.W))
  val num_of_collisions = RegInit(0.U(4.W))
  val count = RegInit(0.U(3.W))
  val prop_delay = RegInit(0.U(3.W))
  val finish_flag = RegInit(false.B)
  val done_Jam = RegInit(false.B)
  
  // Simple LFSR for random bits
  val lfsr = Module(new SimpleLFSR(16))
  val randomBits = lfsr.io.out
  
  val r_bit0 = randomBits(0)
  val r_bit1 = randomBits(1)
  val r_bit2 = randomBits(2)
  val r_bit3 = randomBits(3)
  val r_bit4 = randomBits(4)
  val r_bit5 = randomBits(5)
  
  val delay = Wire(UInt(6.W))
  delay := MuxCase(0.U(6.W), Seq(
    (num_of_collisions === 1.U) -> Cat(0.U(5.W), r_bit0),
    (num_of_collisions === 2.U) -> Cat(0.U(4.W), r_bit1, r_bit0),
    (num_of_collisions === 3.U) -> Cat(0.U(3.W), r_bit2, r_bit1, r_bit0),
    (num_of_collisions === 4.U) -> Cat(0.U(2.W), r_bit3, r_bit2, r_bit1, r_bit0)
  ))
  
  io.send_bt := sendbt
  io.okay := oka
  
  // Propagation delay counter
  prop_delay := prop_delay + 1.U
  
  when(oka === Success || oka === Fail) {
    oka := NA
  }.otherwise {
    // Synchronous delay count down
    when(delay_reg > 0.U) {
      delay_reg := delay_reg - 1.U
    }.otherwise {
      // Send first good data bit
      when(io.llc_f_ready === Req && io.CS === myFALSE && 
            count === 0.U && io.CD === myFALSE) {
        count := count + 1.U
        sendbt := F
        prop_delay := 0.U
      }
      // Collision detected while FT was transmitting
      .elsewhen(io.CD === myTRUE && count > 0.U && sendbt =/= Jam && 
                done_Jam === false.B) {
        count := 1.U
        sendbt := Jam
        done_Jam := true.B
      }
      // Send successive data/jam bits
      .elsewhen((sendbt === F || sendbt === Jam) && count > 0.U) {
        count := count + 1.U
        when(count === 2.U) {
          sendbt := ND
        }
      }
      // Received finish signal from BT
      .elsewhen(io.finished_bt === myTRUE) {
        when(done_Jam === true.B) {
          count := 0.U
          finish_flag := false.B
          done_Jam := false.B
          num_of_collisions := num_of_collisions + 1.U
          when(num_of_collisions > 4.U) {
            oka := Fail
            num_of_collisions := 0.U
          }
          delay_reg := delay
        }.elsewhen(prop_delay >= 4.U) {
          num_of_collisions := 0.U
          oka := Success
          count := 0.U
        }.otherwise {
          finish_flag := true.B
        }
      }
      
      when(finish_flag === true.B && prop_delay >= 4.U && done_Jam =/= true.B) {
        num_of_collisions := 0.U
        oka := Success
        count := 0.U
        finish_flag := false.B
      }
    }
  }
}

class LLC extends Module {
  import EthernetConstants._
  
  val io = IO(new Bundle {
    val ack = Input(UInt(2.W))
    val req = Output(Bool())
    val frame_ready = Input(Bool())
    val llc_ack = Output(Bool())
  })
  
  val reqState = RegInit(NoReq)
  val llc__ack = RegInit(myFALSE)
  val temp_frame = RegInit(false.B)
  
  io.req := reqState
  io.llc_ack := llc__ack
  
  // Simple LFSR for random choice
  val lfsr = Module(new SimpleLFSR(8))
  val randomBits = lfsr.io.out
  val randChoice = randomBits(2, 0)
  
  when(llc__ack === myTRUE) {
    llc__ack := myFALSE
  }.elsewhen(io.frame_ready === myTRUE) {
    llc__ack := myTRUE
  }
  
  when(reqState === NoReq) {
    when(randChoice > 3.U) {
      reqState := Req
    }
  }.elsewhen(io.ack === Success || io.ack === Fail) {
    reqState := NoReq
  }
}

class FrameReceiver extends Module {
  import EthernetConstants._
  
  val io = IO(new Bundle {
    val data_in = Input(UInt(2.W))
    val send_plr = Output(Bool())
    val frame_ready = Output(Bool())
    val llc_ack = Input(Bool())
    val CS = Input(Bool())
  })
  
  val count = RegInit(0.U(1.W))
  val frm_ready = RegInit(myFALSE)
  val reqbit = RegInit(myFALSE)
  val extra_cycle = RegInit(false.B)
  
  io.frame_ready := frm_ready
  io.send_plr := reqbit
  
  when(frm_ready === myTRUE) {
    when(io.llc_ack === myTRUE) {
      frm_ready := myFALSE
    }
  }
  
  when(count === 1.U && io.data_in === ND) {
    frm_ready := myTRUE
    count := 0.U
  }.elsewhen(count === 1.U) {
    count := 0.U
  }
  
  when(reqbit === myTRUE || extra_cycle === true.B) {
    when(reqbit === myTRUE) {
      extra_cycle := true.B
    }.otherwise {
      extra_cycle := false.B
    }
    
    when(io.data_in === F) {
      count := count + 1.U
    }
  }
  
  when(io.CS === myTRUE) {
    reqbit := myTRUE
  }.otherwise {
    reqbit := myFALSE
  }
}

class PLS extends Module {
  import EthernetConstants._
  
  val io = IO(new Bundle {
    val send_signal = Input(UInt(2.W))
    val channel = Output(UInt(2.W))
  })
  
  val channel_send_data = RegInit(ND)
  
  io.channel := channel_send_data
  
  channel_send_data := io.send_signal
}

class PLR extends Module {
  import EthernetConstants._
  
  val io = IO(new Bundle {
    val send_next_bit = Input(Bool())
    val send_FR_signal = Output(UInt(2.W))
    val channel = Input(UInt(2.W))
  })
  
  val channel_receive_data = RegInit(ND)
  val receive_to_FR = RegInit(ND)
  
  val connect = channel_receive_data
  io.send_FR_signal := receive_to_FR
  
  when(io.send_next_bit === myTRUE) {
    receive_to_FR := connect
  }.otherwise {
    receive_to_FR := ND
  }
  
  channel_receive_data := io.channel
}

class CHNL extends Module {
  import EthernetConstants._
  
  val io = IO(new Bundle {
    val data_in_1 = Input(UInt(2.W))
    val data_in_2 = Input(UInt(2.W))
    val carrier_sense = Output(Bool())
    val collision_detect = Output(Bool())
    val data_out = Output(UInt(2.W))
  })
  
  val carrier_sense_reg = RegInit(myFALSE)
  val collision_detect_reg = RegInit(myFALSE)
  val data_out_reg = RegInit(ND)
  
  io.carrier_sense := carrier_sense_reg
  io.collision_detect := collision_detect_reg
  io.data_out := data_out_reg
  
  when(io.data_in_1 === ND) {
    data_out_reg := io.data_in_2
  }.elsewhen(io.data_in_2 === ND) {
    data_out_reg := io.data_in_1
  }.otherwise {
    data_out_reg := Jam
  }
  
  when(!((io.data_in_1 === ND) && (io.data_in_2 === ND))) {
    carrier_sense_reg := myTRUE
  }.otherwise {
    carrier_sense_reg := myFALSE
  }
  
  when(!((io.data_in_1 === ND) || (io.data_in_2 === ND)) || 
        (io.data_in_1 === Jam) || (io.data_in_2 === Jam)) {
    collision_detect_reg := myTRUE
  }.otherwise {
    collision_detect_reg := myFALSE
  }
}

class main extends Module {
  import EthernetConstants._
  
  val io = IO(new Bundle {
    // Debug outputs to preserve signals
    val debug_fin_bt0 = Output(Bool())
    val debug_fin_bt1 = Output(Bool())
    val debug_CS = Output(Bool())
    val debug_CD = Output(Bool())
    val debug_frame_ready0 = Output(Bool())
    val debug_frame_ready1 = Output(Bool())
    val debug_send_plr0 = Output(Bool())
    val debug_send_plr1 = Output(Bool())
    val debug_ack0 = Output(UInt(2.W))
    val debug_ack1 = Output(UInt(2.W))
    val debug_req0 = Output(Bool())
    val debug_req1 = Output(Bool())
  })
  
  // LLC instances
  val L0 = Module(new LLC())
  val L1 = Module(new LLC())
  
  // FrameTransmitter instances
  val FT0 = Module(new FrameTransmitter())
  val FT1 = Module(new FrameTransmitter())
  
  // BitTransmitter instances
  val BT0 = Module(new BitTransmitter())
  val BT1 = Module(new BitTransmitter())
  
  // FrameReceiver instances
  val FR0 = Module(new FrameReceiver())
  val FR1 = Module(new FrameReceiver())
  
  // PLS instances
  val pls_0 = Module(new PLS())
  val pls_1 = Module(new PLS())
  
  // PLR instances
  val plr_0 = Module(new PLR())
  val plr_1 = Module(new PLR())
  
  // Channel instance
  val CHN = Module(new CHNL())
  
  // Connections for LLC0 and FrameTransmitter0
  L0.io.ack := FT0.io.okay
  L0.io.frame_ready := FR0.io.frame_ready
  FT0.io.llc_f_ready := L0.io.req
  FT0.io.finished_bt := BT0.io.finished_bt
  
  // Connections for LLC1 and FrameTransmitter1
  L1.io.ack := FT1.io.okay
  L1.io.frame_ready := FR1.io.frame_ready
  FT1.io.llc_f_ready := L1.io.req
  FT1.io.finished_bt := BT1.io.finished_bt
  
  // Connections for BitTransmitter0
  BT0.io.send_bt := FT0.io.send_bt
  
  // Connections for BitTransmitter1
  BT1.io.send_bt := FT1.io.send_bt
  
  // Connections for FrameReceiver0
  FR0.io.llc_ack := L0.io.llc_ack
  FR0.io.CS := CHN.io.carrier_sense
  
  // Connections for FrameReceiver1
  FR1.io.llc_ack := L1.io.llc_ack
  FR1.io.CS := CHN.io.carrier_sense
  
  // Connections for PLS0
  pls_0.io.send_signal := BT0.io.send_pls
  
  // Connections for PLS1
  pls_1.io.send_signal := BT1.io.send_pls
  
  // Connections for PLR0
  plr_0.io.send_next_bit := FR0.io.send_plr
  plr_0.io.channel := CHN.io.data_out
  
  // Connections for PLR1
  plr_1.io.send_next_bit := FR1.io.send_plr
  plr_1.io.channel := CHN.io.data_out
  
  // Connections for FrameReceiver data inputs
  FR0.io.data_in := plr_0.io.send_FR_signal
  FR1.io.data_in := plr_1.io.send_FR_signal
  
  // Connections for Channel
  CHN.io.data_in_1 := pls_0.io.channel
  CHN.io.data_in_2 := pls_1.io.channel
  
  // Connect CS and CD to FrameTransmitters
  FT0.io.CS := CHN.io.carrier_sense
  FT0.io.CD := CHN.io.collision_detect
  FT1.io.CS := CHN.io.carrier_sense
  FT1.io.CD := CHN.io.collision_detect
  
  // Debug outputs to preserve signals
  io.debug_fin_bt0 := BT0.io.finished_bt
  io.debug_fin_bt1 := BT1.io.finished_bt
  io.debug_CS := CHN.io.carrier_sense
  io.debug_CD := CHN.io.collision_detect
  io.debug_frame_ready0 := FR0.io.frame_ready
  io.debug_frame_ready1 := FR1.io.frame_ready
  io.debug_send_plr0 := FR0.io.send_plr
  io.debug_send_plr1 := FR1.io.send_plr
  io.debug_ack0 := FT0.io.okay
  io.debug_ack1 := FT1.io.okay
  io.debug_req0 := L0.io.req
  io.debug_req1 := L1.io.req
}

object VerilogGenerator extends App {
  emitVerilog(new main(), Array("--target-dir", "generated"))
}