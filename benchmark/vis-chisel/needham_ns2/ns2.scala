package llmverify

import chisel3._
import chisel3.util._

// States for initiators and responders
object States extends ChiselEnum {
  val SLEEPING, WAITING, COMMITTED = Value
}

// Different types of messages
object MessageType extends ChiselEnum {
  val M_NoMessage, M_NonceAddress, M_NonceNonceAddress, M_Nonce = Value
}

class ns2 extends Module {
  val io = IO(new Bundle {
    val selectS = Input(UInt(2.W))  // MSB = 1, so 2 bits
    val selectO = Input(UInt(2.W))
    val intercept = Input(Bool())
    val knowledge = Input(UInt(2.W))  // KMB = 1, so 2 bits
    val message = Input(MessageType())
    val n1 = Input(UInt(2.W))
    val n2 = Input(UInt(2.W))
    val agent = Input(UInt(2.W))
    val coin = Input(Bool())
    
    // Outputs to preserve the design
    val outSource = Output(UInt(2.W))
    val outDest = Output(UInt(2.W))
    val outKey = Output(UInt(2.W))
    val outMType = Output(MessageType())
    val outNonce1 = Output(UInt(2.W))
    val outNonce2 = Output(UInt(2.W))
    val outAddress = Output(UInt(2.W))
    val outAstate = Output(Vec(1, States()))
    val outBstate = Output(Vec(1, States()))
  })

  // Parameters
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

  // Functions
  def isInitiator(i: UInt): Bool = i >= minInitiator.U && i <= maxInitiator.U
  def isResponder(i: UInt): Bool = i >= minResponder.U && i <= maxResponder.U
  def isIntruder(i: UInt): Bool = i >= minIntruder.U && i <= maxIntruder.U

  def nncIndex(row: UInt, col: UInt): UInt = Cat(row - minIntruder.U, col)
  def msgIndex(row: UInt, col: UInt): UInt = Cat(row - minIntruder.U, col)

  // Net variables
  val source = RegInit(0.U(2.W))
  val dest = RegInit(0.U(2.W))
  val key = RegInit(0.U(2.W))
  val mType = RegInit(MessageType.M_NoMessage)
  val nonce1 = RegInit(0.U(2.W))
  val nonce2 = RegInit(0.U(2.W))
  val address = RegInit(0.U(2.W))

  val empty = mType === MessageType.M_NoMessage

  // Initiator variables
  val Astate = RegInit(VecInit(Seq.fill(numInitiators)(States.SLEEPING)))
  val Apartner = RegInit(VecInit(Seq.fill(numInitiators)(0.U(2.W))))

  // Responder variables
  val Bstate = RegInit(VecInit(Seq.fill(numResponders)(States.SLEEPING)))
  val Bpartner = RegInit(VecInit(Seq.fill(numResponders)(0.U(2.W))))

  // Intruder variables
  val Cnonces = RegInit(VecInit(Seq.fill(maxCnncIndex + 1)(false.B)))
  val CmessageKey = RegInit(VecInit(Seq.fill(maxCmsgIndex + 1)(0.U(2.W))))
  val CmessageType = RegInit(VecInit(Seq.fill(maxCmsgIndex + 1)(MessageType.M_NoMessage)))
  val CmessageNonce1 = RegInit(VecInit(Seq.fill(maxCmsgIndex + 1)(0.U(2.W))))
  val CmessageNonce2 = RegInit(VecInit(Seq.fill(maxCmsgIndex + 1)(0.U(2.W))))
  val CmessageAddress = RegInit(VecInit(Seq.fill(maxCmsgIndex + 1)(0.U(2.W))))
  val Cpointer = RegInit(VecInit(Seq.fill(numIntruders)(0.U(2.W))))

  val self = RegInit(0.U(2.W))
  val other = RegInit(0.U(2.W))

  // Main logic
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
          address := self
          Astate(self) := States.WAITING
          Apartner(self) := other
        }
      }
      is(States.WAITING) {
        when(!empty && dest === self) {
          when(key === self && mType === MessageType.M_NonceNonceAddress &&
               nonce1 === self && address === Apartner(self)) {
            // initiator reacts to nonce received
            source := self
            dest := Apartner(self)
            key := Apartner(self)
            mType := MessageType.M_Nonce
            nonce1 := nonce2
            // nonce2 remains unchanged
            // address remains unchanged
            Astate(self) := States.COMMITTED
          }.otherwise {
            mType := MessageType.M_NoMessage
            source := 0.U
            dest := 0.U
            key := 0.U
            nonce1 := 0.U
            nonce2 := 0.U
            address := 0.U
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
            mType := MessageType.M_NonceNonceAddress
            // nonce1 remains unchanged
            nonce2 := self
            address := self
            Bstate(self) := States.WAITING
          }.otherwise {
            mType := MessageType.M_NoMessage
            source := 0.U
            dest := 0.U
            key := 0.U
            nonce1 := 0.U
            nonce2 := 0.U
            address := 0.U
          }
        }
      }
      is(States.WAITING) {
        when(!empty && dest === self) {
          when(key === self && mType === MessageType.M_Nonce &&
               nonce1 === self) {
            // responder reacts to own nonce
            Bstate(self) := States.COMMITTED
          }
          mType := MessageType.M_NoMessage
          source := 0.U
          dest := 0.U
          key := 0.U
          nonce1 := 0.U
          nonce2 := 0.U
          address := 0.U
        }
      }
    }
  }.elsewhen(isIntruder(self)) {
    when(!empty && !isIntruder(source)) {
      when(key === self) {
        Cnonces(nncIndex(self, nonce1)) := true.B
        when(mType === MessageType.M_NonceNonceAddress) {
          // intruder learns two nonces
          Cnonces(nncIndex(self, nonce2)) := true.B
        }
      }.otherwise {
        // intruder learns message
        CmessageKey(msgIndex(self, Cpointer(self))) := key
        CmessageType(msgIndex(self, Cpointer(self))) := mType
        CmessageNonce1(msgIndex(self, Cpointer(self))) := nonce1
        CmessageNonce2(msgIndex(self, Cpointer(self))) := nonce2
        CmessageAddress(msgIndex(self, Cpointer(self))) := address
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
        address := 0.U
      }
    }.elsewhen(empty && (isInitiator(other) || isResponder(other))) {
      when(io.coin && io.knowledge <= maxKnowledge.U) {
        when(CmessageType(msgIndex(self, io.knowledge)) =/= MessageType.M_NoMessage) {
          // intruder sends recorded message
          source := self
          dest := other
          key := CmessageKey(msgIndex(self, io.knowledge))
          mType := CmessageType(msgIndex(self, io.knowledge))
          nonce1 := CmessageNonce1(msgIndex(self, io.knowledge))
          nonce2 := CmessageNonce2(msgIndex(self, io.knowledge))
          address := CmessageAddress(msgIndex(self, io.knowledge))
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
          nonce2 := Mux(io.message === MessageType.M_NonceNonceAddress, io.n2, io.agent)
          address := io.agent
        }
      }
    }
  }

  // Connect outputs to preserve the design
  io.outSource := source
  io.outDest := dest
  io.outKey := key
  io.outMType := mType
  io.outNonce1 := nonce1
  io.outNonce2 := nonce2
  io.outAddress := address
  io.outAstate := Astate
  io.outBstate := Bstate
}

object VerilogGenerator extends App {
  emitVerilog(new ns2(), args)
}