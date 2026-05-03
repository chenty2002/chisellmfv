package llmverify

import chisel3._
import chisel3.util._

// States for initiators and responders
object States extends ChiselEnum {
  val SLEEPING, WAITING, COMMITTED = Value
}

// Different types of messages
object MessageType extends ChiselEnum {
  val M_NoMessage, M_NonceAddress, M_NonceNonce, M_Nonce = Value
}

class ns1 extends Module {
  val io = IO(new Bundle {
    val selectS = Input(UInt(2.W))
    val selectO = Input(UInt(2.W))
    val intercept = Input(Bool())
    val knowledge = Input(UInt(2.W))
    val message = Input(MessageType())
    val n1 = Input(UInt(2.W))
    val n2 = Input(UInt(2.W))
    val agent = Input(UInt(2.W))
    val coin = Input(Bool())
    
    // Outputs to preserve internal state
    val out_source = Output(UInt(2.W))
    val out_dest = Output(UInt(2.W))
    val out_key = Output(UInt(2.W))
    val out_mType = Output(MessageType())
    val out_nonce1 = Output(UInt(2.W))
    val out_nonce2 = Output(UInt(2.W))
    val out_Astate = Output(Vec(1, States()))
    val out_Apartner = Output(Vec(1, UInt(2.W)))
    val out_Bstate = Output(Vec(1, States()))
    val out_Bpartner = Output(Vec(1, UInt(2.W)))
    val out_Cnonces = Output(Vec(3, Bool()))
    val out_CmessageKey = Output(Vec(4, UInt(2.W)))
    val out_CmessageType = Output(Vec(4, MessageType()))
    val out_CmessageNonce1 = Output(Vec(4, UInt(2.W)))
    val out_CmessageNonce2 = Output(Vec(4, UInt(2.W)))
    val out_Cpointer = Output(Vec(1, UInt(2.W)))
  })
  
  // Parameters as constants
  val numInitiators = 1
  val numResponders = 1
  val numIntruders = 1
  val numAgents = numInitiators + numResponders + numIntruders
  val maxKnowledge = 3
  val MSB = 1
  val KMB = 1
  val minInitiator = 0
  val maxInitiator = numInitiators - 1
  val minResponder = maxInitiator + 1
  val maxResponder = maxInitiator + numResponders
  val minIntruder = maxResponder + 1
  val maxIntruder = maxResponder + numIntruders
  val maxCmsgIndex = 3
  val maxCnncIndex = 2
  
  // Helper functions
  def isInitiator(i: UInt): Bool = {
    i >= minInitiator.U && i <= maxInitiator.U
  }
  
  def isResponder(i: UInt): Bool = {
    i >= minResponder.U && i <= maxResponder.U
  }
  
  def isIntruder(i: UInt): Bool = {
    i >= minIntruder.U && i <= maxIntruder.U
  }
  
  def nncIndex(row: UInt, col: UInt): UInt = {
    val tmp = row - minIntruder.U
    Cat(tmp, col)
  }
  
  def msgIndex(row: UInt, col: UInt): UInt = {
    val tmp = row - minIntruder.U
    Cat(tmp, col)
  }
  
  // Net variables
  val source = RegInit(0.U(2.W))
  val dest = RegInit(0.U(2.W))
  val key = RegInit(0.U(2.W))
  val mType = RegInit(MessageType.M_NoMessage)
  val nonce1 = RegInit(0.U(2.W))
  val nonce2 = RegInit(0.U(2.W))
  
  val empty = mType === MessageType.M_NoMessage
  
  // Initiator variables
  val Astate = RegInit(VecInit(Seq.fill(numInitiators)(States.SLEEPING)))
  val Apartner = RegInit(VecInit(Seq.fill(numInitiators)(0.U(2.W))))
  
  // Responder variables
  val Bstate = RegInit(VecInit(Seq.fill(numResponders)(States.SLEEPING)))
  val Bpartner = RegInit(VecInit(Seq.fill(numResponders)(0.U(2.W))))
  
  // Intruder variables - initialize Cnonces according to Verilog logic
  val Cnonces = WireInit(VecInit(Seq.fill(maxCnncIndex + 1)(false.B)))
  // Set Cnonces[2] = 1 (i = minIntruder = 2, i <= maxCnncIndex = 2)
  Cnonces(2) := true.B
  
  val CmessageKey = RegInit(VecInit(Seq.fill(maxCmsgIndex + 1)(0.U(2.W))))
  val CmessageType = RegInit(VecInit(Seq.fill(maxCmsgIndex + 1)(MessageType.M_NoMessage)))
  val CmessageNonce1 = RegInit(VecInit(Seq.fill(maxCmsgIndex + 1)(0.U(2.W))))
  val CmessageNonce2 = RegInit(VecInit(Seq.fill(maxCmsgIndex + 1)(0.U(2.W))))
  val Cpointer = RegInit(VecInit(Seq.fill(numIntruders)(0.U(2.W))))
  
  val self = RegInit(0.U(2.W))
  val other = RegInit(0.U(2.W))
  
  // Sequential logic
  self := io.selectS
  other := io.selectO
  
