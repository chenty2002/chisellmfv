package llmverify

import chisel3._
import chisel3.util._

object ProcState extends ChiselEnum {
  val START, COMPUTING, SPAWNING, WAITING, FINISH = Value
}

class timeout extends Module {
  val io = IO(new Bundle {
    val cpuTime = Input(UInt(4.W))
    val timeOutValue = Input(UInt(4.W))
    
    // Non-deterministic inputs for verification
    val scheduled = Input(Bool())
    val runChild = Input(Bool())
    
    // Outputs for verification and debugging
    val state = Output(ProcState())
    val remainingCpuTime = Output(UInt(4.W))
    val timeToAlarm = Output(UInt(4.W))
    val childCpuTime = Output(UInt(4.W))
    val remainingChildTime = Output(UInt(4.W))
    val ring = Output(Bool())
    val realCpuTime = Output(UInt(4.W))
    val saveCpuTime = Output(UInt(4.W))
    val saveTimeOut = Output(UInt(4.W))
    val latchedSched = Output(Bool())
    val earlyTermination = Output(Bool())
  })
  
  // State registers
  val state = RegInit(ProcState.START)
  val remainingCpuTime = RegInit(0.U(4.W))
  val timeToAlarm = RegInit(0.U(4.W))
  val childCpuTime = RegInit(0.U(4.W))
  val remainingChildTime = RegInit(0.U(4.W))
  val ring = RegInit(false.B)
  
  // Checking registers
  val realCpuTime = RegInit(0.U(4.W))
  val saveCpuTime = RegInit(0.U(4.W))
  val saveTimeOut = RegInit(0.U(4.W))
  val latchedSched = RegInit(false.B)
  
  // Ring logic - will be 1 for one cycle when timeToAlarm reaches 0
  ring := (timeToAlarm === 1.U)
  
  // Early termination check
  val earlyTermination = (saveTimeOut > 0.U) && (saveTimeOut < saveCpuTime)
  
  // Main state machine logic
  when(io.scheduled) {
    // Each clock cycle represents a time slice. Since timeToAlarm
    // is an elapsed time, it is always decremented.
    when(timeToAlarm > 0.U) {
      timeToAlarm := timeToAlarm - 1.U
    }
    
    when(ring) {
      // Signal handling.
      when(remainingCpuTime > 0.U) {
        timeToAlarm := remainingCpuTime // restart timeout
      } .otherwise {
        state := ProcState.FINISH // call longjmp
      }
    } .otherwise {
      switch(state) {
        is(ProcState.START) {
          // Set the alarm if required and compute how much time
          // this process and its children have to run.
          when(io.timeOutValue > 0.U) {
            timeToAlarm := io.timeOutValue // call alarm
            when(io.timeOutValue > io.cpuTime) {
              remainingCpuTime := io.cpuTime
            } .otherwise {
              remainingCpuTime := io.timeOutValue
            }
          } .otherwise {
            remainingCpuTime := io.cpuTime
          }
          when(remainingCpuTime === 0.U) {
            state := ProcState.FINISH
          } .otherwise {
            state := ProcState.COMPUTING
          }
        }
        is(ProcState.COMPUTING) {
          remainingCpuTime := remainingCpuTime - 1.U
          when(remainingCpuTime === 0.U) {
            state := ProcState.FINISH
          } .otherwise {
            when(io.runChild) {
              state := ProcState.SPAWNING
            }
          }
        }
        is(ProcState.SPAWNING) {
          remainingCpuTime := remainingCpuTime - 1.U
          when(remainingCpuTime === 0.U) {
            state := ProcState.FINISH
          } .otherwise {
            // The child is given a timeout that is less than
            // the remaining CPU time, and it is only started
            // if the allotted time is greater than 0.
            when(io.cpuTime < remainingCpuTime) {
              childCpuTime := io.cpuTime
            } .otherwise {
              childCpuTime := remainingCpuTime - 1.U
            }
            when(childCpuTime > 0.U) {
              remainingChildTime := childCpuTime
              state := ProcState.WAITING // call system
            } .otherwise {
              state := ProcState.COMPUTING
            }
          }
        }
        is(ProcState.WAITING) {
          remainingChildTime := remainingChildTime - 1.U
          when(remainingChildTime === 0.U) {
            // The child's CPU time is added to the parent's CPU
            // time only when the child terminates.
            remainingCpuTime := remainingCpuTime - childCpuTime
            // If there is an alarm pending, adjust its value.
            // Since the alarm may have gone off while the child
            // was executing, it may have been set for too far
            // in the future because the information on the
            // CPU time spent by the child was not available.
            when(timeToAlarm > 0.U) {
              timeToAlarm := remainingCpuTime // call alarm
            }
            state := ProcState.COMPUTING
          }
        }
        is(ProcState.FINISH) {
          // Disable timeout in case it hasn't expired.
          timeToAlarm := 0.U // call alarm
        }
      }
    }
  }
  
  // Checking logic
  // We want to impose the constraint that the process is infinitely
  // often scheduled to do real work, rather than just to handle the
  // SIGALRM signal.
  latchedSched := io.scheduled && !ring
  
  // Save the inputs so that we can later check for correctness.
  // When state=FINISH, realCpuTime should equal one of these two.
  when(state === ProcState.START && io.scheduled) {
    saveCpuTime := io.cpuTime
    saveTimeOut := io.timeOutValue
  }
  
  // CPU is charged to the process only in some cases. The one
  // simplifying assumption is that signal handling takes no time.
  // If it is removed, then handling signals while children are
  // running may lead to using more CPU than allotted.
  when(io.scheduled && !ring && state =/= ProcState.START && state =/= ProcState.FINISH) {
    realCpuTime := realCpuTime + 1.U
  }
  
  // Connect outputs
  io.state := state
  io.remainingCpuTime := remainingCpuTime
  io.timeToAlarm := timeToAlarm
  io.childCpuTime := childCpuTime
  io.remainingChildTime := remainingChildTime
  io.ring := ring
  io.realCpuTime := realCpuTime
  io.saveCpuTime := saveCpuTime
  io.saveTimeOut := saveTimeOut
  io.latchedSched := latchedSched
  io.earlyTermination := earlyTermination
}

object VerilogGenerator extends App {
  emitVerilog(new timeout(), args)
}