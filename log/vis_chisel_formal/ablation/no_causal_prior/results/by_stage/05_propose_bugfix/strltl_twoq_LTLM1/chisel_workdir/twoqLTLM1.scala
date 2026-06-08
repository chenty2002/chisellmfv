package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

class sampleq(WIDTH: Int = 2, LENGTH: Int = 4, LOGLENGTH: Int = 2) extends Module {
  val io = IO(new Bundle {
    val inaddr = Input(UInt(WIDTH.W))
    val validin = Input(Bool())
    val readin = Input(Bool())
    val clkin = Input(Clock())
    val bus_gnt = Input(Bool())
    val bus_req = Output(Bool())
    val outaddr = Output(UInt(WIDTH.W))
    val validout = Output(Bool())
    val outisaread = Output(Bool())
    val readheadentry = Output(UInt(WIDTH.W))
    val match_out = Output(Bool())
    val storeaddr = Output(UInt(LOGLENGTH.W))
    val readhead = Output(UInt(LOGLENGTH.W))
  })
  
  // FIFO memories
  val readfifo = RegInit(VecInit(Seq.fill(LENGTH)(0.U(WIDTH.W))))
  val writefifo = RegInit(VecInit(Seq.fill(LENGTH)(0.U(WIDTH.W))))
  
  // Pointers
  val readtail = RegInit(0.U(LOGLENGTH.W))
  val readheadReg = RegInit(0.U(LOGLENGTH.W))
  val writehead = RegInit(0.U(LOGLENGTH.W))
  val writetail = RegInit(0.U(LOGLENGTH.W))
  
  // Control signals
  val matchReg = RegInit(false.B)
  val outaddrReg = RegInit(0.U(WIDTH.W))
  val outisareadReg = RegInit(false.B)
  val validoutReg = RegInit(false.B)
  val storeaddrReg = RegInit(0.U(LOGLENGTH.W))
  
  // FIFO status
  val readempty = (readtail === readheadReg)
  val writeempty = (writetail === writehead)
  val readfull = ((readtail + 1.U) & ((1.U << LOGLENGTH) - 1.U)) === readheadReg
  val writefull = ((writetail + 1.U) & ((1.U << LOGLENGTH) - 1.U)) === writehead
  
  // Read head entry
  val readheadentryWire = readfifo(readheadReg)
  
  // Input processing
  when(io.validin && io.readin && !readfull) {
    readfifo(readtail) := io.inaddr
    readtail := readtail + 1.U
  }.elsewhen(io.validin && !io.readin && !writefull) {
    writefifo(writetail) := io.inaddr
    writetail := writetail + 1.U
  }
  
  // Bus grant processing
  when(io.bus_gnt) {
    // Check for match between read queue entry and write queue entries
    matchReg := false.B
    storeaddrReg := readheadReg
    
    for (i <- 0 until LENGTH) {
      val writeEntryValid = Mux(
        writehead < writetail,
        (i.U >= writehead) && (i.U < writetail),
        (i.U >= writehead) || (i.U < writetail)
      )
      
      when(writeEntryValid && !readempty && (readfifo(readheadReg) === writefifo(i.U))) {
        matchReg := true.B
        storeaddrReg := readheadReg
      }
    }
    
    // Output selection
    when(!readempty && !matchReg) {
      outaddrReg := readfifo(readheadReg)
      readheadReg := readheadReg + 1.U
      outisareadReg := true.B
      validoutReg := true.B
    }.elsewhen(!writeempty) {
      outaddrReg := writefifo(writehead)
      writehead := writehead + 1.U
      outisareadReg := false.B
      validoutReg := true.B
    }.otherwise {
      validoutReg := false.B
    }
  }
  
  // Outputs
  io.bus_req := !(readempty && writeempty)
  io.outaddr := outaddrReg
  io.validout := validoutReg
  io.outisaread := outisareadReg
  io.readheadentry := readheadentryWire
  io.match_out := matchReg
  io.storeaddr := storeaddrReg
  io.readhead := readheadReg
}

class Buechi extends Module {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val q0match = Input(Bool())
    val q0storeaddrNEQq0readhead = Input(Bool())
    val busgnt0 = Input(Bool())
    val fair = Output(Bool())
    val scc = Output(Bool())
  })
  
  // State enumeration
  val s_n1 :: s_n2 :: s_n3 :: s_n4 :: s_Trap :: Nil = Enum(5)
  val state = RegInit(s_n2)
  
  // Nondeterministic transitions (simplified to deterministic for Chisel)
  val ND_n3_n4 = Mux(state === s_n3, s_n4, s_n3) // Simplified choice
  val ND_n1_n2 = Mux(state === s_n1, s_n2, s_n1) // Simplified choice
  
  // Output assignments
  io.fair := (state === s_n4)
  io.scc := (state === s_n3) || (state === s_n4)
  
  // State transition logic
  when(state === s_n3 || state === s_n4) {
    val inputs = Cat(io.busgnt0, io.q0storeaddrNEQq0readhead)
    switch(inputs) {
      is("b00".U) { state := s_n3 }
      is("b01".U) { state := s_Trap }
      is("b11".U) { state := s_Trap }
      is("b10".U) { state := ND_n3_n4 }
    }
  }.elsewhen(state === s_n2) {
    val inputs = Cat(io.q0match, io.q0storeaddrNEQq0readhead)
    switch(inputs) {
      is("b00".U) { state := s_n2 }
      is("b01".U) { state := s_n2 }
      is("b10".U) { state := ND_n1_n2 }
      is("b11".U) { state := s_n2 }
    }
  }.elsewhen(state === s_Trap) {
    state := s_Trap
  }.elsewhen(state === s_n1) {
    when(io.q0storeaddrNEQq0readhead === false.B) {
      state := s_n3
    }.otherwise {
      state := s_Trap
    }
  }
}

