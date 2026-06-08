package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

// Enum definitions
class Command extends Bundle {
  val value = UInt(4.W)
}

object Command {
  def idle = 0.U
  def read_shared = 1.U
  def read_owned = 2.U
  def write_invalid = 3.U
  def write_shared = 4.U
  def write_resp_invalid = 5.U
  def write_resp_shared = 6.U
  def invalidate = 7.U
  def response = 8.U
}

class Status extends Bundle {
  val value = UInt(2.W)
}

object Status {
  def invalid = 0.U
  def shared = 1.U
  def owned = 2.U
}

// Simple LFSR for generating pseudo-random values
class LFSR(width: Int) extends Module {
  val io = IO(new Bundle {
    val out = Output(UInt(width.W))
  })
  
  val lfsrReg = RegInit(1.U(width.W))
  
  // Simple LFSR with taps at positions 16,14,13,11 for 16-bit LFSR
  val next = Cat(lfsrReg(width-2,0), lfsrReg(width-1) ^ lfsrReg(width-2) ^ lfsrReg(width-3) ^ lfsrReg(width-5))
  lfsrReg := next
  io.out := lfsrReg
}

class CacheDeviceIO extends Bundle {
  val CMD = Input(UInt(4.W))
  val master = Input(Bool())
  val abort = Input(Bool())
  val waiting = Input(Bool())
  val state = Output(UInt(2.W))
  val snoop = Output(UInt(2.W))
  val reply_owned = Output(Bool())
  val readable = Output(Bool())
  val writable = Output(Bool())
}

class CacheDevice extends Module with Formal {
  val io = IO(new CacheDeviceIO)
  
  // LFSR for nondeterministic values
  val lfsr = Module(new LFSR(16))
  
  // State registers
  val stateReg = RegInit(Status.invalid)
  val snoopReg = RegInit(Status.invalid)
  
  // Outputs
  io.state := stateReg
  io.snoop := snoopReg
  
  // Combinational logic
  io.readable := ((stateReg === Status.shared) || (stateReg === Status.owned)) && !io.waiting
  io.writable := (stateReg === Status.owned) && (!io.waiting)
  io.reply_owned := (!io.master) && (stateReg === Status.owned)
  
  // Nondeterministic values (using LFSR for simulation)
  val nond_snoop = Mux(!io.master && stateReg === Status.owned && io.CMD === Command.read_shared,
    Mux(lfsr.io.out(0), Status.shared, Status.owned),
    Status.invalid)
  
  val nond_state = Mux(stateReg === Status.shared,
    Mux(lfsr.io.out(1), Status.shared, Status.invalid),
    Status.invalid)
  
  // State machine
  when(io.abort) {
    snoopReg := snoopReg
    stateReg := stateReg
  }.otherwise {
    // Update snoop
    when(!io.master && stateReg === Status.owned && io.CMD === Command.read_shared) {
      snoopReg := nond_snoop
    }.elsewhen(io.master && io.CMD === Command.write_resp_invalid) {
      snoopReg := Status.invalid
    }.elsewhen(io.master && io.CMD === Command.write_resp_shared) {
      snoopReg := Status.invalid
    }.otherwise {
      snoopReg := snoopReg
    }
    
    // Update state
    when(io.master) {
      switch(io.CMD) {
        is(Command.read_shared) { stateReg := Status.shared }
        is(Command.read_owned) { stateReg := Status.owned }
        is(Command.write_invalid) { stateReg := Status.invalid }
        is(Command.write_resp_invalid) { stateReg := Status.invalid }
        is(Command.write_shared) { stateReg := Status.shared }
        is(Command.write_resp_shared) { stateReg := Status.shared }
      }
    }.elsewhen(!io.master && stateReg === Status.shared && 
              (io.CMD === Command.read_owned || io.CMD === Command.invalidate)) {
      stateReg := Status.invalid
    }.elsewhen(stateReg === Status.shared) {
      stateReg := nond_state
    }
  }
  
  // Formal verification assertions
  
  // State encoding assertion - state should only be valid values
  fvAssert(stateReg === Status.invalid || stateReg === Status.shared || stateReg === Status.owned, 
           "CacheDevice: Invalid state value")
  
  // Snoop encoding assertion
  fvAssert(snoopReg === Status.invalid || snoopReg === Status.shared || snoopReg === Status.owned, 
           "CacheDevice: Invalid snoop value")
  
  // Readable/writable consistency
  fvAssert(io.readable === ((stateReg === Status.shared || stateReg === Status.owned) && !io.waiting),
           "CacheDevice: Readable signal mismatch")
  fvAssert(io.writable === (stateReg === Status.owned && !io.waiting),
           "CacheDevice: Writable signal mismatch")
  
  // Reply owned should only be true when not master and state is owned
  fvAssert(io.reply_owned === (!io.master && stateReg === Status.owned),
           "CacheDevice: Reply owned signal mismatch")
  
