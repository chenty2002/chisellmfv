package llmverify

import chisel3._
import chisel3.util._

// Enum definitions as constants
object MState {
  val R = 0.U(2.W)
  val G = 1.U(2.W)
  val U = 2.U(2.W)
  val D = 3.U(2.W)
}

object RState {
  val I = 0.U(1.W)
  val B = 1.U(1.W)
}

object State {
  val S = 0.U(2.W)
  val D = 1.U(2.W)
  val X = 2.U(2.W)
  val T = 3.U(2.W)
}

// Non-deterministic choice function
class NDModule extends Module {
  val io = IO(new Bundle {
    val option1 = Input(UInt(8.W))
    val option2 = Input(UInt(8.W))
    val result = Output(UInt(8.W))
  })
  // Simple implementation - could be made truly non-deterministic
  io.result := Mux(io.option1 > io.option2, io.option1, io.option2)
}

class resource extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    // Add outputs to preserve internal signals
    val req_out = Output(Bool())
    val grant_out = Output(Bool())
    val use_out = Output(Bool())
    val release_out = Output(Bool())
    val m_st_out = Output(UInt(2.W))
    val r_st_out = Output(UInt(1.W))
  })
  
  val clk = io.clk
  
  // Instantiate a0 module
  val a0_inst = Module(new a0())
  a0_inst.io.clk := clk
  
  val req = Wire(Bool())
  val grant = Wire(Bool())
  val use = Wire(Bool())
  val release = Wire(Bool())
  
  a0_inst.io.req := req
  a0_inst.io.grant := grant
  a0_inst.io.use := use
  a0_inst.io.release := release
  
  // RESOURCE REQUESTOR
  val m_st = RegInit(MState.R)
  val r_m_st = Wire(UInt(2.W))
  
  // Non-deterministic choice for r_m_st
  val nd_module1 = Module(new NDModule())
  nd_module1.io.option1 := MState.U
  nd_module1.io.option2 := MState.D
  r_m_st := nd_module1.io.result
  
  req := (m_st === MState.R)
  use := (m_st === MState.U)
  release := (m_st === MState.D)
  
  when(m_st === MState.R) {
    when(grant) {
      m_st := MState.G
    }
  }.elsewhen(m_st === MState.G) {
    m_st := MState.U
  }.elsewhen(m_st === MState.U) {
    m_st := r_m_st
  }.elsewhen(m_st === MState.D) {
    m_st := MState.R
  }
  
  // RESOURCE GRANTER
  val r_st = RegInit(RState.I)
  val r_r_st = Wire(UInt(1.W))
  
  // Non-deterministic choice for r_r_st
  val nd_module2 = Module(new NDModule())
  nd_module2.io.option1 := RState.B
  nd_module2.io.option2 := RState.I
  r_r_st := nd_module2.io.result
  
  grant := (r_st === RState.B)
  
  when(r_st === RState.I) {
    when(req) {
      r_st := r_r_st
    }.otherwise {
      r_st := RState.I
    }
  }.elsewhen(r_st === RState.B) {
    when(release) {
      r_st := RState.I
    }
  }
  
  // Connect outputs
  io.req_out := req
  io.grant_out := grant
  io.use_out := use
  io.release_out := release
  io.m_st_out := m_st
  io.r_st_out := r_st
}

class a0 extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val req = Input(Bool())
    val grant = Input(Bool())
    val use = Input(Bool())
    val release = Input(Bool())
    // Add outputs to preserve internal signals
    val trigger_out = Output(Bool())
    val failure_out = Output(Bool())
  })
  
  val clk = io.clk
  
  val trigger = Wire(Bool())
  val failure = Wire(Bool())
  val e0 = Wire(Bool())
  val r0 = Wire(Bool())
  val s0 = Wire(Bool())
  val f0 = Wire(Bool())
  val e3 = Wire(Bool())
  val r3 = Wire(Bool())
  val s3 = Wire(Bool())
  val f3 = Wire(Bool())
  
  e0 := true.B
  r0 := s3 || f0
  trigger := s0
  
  val a0_seq0_inst = Module(new a0_seq0())
  a0_seq0_inst.io.clk := clk
  a0_seq0_inst.io.e := e0
  a0_seq0_inst.io.r := r0
  a0_seq0_inst.io.req := io.req
  s0 := a0_seq0_inst.io.s
  f0 := a0_seq0_inst.io.f
  
  e3 := trigger
  r3 := false.B
  
  val a0_seq3_inst = Module(new a0_seq3())
  a0_seq3_inst.io.clk := clk
  a0_seq3_inst.io.e := e3
  a0_seq3_inst.io.r := r3
  a0_seq3_inst.io.req := io.req
  a0_seq3_inst.io.grant := io.grant
  a0_seq3_inst.io.use := io.use
  a0_seq3_inst.io.release := io.release
  s3 := a0_seq3_inst.io.s
  f3 := a0_seq3_inst.io.f
  
  failure := f3
  
  io.trigger_out := trigger
  io.failure_out := failure
}

