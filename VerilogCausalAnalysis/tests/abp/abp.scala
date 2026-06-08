package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

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

class abp extends Module with Formal {
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
  
  // Formal verification assertions
  // 1. Mutual exclusion: only one of o1 or o2 can be active at a time
  fvAssert(!(o1Wire && o2Wire), "Arbiter mutual exclusion violated")
  
  // 2. Liveness: if sender sends a message, receiver should eventually acknowledge
  astRelaxedLiveness(sndmsgWire && o1Wire, rcvmsgWire && o2Wire, 100, "Sender message should be acknowledged by receiver")
  
  // 3. Protocol correctness: ack should be AM0 when expecting DATA10/DATA00, AM1 when expecting DATA11/DATA01
  fvAssert(!(o2Wire && r.io.state === ReceiverStatus.R_WAIT0 && 
    (messageWire === DataStatus.DATA10 || messageWire === DataStatus.DATA00) && 
    !(ackWire === AckStatus.AM0 || ackWire === AckStatus.AERR)), 
    "Receiver should send AM0 or AERR when expecting DATA10/DATA00")
  
  fvAssert(!(o2Wire && r.io.state === ReceiverStatus.R_WAIT1 && 
    (messageWire === DataStatus.DATA11 || messageWire === DataStatus.DATA01) && 
    !(ackWire === AckStatus.AM1 || ackWire === AckStatus.AERR)), 
    "Receiver should send AM1 or AERR when expecting DATA11/DATA01")
  
  // 4. State machine consistency: sender should not send messages in WAIT states
  fvAssert(!(o1Wire && (s.io.state === SenderStatus.S_WAIT0 || s.io.state === SenderStatus.S_WAIT1) && sndmsgWire), 
    "Sender should not send messages in WAIT states")
  
  // 5. Data consistency: message should be valid when sent
  fvAssert(!(sndmsgWire && o1Wire && !(messageWire === DataStatus.DATA00 || 
    messageWire === DataStatus.DATA01 || 
    messageWire === DataStatus.DATA10 || 
    messageWire === DataStatus.DATA11 || 
    messageWire === DataStatus.DERR)), 
    "Message should be valid when sent")
  
  // 6. Arbiter liveness: both modules should eventually get activated
  assertLivenessTimer(!o1Wire, reset = false.B, n = 50, "o1 should be activated within 50 cycles")
  assertLivenessTimer(!o2Wire, reset = false.B, n = 50, "o2 should be activated within 50 cycles")
}