class twoQ(WIDTH: Int = 2) extends Module with Formal {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val inaddr0 = Input(UInt(WIDTH.W))
    val inaddr1 = Input(UInt(WIDTH.W))
    val validin = Input(UInt(2.W))
    val readin = Input(UInt(2.W))
    val select = Input(Bool())
    
    // Additional outputs to preserve design
    val bus_req = Output(UInt(2.W))
    val validout = Output(UInt(2.W))
    val outisread = Output(UInt(2.W))
    val outaddr0 = Output(UInt(WIDTH.W))
    val outaddr1 = Output(UInt(WIDTH.W))
    val readheadentry0 = Output(UInt(WIDTH.W))
    val readheadentry1 = Output(UInt(WIDTH.W))
    val fair = Output(Bool())
    val scc = Output(Bool())
    val q0match = Output(Bool())
    val q1match = Output(Bool())
    val q0storeaddr = Output(UInt(2.W))
    val q1storeaddr = Output(UInt(2.W))
    val q0readhead = Output(UInt(2.W))
    val q1readhead = Output(UInt(2.W))
  })
  
  // Instantiate sampleq modules
  val q0 = Module(new sampleq(WIDTH))
  val q1 = Module(new sampleq(WIDTH))
  
  // Connect q0
  q0.io.inaddr := io.inaddr0
  q0.io.validin := io.validin(0)
  q0.io.readin := io.readin(0)
  q0.io.clkin := io.clock
  
  // Connect q1
  q1.io.inaddr := io.inaddr1
  q1.io.validin := io.validin(1)
  q1.io.readin := io.readin(1)
  q1.io.clkin := io.clock
  
  // Bus grant register
  val bus_gnt = RegInit(0.U(2.W))
  
  // Bus request signals
  val bus_req = Wire(UInt(2.W))
  bus_req := Cat(q1.io.bus_req, q0.io.bus_req)
  
  // Connect bus grants to sampleq modules
  q0.io.bus_gnt := bus_gnt(0)
  q1.io.bus_gnt := bus_gnt(1)
  
  // Bus grant arbitration logic
  when(io.select && bus_req(1)) {
    bus_gnt := 2.U(2.W)
  }.elsewhen(!io.select && bus_req(0)) {
    bus_gnt := 1.U(2.W)
  }.otherwise {
    bus_gnt := 0.U(2.W)
  }
  
  // Buechi module signals
  val q0storeaddrNEQq0readhead = (q0.io.storeaddr === q0.io.readhead)
  val busgnt0 = (bus_gnt(0) === 1.U)
  
  // Instantiate Buechi module
  val buechi = Module(new Buechi())
  buechi.io.clock := io.clock
  buechi.io.q0match := q0.io.match_out
  buechi.io.q0storeaddrNEQq0readhead := q0storeaddrNEQq0readhead
  buechi.io.busgnt0 := busgnt0
  
  // Connect outputs
  io.bus_req := bus_req
  io.validout := Cat(q1.io.validout, q0.io.validout)
  io.outisread := Cat(q1.io.outisaread, q0.io.outisaread)
  io.outaddr0 := q0.io.outaddr
  io.outaddr1 := q1.io.outaddr
  io.readheadentry0 := q0.io.readheadentry
  io.readheadentry1 := q1.io.readheadentry
  io.fair := buechi.io.fair
  io.scc := buechi.io.scc
  io.q0match := q0.io.match_out
  io.q1match := q1.io.match_out
  io.q0storeaddr := q0.io.storeaddr
  io.q1storeaddr := q1.io.storeaddr
  io.q0readhead := q0.io.readhead
  io.q1readhead := q1.io.readhead

  // ===== Formal Verification Assertions =====

  // Safety: Bus grant must not be both bits set (allow 00/01/10, forbid 11)
  // The arbiter may issue no grant (00) when no queue requests the bus,
  // so assertZeroOrOneHot semantics are required, not assertOneHot.
  assert(!(bus_gnt(0) && bus_gnt(1)), "bus_gnt_one_hot")

  // Safety: When select is high and q1 requests the bus, q1 gets the grant next cycle
  // Use RegNext to sample antecedent at previous cycle and check consequent now.
  assert(!RegNext(io.select && bus_req(1)) || bus_gnt(1), "grant_priority_q1")

  // Safety: When select is low and q0 requests the bus, q0 gets the grant next cycle  
  assert(!RegNext(!io.select && bus_req(0)) || bus_gnt(0), "grant_priority_q0")

  // Liveness: q1's request with priority (select high) is granted within 4 cycles
  astRelaxedLiveness(bus_req(1) && io.select, bus_gnt(1), 4, "q1_request_served")

  // Liveness: q0's request with priority (select low) is granted within 4 cycles
  astRelaxedLiveness(bus_req(0) && !io.select, bus_gnt(0), 4, "q0_request_served")
}

object VerilogGenerator extends App {
  emitVerilog(new twoQ(), args)
}
