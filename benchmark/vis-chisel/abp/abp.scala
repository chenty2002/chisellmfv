package llmverify

import chisel3._
import chisel3.util._

// Enum definitions
object SenderStatus {
  val S_INIT0 = 0.U(3.W)
  val S_SEND0 = 1.U(3.W)
  val S_WAIT0 = 2.U(3.W)
  val S_INIT1 = 3.U(3.W)
  val S_SEND1 = 4.U(3.W)
  val S_WAIT1 = 5.U(3.W)
}

object ReceiverStatus {
  val R_INIT0 = 0.U(3.W)
  val R_WAIT0 = 1.U(3.W)
  val R_ACK0 = 2.U(3.W)
  val R_INIT1 = 3.U(3.W)
  val R_WAIT1 = 4.U(3.W)
  val R_ACK1 = 5.U(3.W)
}

object DataStatus {
  val DATA00 = 0.U(3.W)
  val DATA01 = 1.U(3.W)
  val DATA10 = 2.U(3.W)
  val DATA11 = 3.U(3.W)
  val DERR = 4.U(3.W)
}

object AckStatus {
  val AM0 = 0.U(2.W)
  val AM1 = 1.U(2.W)
  val AERR = 2.U(2.W)
}

object BoolStatus {
  val ZERO = 0.U(2.W)
  val ONE = 1.U(2.W)
  val X = 2.U(2.W)
}

class abp extends Module {
  val io = IO(new Bundle {
    // Add outputs to preserve internal signals
    val message = Output(UInt(3.W))
    val ack = Output(UInt(2.W))
    val o1 = Output(Bool())
    val o2 = Output(Bool())
    val sndmsg = Output(Bool())
    val rcvmsg = Output(Bool())
  })
  
  val messageWire = Wire(UInt(3.W))
  val ackWire = Wire(UInt(2.W))
  val o1Wire = Wire(Bool())
  val o2Wire = Wire(Bool())
  val sndmsgWire = Wire(Bool())
  val rcvmsgWire = Wire(Bool())
  
  val s = Module(new sender())
  val r = Module(new receiver())
  val a = Module(new arbiter())
  
  // Connect sender
  s.io.ack := ackWire
  s.io.active := o1Wire
  messageWire := s.io.message
  sndmsgWire := s.io.sndmsg
  
  // Connect receiver
  r.io.message := messageWire
  r.io.active := o2Wire
  ackWire := r.io.ack
  rcvmsgWire := r.io.rcvmsg
  
  // Connect arbiter
  o1Wire := a.io.o1
  o2Wire := a.io.o2
  
  // Connect outputs
  io.message := messageWire
  io.ack := ackWire
  io.o1 := o1Wire
  io.o2 := o2Wire
  io.sndmsg := sndmsgWire
  io.rcvmsg := rcvmsgWire
}

class sender extends Module {
  val io = IO(new Bundle {
    val ack = Input(UInt(2.W))
    val active = Input(Bool())
    val message = Output(UInt(3.W))
    val sndmsg = Output(Bool())
  })
  
  val state = RegInit(SenderStatus.S_INIT0)
  val messageReg = RegInit(DataStatus.DATA11)
  val exit1 = RegInit(false.B)
  val smsg = RegInit(BoolStatus.X)
  
  // Non-deterministic signals - using LFSR for randomness
  val lfsr = RegInit(1.U(16.W))
  lfsr := Cat(lfsr(14,0), lfsr(15) ^ lfsr(13) ^ lfsr(12) ^ lfsr(10))
  val r_smsg = lfsr(0)
  val r10_message = Mux(lfsr(1), DataStatus.DATA10, DataStatus.DERR)
  val r00_message = Mux(lfsr(2), DataStatus.DATA00, DataStatus.DERR)
  val r11_message = Mux(lfsr(3), DataStatus.DATA11, DataStatus.DERR)
  val r01_message = Mux(lfsr(4), DataStatus.DATA01, DataStatus.DERR)
  
  io.sndmsg := (state === SenderStatus.S_INIT0 || state === SenderStatus.S_INIT1)
  io.message := messageReg
  
