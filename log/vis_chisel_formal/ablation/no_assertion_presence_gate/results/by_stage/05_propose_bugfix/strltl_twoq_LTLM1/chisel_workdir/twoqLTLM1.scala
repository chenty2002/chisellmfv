package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

class sampleq(WIDTH: Int = 2, LENGTH: Int = 4, LOGLENGTH: Int = 2) extends Module with Formal {
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

  // ============ FORMAL ASSERTIONS ============

  // Safety: FIFO pointers must stay within valid range
  fvAssert(readheadReg < LENGTH.U, "readhead_in_range")
  fvAssert(readtail < LENGTH.U, "readtail_in_range")
  fvAssert(writehead < LENGTH.U, "writehead_in_range")
  fvAssert(writetail < LENGTH.U, "writetail_in_range")

  // Safety: Bus request implies at least one FIFO is non-empty
  fvAssert(io.bus_req === !(readempty && writeempty), "bus_req_correct")

  // Safety: When validout is asserted, bus_gnt must have been given previously
  // validout only asserted in bus_gnt processing logic, so it implies bus_gnt in same cycle
  fvAssert(!io.validout || io.bus_gnt, "validout_requires_bus_gnt")

  // Safety: For read queue, readhead and readtail must not be equal when readhead entry is being dequeued
  // (readtail === readheadReg) means empty - can't dequeue when empty
  // When bus_gnt && !readempty && !matchReg, we dequeue from read queue
  // So validout && outisaread should never happen when readempty
  fvAssert(!(io.validout && io.outisaread) || !readempty, "read_dequeue_not_empty")

  // Safety: Similarly, write queue dequeue requires write queue non-empty
  fvAssert(!(io.validout && !io.outisaread) || !writeempty, "write_dequeue_not_empty")

  // Safety: Match can only be true when read queue is non-empty
  fvAssert(!matchReg || !readempty, "match_requires_nonempty_read")
}

class Buechi extends Module with Formal {
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

  // ============ FORMAL ASSERTIONS ============

  // Safety: State must always be one of the defined states
  val validState = (state === s_n1) || (state === s_n2) || (state === s_n3) || (state === s_n4) || (state === s_Trap)
  fvAssert(validState, "valid_state")

  // Safety: Once in Trap, always in Trap (absorbing state)
  // Use stability assertion on Trap state
  assertStableWhen(state === s_Trap, state, "trap_is_absorbing")

  // Liveness: From s_n3 on a busgnt0 with q0storeaddrNEQq0readhead=0, we should not get stuck
  // If busgnt0 is true and q0storeaddrNEQq0readhead is false, s_n3 -> s_n3 or s_n4
  // We check that fair (s_n4) is reachable under these conditions
  // Relaxed liveness: from s_n3, when busgnt0 && !q0storeaddrNEQq0readhead, fair should eventually hold
  val from_s3_with_busgnt = (state === s_n3) && io.busgnt0 && !io.q0storeaddrNEQq0readhead
  astRelaxedLiveness(from_s3_with_busgnt, io.fair, 10, "s3_busgnt_eventually_fair")

  // Liveness: From s_n1 with !q0storeaddrNEQq0readhead, we go to s_n3 or s_n4, so fair should eventually hold
  val from_s1_no_neq = (state === s_n1) && !io.q0storeaddrNEQq0readhead
  astRelaxedLiveness(from_s1_no_neq, io.fair, 10, "s1_no_neq_eventually_fair")
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

  // ============ FORMAL ASSERTIONS ============

  // === SAFETY ASSERTIONS ===

  // Safety: Bus grant must be one-hot (at most one queue gets grant)
  assert(PopCount(bus_gnt) <= 1.U, "bus_gnt_onehot")

  // Safety: Bus grant to a queue implies that queue is requesting the bus
  assertImplies(bus_gnt(0), q0.io.bus_req, "gnt0_requires_req0")
  assertImplies(bus_gnt(1), q1.io.bus_req, "gnt1_requires_req1")

  // Safety: When select is high, only q1 can get grant; when select is low, only q0 can get grant
  assertImplies(io.select && bus_gnt(0), false.B, "no_gnt0_when_select")   // select high should not grant q0
  assertImplies(!io.select && bus_gnt(1), false.B, "no_gnt1_when_not_select") // select low should not grant q1

  // Safety: select=1 implies only q1 gets grant; the arbiter checks bus_req(1)
  // When select is true and bus_req(1) is true, bus_gnt must be 2
  assertImplies(io.select && bus_req(1), bus_gnt === 2.U(2.W), "select_with_req1_grants_q1")

  // Safety: select=0 and bus_req(0) implies grant to q0 (bus_gnt=1)
  assertImplies(!io.select && bus_req(0), bus_gnt === 1.U(2.W), "not_select_with_req0_grants_q0")

  // Safety: When no queue requests, no grants are given
  assertImplies(bus_req === 0.U(2.W), bus_gnt === 0.U(2.W), "no_req_no_gnt")

  // === LIVENESS / PROGRESS ASSERTIONS ===

  // Liveness: When a queue gets a bus grant, it should produce valid output within bounded cycles
  // q0: bus_gnt(0) -> q0.io.validout should eventually be true within 5 cycles
  astRelaxedLiveness(bus_gnt(0), q0.io.validout, 5, "gnt0_leads_to_q0_valid")
  // q1: bus_gnt(1) -> q1.io.validout should eventually be true within 5 cycles
  astRelaxedLiveness(bus_gnt(1), q1.io.validout, 5, "gnt1_leads_to_q1_valid")

  // Liveness: Bus request should eventually be served (granted) when select matches
  // If q0 is requesting and select is low, q0 should eventually get grant
  astRelaxedLiveness(q0.io.bus_req && !io.select, bus_gnt(0), 10, "q0_req_eventually_granted")
  // If q1 is requesting and select is high, q1 should eventually get grant
  astRelaxedLiveness(q1.io.bus_req && io.select, bus_gnt(1), 10, "q1_req_eventually_granted")

  // === FAIRNESS ASSERTIONS ===

  // Fairness: fair implies scc (scc is a superset of fair states)
  assertImplies(io.fair, io.scc, "fair_implies_scc")
}

object VerilogGenerator extends App {
  emitVerilog(new twoQ(), args)
}