class sender extends Module with Formal {
  val io = IO(new Bundle {
    val ack = Input(UInt(2.W))
    val active = Input(Bool())
    val message = Output(UInt(3.W))
    val sndmsg = Output(Bool())
    val state = Output(UInt(3.W)) // Expose state for verification
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
  io.state := state
  
  // Sender assertions
  // 1. State encoding: state should always be valid
  fvAssert(state === SenderStatus.S_INIT0 || state === SenderStatus.S_SEND0 || 
    state === SenderStatus.S_WAIT0 || state === SenderStatus.S_INIT1 || 
    state === SenderStatus.S_SEND1 || state === SenderStatus.S_WAIT1, 
    "Sender state should be valid")
  
  // 2. Message consistency: message should match smsg in SEND states
  // Fixed: Use proper implication structure instead of negated conjunction
  when (state === SenderStatus.S_SEND0 && smsg === BoolStatus.ONE) {
    fvAssert(messageReg === DataStatus.DATA10 || messageReg === DataStatus.DERR, 
      "SEND0 with smsg=ONE should send DATA10 or DERR")
  }
  
  when (state === SenderStatus.S_SEND0 && smsg === BoolStatus.ZERO) {
    fvAssert(messageReg === DataStatus.DATA00 || messageReg === DataStatus.DERR, 
      "SEND0 with smsg=ZERO should send DATA00 or DERR")
  }
  
  when (state === SenderStatus.S_SEND1 && smsg === BoolStatus.ONE) {
    fvAssert(messageReg === DataStatus.DATA11 || messageReg === DataStatus.DERR, 
      "SEND1 with smsg=ONE should send DATA11 or DERR")
  }
  
  when (state === SenderStatus.S_SEND1 && smsg === BoolStatus.ZERO) {
    fvAssert(messageReg === DataStatus.DATA01 || messageReg === DataStatus.DERR, 
      "SEND1 with smsg=ZERO should send DATA01 or DERR")
  }
  
  // 3. Transition correctness: from WAIT states should only go to INIT states when exit1 is true
  fvAssert(!(state === SenderStatus.S_WAIT0 && exit1 && !(nextState(state, io.ack, exit1) === SenderStatus.S_INIT1)), 
    "WAIT0 with exit1 should transition to INIT1")
  
  fvAssert(!(state === SenderStatus.S_WAIT1 && exit1 && !(nextState(state, io.ack, exit1) === SenderStatus.S_INIT0)), 
    "WAIT1 with exit1 should transition to INIT0")
  
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
  
  // Helper function to compute next state for verification
  def nextState(currentState: UInt, ack: UInt, exit: Bool): UInt = {
    val nextState = Wire(UInt(3.W))
    nextState := currentState
    
    when(currentState === SenderStatus.S_WAIT0 && exit) {
      nextState := SenderStatus.S_INIT1
    }.elsewhen(currentState === SenderStatus.S_WAIT1 && exit) {
      nextState := SenderStatus.S_INIT0
    }
    
    nextState
  }
}

class receiver extends Module with Formal {
  val io = IO(new Bundle {
    val message = Input(UInt(3.W))
    val active = Input(Bool())
    val ack = Output(UInt(2.W))
    val rcvmsg = Output(Bool())
    val state = Output(UInt(3.W)) // Expose state for verification
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
  io.state := state
  
  // Receiver assertions
  // 1. State encoding: state should always be valid
  fvAssert(state === ReceiverStatus.R_INIT0 || state === ReceiverStatus.R_WAIT0 || 
    state === ReceiverStatus.R_ACK0 || state === ReceiverStatus.R_INIT1 || 
    state === ReceiverStatus.R_WAIT1 || state === ReceiverStatus.R_ACK1, 
    "Receiver state should be valid")
  
  // 2. Ack consistency: ack should be AM0 in ACK0 state, AM1 in ACK1 state
  fvAssert(!(state === ReceiverStatus.R_ACK0 && !(ackReg === AckStatus.AM0 || ackReg === AckStatus.AERR)), 
    "ACK0 state should have AM0 or AERR")
  
  fvAssert(!(state === ReceiverStatus.R_ACK1 && !(ackReg === AckStatus.AM1 || ackReg === AckStatus.AERR)), 
    "ACK1 state should have AM1 or AERR")
  
  // 3. Message detection: should only set exit2 when receiving valid messages
  fvAssert(!((state === ReceiverStatus.R_WAIT0 && exit2) && 
    !(io.message === DataStatus.DATA10 || io.message === DataStatus.DATA00)), 
    "WAIT0 should only exit for DATA10 or DATA00")
  
  fvAssert(!((state === ReceiverStatus.R_WAIT1 && exit2) && 
    !(io.message === DataStatus.DATA11 || io.message === DataStatus.DATA01)), 
    "WAIT1 should only exit for DATA11 or DATA01")
  
  // 4. State transition: ACK states should transition to opposite INIT states
  fvAssert(!(state === ReceiverStatus.R_ACK0 && !(nextState(state) === ReceiverStatus.R_INIT1)), 
    "ACK0 should transition to INIT1")
  
  fvAssert(!(state === ReceiverStatus.R_ACK1 && !(nextState(state) === ReceiverStatus.R_INIT0)), 
    "ACK1 should transition to INIT0")
  
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
  
  // Helper function to compute next state for verification
  def nextState(currentState: UInt): UInt = {
    val nextState = Wire(UInt(3.W))
    nextState := currentState
    
    when(currentState === ReceiverStatus.R_ACK0) {
      nextState := ReceiverStatus.R_INIT1
    }.elsewhen(currentState === ReceiverStatus.R_ACK1) {
      nextState := ReceiverStatus.R_INIT0
    }
    
    nextState
  }
}

class arbiter extends Module with Formal {
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
  
  // Arbiter assertions
  // 1. Mutual exclusion: never both active
  fvAssert(!(o1Reg && o2Reg), "Arbiter: o1 and o2 should never both be true")
  
  // 2. At least one active: should always activate one of them
  fvAssert(o1Reg || o2Reg, "Arbiter: at least one output should be active")
  
  // 3. One-hot property: exactly one should be active
  assertOneHot(Cat(o1Reg, o2Reg), "Arbiter: exactly one output should be active")
  
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