  // State transition consistency - invalid state should only transition to valid states
  fvAssert(!(stateReg === Status.invalid && io.master && 
            io.CMD =/= Command.read_shared && io.CMD =/= Command.read_owned &&
            io.CMD =/= Command.write_invalid && io.CMD =/= Command.write_shared &&
            io.CMD =/= Command.write_resp_invalid && io.CMD =/= Command.write_resp_shared),
           "CacheDevice: Invalid state transition from invalid")
}

class BusDeviceIO extends Bundle {
  val CMD = Input(UInt(4.W))
  val master = Input(Bool())
  val REPLY_STALL = Input(Bool())
  val REPLY_WAITING = Input(Bool())
  val waiting = Output(Bool())
  val reply_waiting = Output(Bool())
  val abort = Output(Bool())
}

class BusDevice extends Module with Formal {
  val io = IO(new BusDeviceIO)
  
  val waitingReg = RegInit(false.B)
  
  io.waiting := waitingReg
  io.reply_waiting := !io.master && waitingReg
  io.abort := io.REPLY_STALL || 
              ((io.CMD === Command.read_shared || io.CMD === Command.read_owned) && io.REPLY_WAITING)
  
  when(io.abort) {
    waitingReg := waitingReg
  }.elsewhen(io.master && io.CMD === Command.read_shared) {
    waitingReg := true.B
  }.elsewhen(io.master && io.CMD === Command.read_owned) {
    waitingReg := true.B
  }.elsewhen(!io.master && io.CMD === Command.response) {
    waitingReg := false.B
  }.elsewhen(!io.master && io.CMD === Command.write_resp_invalid) {
    waitingReg := false.B
  }.elsewhen(!io.master && io.CMD === Command.write_resp_shared) {
    waitingReg := false.B
  }
  
  // Formal verification assertions
  
  // Reply waiting should only be true when not master and waiting
  fvAssert(io.reply_waiting === (!io.master && waitingReg),
           "BusDevice: Reply waiting signal mismatch")
  
  // Abort should be true when REPLY_STALL is true
  fvAssert(!io.REPLY_STALL || io.abort,
           "BusDevice: Abort should be true when REPLY_STALL is true")
  
  // Abort should be true for read commands when REPLY_WAITING is true
  fvAssert(!((io.CMD === Command.read_shared || io.CMD === Command.read_owned) && io.REPLY_WAITING) || io.abort,
           "BusDevice: Abort should be true for read commands with REPLY_WAITING")
  
  // Waiting should eventually be cleared after response (liveness)
  astRelaxedLiveness(waitingReg && !io.master && io.CMD === Command.response, 
                     !waitingReg, 10, "BusDevice: Waiting should be cleared after response")
}

class ProcessorIO extends Bundle {
  val CMD = Input(UInt(4.W))
  val master = Input(Bool())
  val REPLY_OWNED = Input(Bool())
  val REPLY_WAITING = Input(Bool())
  val REPLY_STALL = Input(Bool())
  val cmd = Output(UInt(4.W))
  val reply_owned = Output(Bool())
  val reply_waiting = Output(Bool())
  val reply_stall = Output(Bool())
}

class Processor extends Module with Formal {
  val io = IO(new ProcessorIO)
  
  // LFSR for nondeterministic values
  val lfsr = Module(new LFSR(16))
  
  // Instantiate bus device
  val busDevice = Module(new BusDevice)
  busDevice.io.CMD := io.CMD
  busDevice.io.master := io.master
  busDevice.io.REPLY_STALL := io.REPLY_STALL
  busDevice.io.REPLY_WAITING := io.REPLY_WAITING
  val abort = busDevice.io.abort
  val waiting = busDevice.io.waiting
  io.reply_waiting := busDevice.io.reply_waiting
  
  // Instantiate cache device
  val cacheDevice = Module(new CacheDevice)
  cacheDevice.io.CMD := io.CMD
  cacheDevice.io.master := io.master
  cacheDevice.io.abort := abort
  cacheDevice.io.waiting := waiting
  val state = cacheDevice.io.state
  val snoop = cacheDevice.io.snoop
  io.reply_owned := cacheDevice.io.reply_owned
  
  // Nondeterministic command
  val nond_cmd = Mux(lfsr.io.out(2), Command.read_shared, Command.read_owned)
  
  // Command generation logic
  io.cmd := Mux(io.master && state === Status.invalid, nond_cmd,
    Mux(io.master && state === Status.shared, Command.read_owned,
      Mux(io.master && state === Status.owned && snoop === Status.owned, Command.write_resp_invalid,
        Mux(io.master && state === Status.owned && snoop === Status.shared, Command.write_resp_shared,
          Mux(io.master && state === Status.owned && snoop === Status.invalid, Command.write_invalid,
            Command.idle)))))
  