  when(io.active) {
    switch(state) {
      is(SenderStatus.S_INIT0) {
        exit1 := false.B
        when(r_smsg) {
          smsg := BoolStatus.ONE
        }.otherwise {
          smsg := BoolStatus.ZERO
        }
        state := SenderStatus.S_SEND0
      }
      is(SenderStatus.S_SEND0) {
        switch(smsg) {
          is(BoolStatus.ONE) { messageReg := r10_message }
          is(BoolStatus.ZERO) { messageReg := r00_message }
          is(BoolStatus.X) { messageReg := DataStatus.DERR }
        }
        state := SenderStatus.S_WAIT0
      }
      is(SenderStatus.S_WAIT0) {
        when(!exit1) {
          switch(io.ack) {
            is(AckStatus.AM0) { exit1 := true.B }
            is(AckStatus.AM1) {
              switch(smsg) {
                is(BoolStatus.ONE) { messageReg := r10_message }
                is(BoolStatus.ZERO) { messageReg := r00_message }
                is(BoolStatus.X) { messageReg := DataStatus.DERR }
              }
            }
            is(AckStatus.AERR) {
              switch(smsg) {
                is(BoolStatus.ONE) { messageReg := r10_message }
                is(BoolStatus.ZERO) { messageReg := r00_message }
                is(BoolStatus.X) { messageReg := DataStatus.DERR }
              }
            }
          }
        }.otherwise {
          state := SenderStatus.S_INIT1
          smsg := BoolStatus.X
        }
      }
      is(SenderStatus.S_INIT1) {
        exit1 := false.B
        when(r_smsg) {
          smsg := BoolStatus.ONE
        }.otherwise {
          smsg := BoolStatus.ZERO
        }
        state := SenderStatus.S_SEND1
      }
      is(SenderStatus.S_SEND1) {
        switch(smsg) {
          is(BoolStatus.ONE) { messageReg := r11_message }
          is(BoolStatus.ZERO) { messageReg := r01_message }
          is(BoolStatus.X) { messageReg := DataStatus.DERR }
        }
        state := SenderStatus.S_WAIT1
      }
      is(SenderStatus.S_WAIT1) {
        when(!exit1) {
          switch(io.ack) {
            is(AckStatus.AM1) { exit1 := true.B }
            is(AckStatus.AM0) {
              switch(smsg) {
                is(BoolStatus.ONE) { messageReg := r11_message }
                is(BoolStatus.ZERO) { messageReg := r01_message }
                is(BoolStatus.X) { messageReg := DataStatus.DERR }
              }
            }
            is(AckStatus.AERR) {
              switch(smsg) {
                is(BoolStatus.ONE) { messageReg := r11_message }
                is(BoolStatus.ZERO) { messageReg := r01_message }
                is(BoolStatus.X) { messageReg := DataStatus.DERR }
              }
            }
          }
        }.otherwise {
          state := SenderStatus.S_INIT0
          smsg := BoolStatus.X
        }
      }
    }
  }
}

class receiver extends Module {
  val io = IO(new Bundle {
    val message = Input(UInt(3.W))
    val active = Input(Bool())
    val ack = Output(UInt(2.W))
    val rcvmsg = Output(Bool())
  })
  
  val ackReg = RegInit(AckStatus.AERR)
  val state = RegInit(ReceiverStatus.R_INIT0)
  val exit2 = RegInit(false.B)
  val rmsg = RegInit(BoolStatus.X)
  
  // Non-deterministic signal
  val lfsr = RegInit(1.U(16.W))
  lfsr := Cat(lfsr(14,0), lfsr(15) ^ lfsr(13) ^ lfsr(12) ^ lfsr(10))
  val r_ack = lfsr(0)
  
  io.ack := ackReg
  io.rcvmsg := (state === ReceiverStatus.R_ACK0 || state === ReceiverStatus.R_ACK1)
  
  when(io.active) {
    switch(state) {
      is(ReceiverStatus.R_INIT0) {
        exit2 := false.B
        state := ReceiverStatus.R_WAIT0
      }
      is(ReceiverStatus.R_WAIT0) {
        when(!exit2) {
          when(io.message === DataStatus.DATA10) {
            exit2 := true.B
            rmsg := BoolStatus.ONE
          }.elsewhen(io.message === DataStatus.DATA00) {
            exit2 := true.B
            rmsg := BoolStatus.ZERO
          }.otherwise {
            when(r_ack) {
              ackReg := AckStatus.AM1
            }.otherwise {
              ackReg := AckStatus.AERR
            }
          }
        }.otherwise {
          state := ReceiverStatus.R_ACK0
        }
      }
      is(ReceiverStatus.R_ACK0) {
        when(r_ack) {
          ackReg := AckStatus.AM0
        }.otherwise {
          ackReg := AckStatus.AERR
        }
        state := ReceiverStatus.R_INIT1
      }
      is(ReceiverStatus.R_INIT1) {
        exit2 := false.B
        state := ReceiverStatus.R_WAIT1
      }
      is(ReceiverStatus.R_WAIT1) {
        when(!exit2) {
          when(io.message === DataStatus.DATA11) {
            exit2 := true.B
            rmsg := BoolStatus.ONE
          }.elsewhen(io.message === DataStatus.DATA01) {
            exit2 := true.B
            rmsg := BoolStatus.ZERO
          }.otherwise {
            when(r_ack) {
              ackReg := AckStatus.AM0
            }.otherwise {
              ackReg := AckStatus.AERR
            }
          }
        }.otherwise {
          state := ReceiverStatus.R_ACK1
        }
      }
      is(ReceiverStatus.R_ACK1) {
        when(r_ack) {
          ackReg := AckStatus.AM1
        }.otherwise {
          ackReg := AckStatus.AERR
        }
        state := ReceiverStatus.R_INIT0
      }
    }
  }
}

class arbiter extends Module {
  val io = IO(new Bundle {
    val o1 = Output(Bool())
    val o2 = Output(Bool())
  })
  
  val o1Reg = RegInit(false.B)
  val o2Reg = RegInit(false.B)
  
  // Non-deterministic signal
  val lfsr = RegInit(1.U(16.W))
  lfsr := Cat(lfsr(14,0), lfsr(15) ^ lfsr(13) ^ lfsr(12) ^ lfsr(10))
  val s = lfsr(0)
  
  io.o1 := o1Reg
  io.o2 := o2Reg
  
  when(s) {
    o1Reg := false.B
    o2Reg := true.B
  }.otherwise {
    o1Reg := true.B
    o2Reg := false.B
  }
}

object VerilogGenerator extends App {
  emitVerilog(new abp(), args)
}