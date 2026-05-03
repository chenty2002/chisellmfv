package llmverify

import chisel3._
import chisel3.util._

object TokenState {
  val START_RT = 0.U(3.W)
  val RT = 1.U(3.W)
  val WAIT_RT = 2.U(3.W)
  val START_NRT = 3.U(3.W)
  val NRT = 4.U(3.W)
  val WAIT_NRT = 5.U(3.W)
}

object NodeState {
  val A = 0.U(1.W)
  val B = 1.U(1.W)
}

class retherRTF extends Module {
  val io = IO(new Bundle {
    val select = Input(UInt(2.W))
    // Debug outputs to preserve the design
    val RT_count = Output(UInt(2.W))
    val grant = Output(Bool())
    val noGrant = Output(Bool())
    val ok = Output(Bool())
    val notOk = Output(Bool())
    val tokenState = Output(UInt(3.W))
    val NRT_count = Output(UInt(2.W))
    val index = Output(UInt(3.W))
    val next = Output(UInt(2.W))
    val serving_rt = Output(Bool())
    val nodeState = Output(Vec(4, UInt(1.W)))
    val token = Output(Vec(4, Bool()))
    val node = Output(Vec(4, Bool()))
    val rt = Output(Vec(4, Bool()))
    val nrt = Output(Vec(4, Bool()))
    val res = Output(Vec(4, Bool()))
    val request = Output(Bool())
    val rlease = Output(Bool())
  })
  
  // Parameters
  val N = 4
  val MSB = 1
  val MSBc = 2
  val Slots = 3
  val RTSlots = 2
  
  // Bandwidth allocation registers
  val RT_count = RegInit(1.U(2.W))
  val grant = RegInit(false.B)
  val noGrant = RegInit(false.B)
  val ok = RegInit(false.B)
  val notOk = RegInit(false.B)
  
  // Token management registers
  val tokenState = RegInit(TokenState.START_RT)
  val NRT_count = RegInit((Slots - 1).U(2.W))
  val index = RegInit(0.U(3.W))
  val next = RegInit(0.U(2.W))
  val serving_rt = RegInit(true.B)
  val token = RegInit(VecInit(Seq.fill(N)(false.B)))
  
  // Node process registers
  val nodeState = RegInit(VecInit(Seq.fill(N)(NodeState.A)))
  val self = RegInit(0.U(2.W))
  val request = RegInit(false.B)
  val rlease = RegInit(false.B)
  val node = RegInit(VecInit(Seq.fill(N)(false.B)))
  val rt = RegInit(VecInit(Seq.fill(N)(false.B)))
  val nrt = RegInit(VecInit(Seq.fill(N)(false.B)))
  val res = RegInit(VecInit(Seq.fill(N)(false.B)))
  
  // Initialize node[0] to true
  node(0) := true.B
  
  // Wire for coin flip (simplified nondeterminism)
  val coin = Wire(Bool())
  coin := self(0) // Simple pseudo-random based on select
  
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
  
  // Token management logic
  val start = (tokenState === TokenState.START_RT)
  val cycle = (tokenState === TokenState.NRT) && (NRT_count === 0.U)
  
  switch(tokenState) {
    is(TokenState.START_RT) {
      serving_rt := true.B
      index := 0.U
      tokenState := TokenState.RT
      NRT_count := (Slots - RTSlots).U
    }
    is(TokenState.RT) {
      when(index === N.U) {
        tokenState := TokenState.START_NRT
      }.elsewhen(node(index)) {
        token(index) := true.B
        tokenState := TokenState.WAIT_RT
      }.otherwise {
        index := index + 1.U
      }
    }
    is(TokenState.WAIT_RT) {
      when(nodeState(index) === NodeState.B) {
        token(index) := false.B
      }
      when(rt(index)) {
        index := index + 1.U
        tokenState := TokenState.RT
      }
    }
    is(TokenState.START_NRT) {
      serving_rt := false.B
      tokenState := TokenState.NRT
    }
    is(TokenState.NRT) {
      when(NRT_count === 0.U) {
        tokenState := TokenState.START_RT
      }.otherwise {
        token(next) := true.B
        tokenState := TokenState.WAIT_NRT
      }
    }
    is(TokenState.WAIT_NRT) {
      when(nodeState(next) === NodeState.B) {
        token(next) := false.B
      }
      when(nrt(next)) {
        next := (next + 1.U) % N.U
        NRT_count := NRT_count - 1.U
        tokenState := TokenState.NRT
      }
    }
  }
  
  // Node process logic
  self := io.select
  
  // Reset rt, nrt, res arrays each cycle
  for (i <- 0 until N) {
    rt(i) := false.B
    nrt(i) := false.B
    res(i) := false.B
  }
  
  when(serving_rt) {
    switch(nodeState(self)) {
      is(NodeState.A) {
        when(token(self)) {
          when(coin) {
            rlease := true.B
          }
          nodeState(self) := NodeState.B
        }
      }
      is(NodeState.B) {
        when(ok) {
          rlease := false.B
          node(self) := false.B
          rt(self) := true.B
          nodeState(self) := NodeState.A
        }.elsewhen(notOk) {
          rlease := false.B
          rt(self) := true.B
          nodeState(self) := NodeState.A
        }.elsewhen(!rlease) {
          rt(self) := true.B
          nodeState(self) := NodeState.A
        }
      }
    }
  }.otherwise {
    switch(nodeState(self)) {
      is(NodeState.A) {
        when(token(self)) {
          when(!node(self) && coin) {
            request := true.B
          }
          nodeState(self) := NodeState.B
        }
      }
      is(NodeState.B) {
        when(grant) {
          request := false.B
          node(self) := true.B
          res(self) := true.B
          nrt(self) := true.B
          nodeState(self) := NodeState.A
        }.elsewhen(noGrant) {
          request := false.B
          nrt(self) := true.B
          nodeState(self) := NodeState.A
        }.elsewhen(!request) {
          nrt(self) := true.B
          nodeState(self) := NodeState.A
        }
      }
    }
  }
  
  // Output assignments for debugging
  io.RT_count := RT_count
  io.grant := grant
  io.noGrant := noGrant
  io.ok := ok
  io.notOk := notOk
  io.tokenState := tokenState
  io.NRT_count := NRT_count
  io.index := index
  io.next := next
  io.serving_rt := serving_rt
  io.nodeState := nodeState
  io.token := token
  io.node := node
  io.rt := rt
  io.nrt := nrt
  io.res := res
  io.request := request
  io.rlease := rlease
}

object VerilogGenerator extends App {
  emitVerilog(new retherRTF(), args)
}