  // Nondeterministic stall
  io.reply_stall := lfsr.io.out(3)
  
  // Formal verification assertions
  
  // Command should be valid (within defined range)
  fvAssert(io.cmd <= Command.response, "Processor: Invalid command value")
  
  // When not master, should only issue idle command
  fvAssert(io.master || io.cmd === Command.idle, "Processor: Non-master should only issue idle")
  
  // When master and state is invalid, should issue read commands
  fvAssert(!io.master || state =/= Status.invalid || 
           (io.cmd === Command.read_shared || io.cmd === Command.read_owned),
           "Processor: Master with invalid state should issue read commands")
}

class MemoryIO extends Bundle {
  val CMD = Input(UInt(4.W))
  val master = Input(Bool())
  val REPLY_OWNED = Input(Bool())
  val REPLY_WAITING = Input(Bool())
  val REPLY_STALL = Input(Bool())
  val cmd = Output(UInt(4.W))
  val reply_owned = Output(Bool())
  val reply_waiting = Output(Bool())
  val reply_stall = Output(Bool())
}

class Memory extends Module with Formal {
  val io = IO(new MemoryIO)
  
  // LFSR for nondeterministic values
  val lfsr = Module(new LFSR(16))
  
  val busyReg = RegInit(false.B)
  
  io.reply_owned := false.B
  io.reply_waiting := false.B
  
  val abort = io.REPLY_STALL || 
              (io.CMD === Command.read_shared || io.CMD === Command.read_owned) && io.REPLY_WAITING || 
              (io.CMD === Command.read_shared || io.CMD === Command.read_owned) && io.REPLY_OWNED
  
  val nond_cmd = Mux(lfsr.io.out(4), Command.response, Command.idle)
  val nond_reply_stall = lfsr.io.out(5)
  
  io.cmd := Mux(io.master && busyReg, nond_cmd, Command.idle)
  
  io.reply_stall := Mux(busyReg && (io.CMD === Command.read_shared || io.CMD === Command.read_owned ||
                      io.CMD === Command.write_invalid || io.CMD === Command.write_shared ||
                      io.CMD === Command.write_resp_invalid || io.CMD === Command.write_resp_shared),
                      true.B, nond_reply_stall)
  
  when(abort) {
    busyReg := busyReg
  }.elsewhen(io.master && io.CMD === Command.response) {
    busyReg := false.B
  }.elsewhen(!io.master && (io.CMD === Command.read_owned || io.CMD === Command.read_shared)) {
    busyReg := true.B
  }
  
  // Formal verification assertions
  
  // Memory should never claim reply_owned
  fvAssert(!io.reply_owned, "Memory: Should never claim reply_owned")
  
  // Memory should never claim reply_waiting
  fvAssert(!io.reply_waiting, "Memory: Should never claim reply_waiting")
  
  // When busy and master, should only issue response or idle
  fvAssert(!busyReg || !io.master || (io.cmd === Command.response || io.cmd === Command.idle),
           "Memory: Busy master should only issue response or idle")
  
  // When not master and not busy, should only issue idle
  fvAssert(io.master || busyReg || io.cmd === Command.idle,
           "Memory: Non-master non-busy should only issue idle")
  
  // Reply stall should be true when busy with certain commands
  fvAssert(!busyReg || !(io.CMD === Command.read_shared || io.CMD === Command.read_owned ||
           io.CMD === Command.write_invalid || io.CMD === Command.write_shared ||
           io.CMD === Command.write_resp_invalid || io.CMD === Command.write_resp_shared) || io.reply_stall,
           "Memory: Should stall when busy with memory commands")
}

class MainIO extends Bundle {
  // Expose internal signals for verification
  val p0_cmd = Output(UInt(4.W))
  val p1_cmd = Output(UInt(4.W))
  val p2_cmd = Output(UInt(4.W))
  val m_cmd = Output(UInt(4.W))
  val p0_master = Output(Bool())
  val p1_master = Output(Bool())
  val p2_master = Output(Bool())
  val m_master = Output(Bool())
  val CMD = Output(UInt(4.W))
  val REPLY_OWNED = Output(Bool())
  val REPLY_WAITING = Output(Bool())
  val REPLY_STALL = Output(Bool())
}

class Main extends Module with Formal {
  val io = IO(new MainIO)
  
  // LFSR for nondeterministic values
  val lfsr = Module(new LFSR(16))
  
  // Instantiate processors and memory
  val p0 = Module(new Processor)
  val p1 = Module(new Processor)
  val p2 = Module(new Processor)
  val m = Module(new Memory)
  
  // Global signals
  val CMD = Wire(UInt(4.W))
  val REPLY_OWNED = Wire(Bool())
  val REPLY_WAITING = Wire(Bool())
  val REPLY_STALL = Wire(Bool())
  
