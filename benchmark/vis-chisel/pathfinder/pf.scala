package llmverify

import chisel3._
import chisel3.util._

// Model for the priority inversion problem of the Pathfinder.
// Three concurrent tasks run in this model of the pathfinder:
// 1. A low priority task that gathers meteorological data;
// 2. A long running medium priority communications task;
// 3. A high priority bus management task.
// Tasks 1 and 3 use the system bus. When they want to access the bus,
// they first obtain a lock for it.
// Tasks 1 and 2 run on the same processor. Since the communications task is
// higher priority, it preempts the meteo task.
// There is a watchdog processor (not modeled here) that resets the system
// if the bus management task is not performed regularly.

class pathfinder extends Module {
  val io = IO(new Bundle {
    val start = Input(UInt(3.W))
    // Add outputs to preserve internal signals
    val busRequest = Output(UInt(2.W))
    val busGrant = Output(UInt(2.W))
    val ready = Output(UInt(3.W))
    val run = Output(UInt(3.W))
  })
  
  val meteo = Module(new busTask())
  meteo.io.start := io.start(0)
  meteo.io.grant := io.busGrant(0)
  
  val cm = Module(new comm())
  cm.io.start := io.start(1)
  cm.io.stopb := io.ready(2)
  
  val busMgmt = Module(new busTask())
  busMgmt.io.start := io.start(2)
  busMgmt.io.grant := io.busGrant(1)
  
  val ba = Module(new busArbiter())
  ba.io.request := io.busRequest
  
  val sch = Module(new scheduler())
  sch.io.ready := io.ready
  
  // Construct busRequest from individual request signals
  io.busRequest := Cat(busMgmt.io.request, meteo.io.request)
  
  // Connect bus grant from arbiter
  io.busGrant := ba.io.grant
  
  // Construct ready from individual ready signals
  io.ready := Cat(busMgmt.io.ready, cm.io.ready, meteo.io.ready)
  
  // Connect run signals from scheduler to modules
  val run = sch.io.run
  meteo.io.run := run(0)
  cm.io.run := run(1)
  busMgmt.io.run := run(2)
  io.run := run
}

class busTask extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val ready = Output(Bool())
    val run = Input(Bool())
    val grant = Input(Bool())
    val request = Output(Bool())
  })
  
  // State machine states
  val idle :: locking :: busy :: unlocking :: Nil = Enum(4)
  val state = RegInit(idle)
  
  when(io.run) {
    switch(state) {
      is(idle) {
        when(io.start) {
          state := locking
        }
      }
      is(locking) {
        when(io.grant) {
          state := busy
        }
      }
      is(busy) {
        when(io.start) {
          state := unlocking
        }
      }
      is(unlocking) {
        state := idle
      }
    }
  }
  
  io.request := (state === locking) || (state === busy) || (state === idle && io.start)
  io.ready := state =/= idle
}

// Communications task model. Notice the stopb input that allows the
// task to return to the idle state.
class comm extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val stopb = Input(Bool())
    val ready = Output(Bool())
    val run = Input(Bool())
  })
  
  // State machine states
  val idle :: busy :: Nil = Enum(2)
  val state = RegInit(idle)
  
  when(io.run) {
    switch(state) {
      is(idle) {
        when(io.start) {
          state := busy
        }
      }
      is(busy) {
        when(!io.stopb) {
          state := idle
        }
      }
    }
  }
  
  io.ready := state =/= idle
}

// This synchronous bus arbiter grants locks to the bus to requestors.
// Two requestors are connected to the arbiter. The one with index 1 has
// precedence over the one with index 0.
class busArbiter extends Module {
  val io = IO(new Bundle {
    val request = Input(UInt(2.W))
    val grant = Output(UInt(2.W))
  })
  
  val lock = RegInit(false.B)
  val locker = RegInit(false.B)  // false = 0, true = 1
  
  when(lock) {
    when((!locker && !io.request(0)) || (locker && !io.request(1))) {
      lock := false.B
    }
  }.otherwise {
    when(io.request(1)) {
      lock := true.B
      locker := true.B
    }.elsewhen(io.request(0)) {
      lock := true.B
      locker := false.B
    }
  }
  
  io.grant := Cat(lock && locker, lock && !locker)
}

// This is an extremely simple model of a preemptive scheduler.
// Process 2 runs on a separate processor, and is therefore always enabled.
// Processes 1 and 0 share the same processor. Process 1 is always enabled.
// Process 0 is enabled only when Process 1 is not running.
class scheduler extends Module {
  val io = IO(new Bundle {
    val ready = Input(UInt(3.W))
    val run = Output(UInt(3.W))
  })
  
  io.run := Cat(true.B, true.B, !io.ready(1))
}

object VerilogGenerator extends App {
  emitVerilog(new pathfinder(), args)
}