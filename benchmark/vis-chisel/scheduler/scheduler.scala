package llmverify

import chisel3._
import chisel3.util._

// Enum definitions
object JobStatus extends ChiselEnum {
  val STOPPED, RUNNING = Value
}

object CellOutput extends ChiselEnum {
  val GO, ENABLE_NEXT, READY, FINISH = Value
}

object CellState extends ChiselEnum {
  val GO, WAIT_NEXT, FINISH_A, FINISH_B, READY_A, READY_B = Value
}

object AltState extends ChiselEnum {
  val ALT_1, ALT_2, BAD = Value
}

object SeqState extends ChiselEnum {
  val SEQ_1, SEQ_2, BAD = Value
}

/*
 * Two state process.  Waits in state STOPPED for a GO signal, then proceeds to
 * state RUNNING.  Once in RUNNING, can go back to STOPPED anytime after receiving
 * the FINISH signal.
 */
class Job extends Module {
  val io = IO(new Bundle {
    val in = Input(CellOutput())
    val out = Output(JobStatus())
    // Add output to preserve internal state
    val state_out = Output(JobStatus())
  })
  
  val state = RegInit(JobStatus.STOPPED)
  
  // Non-deterministic random bit for transitions
  val r_state = Wire(UInt(1.W))
  r_state := 0.U // Can be overridden for testing
  
  io.out := state
  io.state_out := state
  
  switch(state) {
    is(JobStatus.STOPPED) {
      when(io.in === CellOutput.GO) {
        state := JobStatus.RUNNING
      }
    }
    is(JobStatus.RUNNING) {
      when(io.in === CellOutput.FINISH) {
        when(r_state === 0.U) {
          state := JobStatus.STOPPED
        }.otherwise {
          state := JobStatus.RUNNING // non-det transition
        }
      }
    }
  }
}

/*
 * Six state process.  Order of events from GO state are:
 * 1) emit GO to enable the cell's corresponding job
 * 2) emit ENABLE_NEXT to pass the token to the next cell
 * 3) emit FINISH to tell the job that it can stop anytime
 * 4) emit READY to tell the previous cell that this cell
 *    is ready to accept the token.
 *
 * Steps 3 and 4 can non-deterministically be performed in
 * either order.
 */
class Cell extends Module {
  val io = IO(new Bundle {
    val job = Input(JobStatus())
    val prev = Input(CellOutput())
    val next = Input(CellOutput())
    val init = Input(CellState())
    val out = Output(CellOutput())
    // Add output to preserve internal state
    val state_out = Output(CellState())
  })
  
  // Initialize state with a default, then override if init signal is provided
  val state = RegInit(CellState.GO)
  
  // Handle initialization - set state to init value on reset or when needed
  // This is a workaround for not being able to use io.init in RegInit
  when(reset.asBool) {
    state := io.init
  }
  
  // Non-deterministic random bit for transitions
  val r_state = Wire(UInt(1.W))
  r_state := 0.U // Can be overridden for testing
  
  // Output logic based on current state using switch statement
  io.out := CellOutput.READY
  switch(state) {
    is(CellState.GO) {
      io.out := CellOutput.GO
    }
    is(CellState.WAIT_NEXT) {
      io.out := CellOutput.ENABLE_NEXT
    }
    is(CellState.FINISH_A) {
      io.out := CellOutput.FINISH
    }
    is(CellState.FINISH_B) {
      io.out := CellOutput.FINISH
    }
    is(CellState.READY_A) {
      io.out := CellOutput.READY
    }
    is(CellState.READY_B) {
      io.out := CellOutput.READY
    }
  }
  
  io.state_out := state
  
  switch(state) {
    is(CellState.GO) {
      state := CellState.WAIT_NEXT
    }
    is(CellState.WAIT_NEXT) {
      when(io.next === CellOutput.READY) {
        when(r_state === 0.U) {
          state := CellState.FINISH_A
        }.otherwise {
          state := CellState.READY_B // non-det transition
        }
      }
    }
    is(CellState.FINISH_A) {
      when(io.job === JobStatus.STOPPED) {
        state := CellState.READY_A
      }
    }
    is(CellState.FINISH_B) {
      when(io.job === JobStatus.STOPPED) {
        state := CellState.GO
      }
    }
    is(CellState.READY_A) {
      when(io.prev === CellOutput.ENABLE_NEXT) {
        state := CellState.GO
      }
    }
    is(CellState.READY_B) {
      when(io.prev === CellOutput.ENABLE_NEXT) {
        state := CellState.FINISH_B
      }
    }
  }
}