  when(isInitiator(self)) {
    switch(Astate(self)) {
      is(States.SLEEPING) {
        when(empty && (isResponder(other) || isIntruder(other))) {
          // initiator starts protocol with responder or intruder
          source := self
          dest := other
          key := other
          mType := MessageType.M_NonceAddress
          nonce1 := self
          nonce2 := self
          Astate(self) := States.WAITING
          Apartner(self) := other
        }
      }
      is(States.WAITING) {
        when(!empty && dest === self) {
          when(key === self && mType === MessageType.M_NonceNonce && nonce1 === self) {
            // initiator reacts to nonce received
            source := self
            dest := Apartner(self)
            key := Apartner(self)
            mType := MessageType.M_Nonce
            nonce1 := nonce2
            // nonce2 := nonce2 (unchanged)
            Astate(self) := States.COMMITTED
          }.otherwise {
            mType := MessageType.M_NoMessage
            source := 0.U
            dest := 0.U
            key := 0.U
            nonce1 := 0.U
            nonce2 := 0.U
          }
        }
      }
    }
  }.elsewhen(isResponder(self)) {
    switch(Bstate(self)) {
      is(States.SLEEPING) {
        when(!empty && dest === self) {
          when(key === self && mType === MessageType.M_NonceAddress) {
            // responder reacts to initiator's nonce
            Bpartner(self) := nonce2
            source := self
            dest := nonce2
            key := nonce2
            mType := MessageType.M_NonceNonce
            // nonce1 := nonce1 (unchanged)
            nonce2 := self
            Bstate(self) := States.WAITING
          }.otherwise {
            mType := MessageType.M_NoMessage
            source := 0.U
            dest := 0.U
            key := 0.U
            nonce1 := 0.U
            nonce2 := 0.U
          }
        }
      }
      is(States.WAITING) {
        when(!empty && dest === self) {
          when(key === self && mType === MessageType.M_Nonce && nonce1 === self) {
            // responder reacts to own nonce
            Bstate(self) := States.COMMITTED
          }
          mType := MessageType.M_NoMessage
          source := 0.U
          dest := 0.U
          key := 0.U
          nonce1 := 0.U
          nonce2 := 0.U
        }
      }
    }
  }.elsewhen(isIntruder(self)) {
    when(!empty && !isIntruder(source)) {
      when(key === self) {
        Cnonces(nncIndex(self, nonce1)) := true.B
        when(mType === MessageType.M_NonceNonce) {
          // intruder learns two nonces
          Cnonces(nncIndex(self, nonce2)) := true.B
        }
      }.otherwise {
        // intruder learns message
        val msgIdx = msgIndex(self, Cpointer(self))
        CmessageKey(msgIdx) := key
        CmessageType(msgIdx) := mType
        CmessageNonce1(msgIdx) := nonce1
        CmessageNonce2(msgIdx) := nonce2
        
        when(Cpointer(self) === maxKnowledge.U) {
          Cpointer(self) := 0.U
        }.otherwise {
          Cpointer(self) := Cpointer(self) + 1.U
        }
      }
      
      when(io.intercept) {
        mType := MessageType.M_NoMessage
        source := 0.U
        dest := 0.U
        key := 0.U
        nonce1 := 0.U
        nonce2 := 0.U
      }
    }.elsewhen(empty && (isInitiator(other) || isResponder(other))) {
      when(io.coin && io.knowledge <= maxKnowledge.U) {
        val msgIdx = msgIndex(self, io.knowledge)
        when(CmessageType(msgIdx) =/= MessageType.M_NoMessage) {
          // intruder sends recorded message
          source := self
          dest := other
          key := CmessageKey(msgIdx)
          mType := CmessageType(msgIdx)
          nonce1 := CmessageNonce1(msgIdx)
          nonce2 := CmessageNonce2(msgIdx)
        }
      }
      
      when(!io.coin && (io.n1 < numAgents.U) && (io.n2 < numAgents.U) &&
            (io.agent < numAgents.U) && (io.message =/= MessageType.M_NoMessage)) {
        when(Cnonces(nncIndex(self, io.n1)) && Cnonces(nncIndex(self, io.n2))) {
          // intruder generates message with known nonces
          source := self
          dest := other
          key := other
          mType := io.message
          nonce1 := io.n1
          nonce2 := Mux(io.message === MessageType.M_NonceNonce, io.n2, io.agent)
        }
      }
    }
  }
  
  // Connect outputs to preserve internal state
  io.out_source := source
  io.out_dest := dest
  io.out_key := key
  io.out_mType := mType
  io.out_nonce1 := nonce1
  io.out_nonce2 := nonce2
  io.out_Astate := Astate
  io.out_Apartner := Apartner
  io.out_Bstate := Bstate
  io.out_Bpartner := Bpartner
  io.out_Cnonces := Cnonces
  io.out_CmessageKey := CmessageKey
  io.out_CmessageType := CmessageType
  io.out_CmessageNonce1 := CmessageNonce1
  io.out_CmessageNonce2 := CmessageNonce2
  io.out_Cpointer := Cpointer
}

object VerilogGenerator extends App {
  emitVerilog(new ns1(), args)
}