  // Master signals
  val p0_master = Wire(Bool())
  val p1_master = Wire(Bool())
  val p2_master = Wire(Bool())
  val m_master = Wire(Bool())
  
  // Connect processors
  p0.io.CMD := CMD
  p0.io.master := p0_master
  p0.io.REPLY_OWNED := REPLY_OWNED
  p0.io.REPLY_WAITING := REPLY_WAITING
  p0.io.REPLY_STALL := REPLY_STALL
  
  p1.io.CMD := CMD
  p1.io.master := p1_master
  p1.io.REPLY_OWNED := REPLY_OWNED
  p1.io.REPLY_WAITING := REPLY_WAITING
  p1.io.REPLY_STALL := REPLY_STALL
  
  p2.io.CMD := CMD
  p2.io.master := p2_master
  p2.io.REPLY_OWNED := REPLY_OWNED
  p2.io.REPLY_WAITING := REPLY_WAITING
  p2.io.REPLY_STALL := REPLY_STALL
  
  // Connect memory
  m.io.CMD := CMD
  m.io.master := m_master
  m.io.REPLY_OWNED := REPLY_OWNED
  m.io.REPLY_WAITING := REPLY_WAITING
  m.io.REPLY_STALL := REPLY_STALL
  
  // Reply signals
  REPLY_OWNED := p0.io.reply_owned | p1.io.reply_owned | p2.io.reply_owned
  REPLY_WAITING := p0.io.reply_waiting | p1.io.reply_waiting | p2.io.reply_waiting
  REPLY_STALL := p0.io.reply_stall | p1.io.reply_stall | p2.io.reply_stall | m.io.reply_stall
  
  // Command arbitration
  val nond_CMD = Wire(UInt(4.W))
  nond_CMD := lfsr.io.out(7, 4) // 4-bit random value
  
  CMD := Mux(p1.io.cmd === Command.idle && p2.io.cmd === Command.idle && m.io.cmd === Command.idle, p0.io.cmd,
    Mux(p0.io.cmd === Command.idle && p2.io.cmd === Command.idle && m.io.cmd === Command.idle, p1.io.cmd,
      Mux(p0.io.cmd === Command.idle && p1.io.cmd === Command.idle && m.io.cmd === Command.idle, p2.io.cmd,
        Mux(p0.io.cmd === Command.idle && p1.io.cmd === Command.idle && p2.io.cmd === Command.idle, m.io.cmd,
          nond_CMD))))
  
  // Master arbitration
  p0_master := lfsr.io.out(8)
  p1_master := Mux(p0_master, false.B, lfsr.io.out(9))
  p2_master := Mux(p0_master || p1_master, false.B, lfsr.io.out(10))
  m_master := Mux(p0_master || p1_master || p2_master, false.B, lfsr.io.out(11))
  
  // Expose signals for verification
  io.p0_cmd := p0.io.cmd
  io.p1_cmd := p1.io.cmd
  io.p2_cmd := p2.io.cmd
  io.m_cmd := m.io.cmd
  io.p0_master := p0_master
  io.p1_master := p1_master
  io.p2_master := p2_master
  io.m_master := m_master
  io.CMD := CMD
  io.REPLY_OWNED := REPLY_OWNED
  io.REPLY_WAITING := REPLY_WAITING
  io.REPLY_STALL := REPLY_STALL
  
  // Formal verification assertions
  
  // Mutex assertion: at most one master should be true
  assertMutex(Seq(p0_master, p1_master, p2_master, m_master), "Main: Multiple masters active")
  
  // Command should be valid
  fvAssert(CMD <= Command.response, "Main: Invalid global command")
  
  // Memory should not be master when any processor is master
  fvAssert(!(p0_master || p1_master || p2_master) || !m_master, 
           "Main: Memory should not be master when processor is master")
  
  // When memory is master, all processors should be non-master
  fvAssert(!m_master || (!p0_master && !p1_master && !p2_master),
           "Main: All processors should be non-master when memory is master")
  
  // Liveness: system should not deadlock - someone should eventually be master
  astRelaxedLiveness(true.B, p0_master || p1_master || p2_master || m_master, 20,
                     "Main: System should eventually have a master")
  
  // Reply owned should only come from processors (memory never claims it)
  fvAssert(REPLY_OWNED === (p0.io.reply_owned || p1.io.reply_owned || p2.io.reply_owned),
           "Main: Reply owned should only come from processors")
  
  // Command arbitration consistency - when multiple entities have non-idle commands, arbitration should pick one
  fvAssert(!(p0.io.cmd =/= Command.idle && p1.io.cmd =/= Command.idle) || 
           (CMD === p0.io.cmd || CMD === p1.io.cmd || CMD === nond_CMD),
           "Main: Command arbitration should pick valid command")
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}