/*
 * Task: Accepted language is ((in=GO)(in=FINISH)+)w
 */
class Alt extends Module {
  val io = IO(new Bundle {
    val in = Input(CellOutput())
    // Add output to preserve internal state
    val state_out = Output(AltState())
  })
  
  val state = RegInit(AltState.ALT_1)
  
  io.state_out := state
  
  switch(state) {
    is(AltState.ALT_1) {
      when(io.in === CellOutput.GO) {
        state := AltState.ALT_2
      }
    }
    is(AltState.ALT_2) {
      when(io.in === CellOutput.GO) {
        state := AltState.BAD
      }.elsewhen(io.in === CellOutput.FINISH) {
        state := AltState.ALT_1
      }
    }
    is(AltState.BAD) {
      state := AltState.BAD
    }
  }
}

/*
 * Task: Accepted language is ((in1=GO)(in2=GO))w
 */
class Sequence extends Module {
  val io = IO(new Bundle {
    val in1 = Input(CellOutput())
    val in2 = Input(CellOutput())
    // Add output to preserve internal state
    val state_out = Output(SeqState())
  })
  
  val state = RegInit(SeqState.SEQ_1)
  
  io.state_out := state
  
  switch(state) {
    is(SeqState.SEQ_1) {
      when(io.in1 === CellOutput.GO) {
        state := SeqState.SEQ_2
      }.elsewhen(io.in2 === CellOutput.GO) {
        state := SeqState.BAD
      }
    }
    is(SeqState.SEQ_2) {
      when(io.in2 === CellOutput.GO) {
        state := SeqState.SEQ_1
      }.elsewhen(io.in1 === CellOutput.GO) {
        state := SeqState.BAD
      }
    }
    is(SeqState.BAD) {
      state := SeqState.BAD
    }
  }
}

// Top-level module to instantiate all components for testing
class SchedulerTop extends Module {
  val io = IO(new Bundle {
    // Job interface
    val job_in = Input(CellOutput())
    val job_out = Output(JobStatus())
    val job_state = Output(JobStatus())
    
    // Cell interface
    val cell_job = Input(JobStatus())
    val cell_prev = Input(CellOutput())
    val cell_next = Input(CellOutput())
    val cell_init = Input(CellState())
    val cell_out = Output(CellOutput())
    val cell_state = Output(CellState())
    
    // Alt monitor interface
    val alt_in = Input(CellOutput())
    val alt_state = Output(AltState())
    
    // Sequence monitor interface
    val seq_in1 = Input(CellOutput())
    val seq_in2 = Input(CellOutput())
    val seq_state = Output(SeqState())
  })
  
  // Instantiate modules
  val job = Module(new Job())
  val cell = Module(new Cell())
  val alt = Module(new Alt())
  val sequence = Module(new Sequence())
  
  // Connect job module
  job.io.in := io.job_in
  io.job_out := job.io.out
  io.job_state := job.io.state_out
  
  // Connect cell module
  cell.io.job := io.cell_job
  cell.io.prev := io.cell_prev
  cell.io.next := io.cell_next
  cell.io.init := io.cell_init
  io.cell_out := cell.io.out
  io.cell_state := cell.io.state_out
  
  // Connect alt monitor
  alt.io.in := io.alt_in
  io.alt_state := alt.io.state_out
  
  // Connect sequence monitor
  sequence.io.in1 := io.seq_in1
  sequence.io.in2 := io.seq_in2
  io.seq_state := sequence.io.state_out
}

object VerilogGenerator extends App {
  // Generate individual modules
  chisel3.emitVerilog(new Job(), Array("--target-dir", "generated/job"))
  chisel3.emitVerilog(new Cell(), Array("--target-dir", "generated/cell"))
  chisel3.emitVerilog(new Alt(), Array("--target-dir", "generated/alt"))
  chisel3.emitVerilog(new Sequence(), Array("--target-dir", "generated/sequence"))
  
  // Generate top-level module
  chisel3.emitVerilog(new SchedulerTop(), Array("--target-dir", "generated/scheduler"))
}