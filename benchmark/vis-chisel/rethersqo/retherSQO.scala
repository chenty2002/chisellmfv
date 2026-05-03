package llmverify

import chisel3._
import chisel3.util._

// Enum for TokenState
object TokenState extends ChiselEnum {
  val START, POLL, WAIT1, WAIT2 = Value
}

// Enum for NodeState  
object NodeState extends ChiselEnum {
  val A, B = Value
}

class retherRTF(N: Int = 4, MSB: Int = 1, MSBc: Int = 2, Slots: Int = 3, RTSlots: Int = 2) extends Module {
  val io = IO(new Bundle {
    val select = Input(UInt(log2Ceil(N).W))
    // Additional outputs to preserve internal state
    val RT_count = Output(UInt(log2Ceil(N).W))
    val grant = Output(Bool())
    val noGrant = Output(Bool())
    val ok = Output(Bool())
    val notOk = Output(Bool())
    val tokenState = Output(TokenState())
    val index = Output(UInt(log2Ceil(N).W))
    val token = Output(Vec(N, Bool()))
    val nodeState = Output(Vec(N, NodeState()))
    val tok_RT_count = Output(UInt(log2Ceil(N).W))
    val NRT_count = Output(UInt(log2Ceil(N).W))
    val total_NRT = Output(UInt(log2Ceil(N).W))
    val request = Output(Bool())
    val rlease = Output(Bool())
    val nodeBusy = Output(Bool())
    val node = Output(Vec(N, Bool()))
    val rt = Output(Vec(N, Bool()))
    val nrt = Output(Vec(N, Bool()))
    val res = Output(Vec(N, Bool()))
  })
  
  // Calculate proper bit widths
  val addrWidth = log2Ceil(N)
  val countWidth = log2Ceil(Slots + 1) // Need enough bits for RTSlots and NRT_count
  
  // Bandwidth allocation registers
  val RT_count = RegInit(1.U(countWidth.W))
  val grant = RegInit(false.B)
  val noGrant = RegInit(false.B)
  val ok = RegInit(false.B)
  val notOk = RegInit(false.B)
  
  // Token management registers
  val tokenState = RegInit(TokenState.START)
  val index = RegInit(0.U(addrWidth.W))
  val token = RegInit(VecInit(Seq.fill(N)(false.B)))
  
  // Node process registers
  val nodeStateVec = RegInit(VecInit(Seq.fill(N)(NodeState.A)))
  val self = RegInit(0.U(addrWidth.W))
  val tok_RT_count = RegInit(1.U(countWidth.W))
  val NRT_count = RegInit((Slots - 1).U(countWidth.W))
  val total_NRT = RegInit((Slots - 1).U(countWidth.W))
  val next = RegInit(0.U(addrWidth.W))
  val request = RegInit(false.B)
  val rlease = RegInit(false.B)
  val nodeBusy = RegInit(false.B)
  val node = RegInit(VecInit(Seq.fill(N)(false.B)))
  val rt = RegInit(VecInit(Seq.fill(N)(false.B)))
  val nrt = RegInit(VecInit(Seq.fill(N)(false.B)))
  val res = RegInit(VecInit(Seq.fill(N)(false.B)))
  
  // Initialize node[0] to true
  node(0) := true.B
  
  // Wire definitions
  val start = tokenState === TokenState.START
  val cycle = tokenState === TokenState.POLL && NRT_count === 0.U && tok_RT_count === 0.U
  
  // Nondeterministic coin - using LFSR for pseudo-randomness
  val lfsr = RegInit(1.U(32.W))
  val coin = lfsr(0)
  lfsr := (lfsr << 1) | ((lfsr(31) ^ lfsr(21) ^ lfsr(1) ^ lfsr(0)) & 1.U)
  
  val NRT_enabled = NRT_count > 0.U && (
    next === index || 
    (index < next && total_NRT > (N.U - next) + index)
  )
  
  // Bandwidth allocation logic
  when(grant || noGrant) {
    when(!request) {
      grant := false.B
      noGrant := false.B
    }
  }.elsewhen(request) {
    when(RT_count < RTSlots.U) {
      RT_count := RT_count + 1.U
      grant := true.B
    }.otherwise {
      noGrant := true.B
    }
  }.elsewhen(ok || notOk) {
    when(!rlease) {
      ok := false.B
      notOk := false.B
    }
  }.elsewhen(rlease) {
    when(RT_count > 1.U) {
      RT_count := RT_count - 1.U
      ok := true.B
    }.otherwise {
      notOk := true.B
    }
  }
  
  // Token management state machine
  switch(tokenState) {
    is(TokenState.START) {
      index := 0.U
      tokenState := TokenState.POLL
    }
    is(TokenState.POLL) {
      when(NRT_count === 0.U && tok_RT_count === 0.U) {
        tokenState := TokenState.START
      }.otherwise {
        token(index) := true.B
        tokenState := TokenState.WAIT1
      }
    }
    is(TokenState.WAIT1) {
      when(nodeBusy) {
        token(index) := false.B
        tokenState := TokenState.WAIT2
      }
    }
    is(TokenState.WAIT2) {
      when(!nodeBusy) {
        index := index + 1.U
        tokenState := TokenState.POLL
      }
    }
  }
  
  // Node process logic
  // Reset transmission signals each cycle
  for (i <- 0 until N) {
    rt(i) := false.B
    nrt(i) := false.B
    res(i) := false.B
  }
  
  when(start) {
    tok_RT_count := RT_count
    NRT_count := (Slots.U - RT_count)
    total_NRT := (Slots.U - RT_count)
  }
  
  self := io.select
  
  switch(nodeStateVec(self)) {
    is(NodeState.A) {
      when(token(self)) {
        nodeBusy := true.B
        when(node(self)) {
          when(tok_RT_count > 0.U) {
            tok_RT_count := tok_RT_count - 1.U
            rt(self) := true.B
          }
          when(coin) {
            rlease := true.B
          }
        }.otherwise {
          when(coin) {
            request := true.B
          }
        }
        nodeStateVec(self) := NodeState.B
      }
    }
    is(NodeState.B) {
      when(grant) {
        request := false.B
        node(self) := true.B
        res(self) := true.B
      }.elsewhen(noGrant) {
        request := false.B
      }.elsewhen(ok) {
        rlease := false.B
        node(self) := false.B
      }.elsewhen(notOk) {
        rlease := false.B
      }
      
      when(grant || noGrant || ok || notOk || !(rlease || request)) {
        when(NRT_enabled) {
          nrt(self) := true.B
          NRT_count := NRT_count - 1.U
          when(index === next) {
            next := next + 1.U
          }
        }
        nodeBusy := false.B
        nodeStateVec(self) := NodeState.A
      }
    }
  }
  
  // Output assignments
  io.RT_count := RT_count
  io.grant := grant
  io.noGrant := noGrant
  io.ok := ok
  io.notOk := notOk
  io.tokenState := tokenState
  io.index := index
  io.token := token
  io.nodeState := nodeStateVec
  io.tok_RT_count := tok_RT_count
  io.NRT_count := NRT_count
  io.total_NRT := total_NRT
  io.request := request
  io.rlease := rlease
  io.nodeBusy := nodeBusy
  io.node := node
  io.rt := rt
  io.nrt := nrt
  io.res := res
}

object VerilogGenerator extends App {
  emitVerilog(new retherRTF(), args)
}