class a0_seq0 extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val e = Input(Bool())
    val r = Input(Bool())
    val req = Input(Bool())
    val s = Output(Bool())
    val f = Output(Bool())
  })
  
  val st = RegInit(State.S)
  
  io.s := ((st === State.S) && io.e && io.req)
  io.f := ((st === State.S) && io.e && !io.req) || (st === State.T)
  
  when(st === State.S) {
    when(io.e && io.req) {
      st := State.S
    }.elsewhen(io.e && !io.req) {
      st := State.T
    }
  }.otherwise {
    when(io.r) {
      st := State.S
    }
  }
}

class a0_seq3 extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val e = Input(Bool())
    val r = Input(Bool())
    val req = Input(Bool())
    val grant = Input(Bool())
    val use = Input(Bool())
    val release = Input(Bool())
    val s = Output(Bool())
    val f = Output(Bool())
  })
  
  val e1 = Wire(Bool())
  val r1 = Wire(Bool())
  val s1 = Wire(Bool())
  val f1 = Wire(Bool())
  val e2 = Wire(Bool())
  val r2 = Wire(Bool())
  val s2 = Wire(Bool())
  val f2 = Wire(Bool())
  
  val a0_seq1_inst = Module(new a0_seq1())
  a0_seq1_inst.io.clk := io.clk
  a0_seq1_inst.io.e := e1
  a0_seq1_inst.io.r := r1
  a0_seq1_inst.io.req := io.req
  a0_seq1_inst.io.grant := io.grant
  s1 := a0_seq1_inst.io.s
  f1 := a0_seq1_inst.io.f
  
  val a0_seq2_inst = Module(new a0_seq2())
  a0_seq2_inst.io.clk := io.clk
  a0_seq2_inst.io.e := e2
  a0_seq2_inst.io.r := r2
  a0_seq2_inst.io.use := io.use
  a0_seq2_inst.io.release := io.release
  s2 := a0_seq2_inst.io.s
  f2 := a0_seq2_inst.io.f
  
  r1 := io.r
  r2 := io.r
  
  val then_inst = Module(new then())
  then_inst.io.clk := io.clk
  then_inst.io.e := s1
  then_inst.io.r := io.r
  e2 := then_inst.io.s
  
  io.s := s2
  io.f := f1 || f2
  e1 := io.e
}

class then extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val e = Input(Bool())
    val r = Input(Bool())
    val s = Output(Bool())
  })
  
  val st = RegInit(State.S)
  
  io.s := (st === State.D)
  
  when(st === State.S) {
    when(io.e) {
      st := State.X
    }
  }.elsewhen(st === State.X) {
    st := State.D
  }.otherwise {
    when(io.r) {
      st := State.S
    }
  }
}

class a0_seq1 extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val e = Input(Bool())
    val r = Input(Bool())
    val req = Input(Bool())
    val grant = Input(Bool())
    val s = Output(Bool())
    val f = Output(Bool())
  })
  
  val st = RegInit(State.S)
  
  io.s := ((st === State.S) && io.e && io.grant) || ((st === State.X) && !io.r && io.grant)
  io.f := ((st === State.S) && io.e && !io.req && !io.grant) || 
          ((st === State.X) && !io.req && !io.grant) || 
          (st === State.T)
  
  when(st === State.S) {
    when(io.e && io.grant) {
      st := State.S
    }.elsewhen(io.e && io.req) {
      st := State.X
    }.elsewhen(io.e && !io.req && !io.grant) {
      st := State.T
    }
  }.elsewhen(st === State.X) {
    when(io.r || io.grant) {
      st := State.S
    }.elsewhen(!io.req && !io.grant) {
      st := State.T
    }
  }.otherwise {
    when(io.r) {
      st := State.S
    }
  }
}

class a0_seq2 extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val e = Input(Bool())
    val r = Input(Bool())
    val use = Input(Bool())
    val release = Input(Bool())
    val s = Output(Bool())
    val f = Output(Bool())
  })
  
  val st = RegInit(State.S)
  
  io.s := ((st === State.S) && io.e && io.release) || ((st === State.X) && !io.r && io.release)
  io.f := ((st === State.S) && io.e && !io.use && !io.release) || 
          ((st === State.X) && !io.use && !io.release) || 
          (st === State.T)
  
  when(st === State.S) {
    when(io.e && io.release) {
      st := State.S
    }.elsewhen(io.e && io.use) {
      st := State.X
    }.elsewhen(io.e && !io.use && !io.release) {
      st := State.T
    }
  }.elsewhen(st === State.X) {
    when(io.r || io.release) {
      st := State.S
    }.elsewhen(!io.use && !io.release) {
      st := State.T
    }
  }.otherwise {
    when(io.r) {
      st := State.S
    }
  }
}

object VerilogGenerator extends App {
  emitVerilog(new resource(), args)
}