// This model describes timeout handling for a process that spawns
// child processes.  The process and its children may or may not be
// scheduled.  Hence, there is a distinction between CPU time and
// elapsed time.  The model has two inputs:
//
//   1. cpuTime: The time the process is supposed to take
//   2. timeOutValue: the maximum time allowed.
//
// If timeOutValue is > 0, the process sets an alarm for timeOutValue
// later.  This alarm is in terms of elapsed time.  Hence, when the
// alarm rings, a new alarm may have to be set for the residual CPU
// time.
//
// A child process's time cannot exceed the residual CPU time of
// the parent.  That is, the child is given a timeout in terms of
// CPU time, and it honors the request.
//
// The CPU time of the child is added to the CPU time of the parent only
// when the child terminates.
//
// We want to prove that when the process terminates, the CPU time it
// has spent, including that of its children, is the minimum of the
// required CPU time and the timeout value.
//
// The above assumptions are consistent with what faced by a C program
// that uses the Unix library functions signal, alarm, system, and times.  
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {START, COMPUTING, SPAWNING, WAITING, FINISH} ProcState;

module timeout(clock, cpuTime, timeOutValue);
    input clock;
    input [MSB:0] cpuTime, timeOutValue;
    parameter MSB = 3;

    reg [MSB:0] remainingCpuTime;
    reg [MSB:0] timeToAlarm;
    reg [MSB:0] childCpuTime;
    reg [MSB:0] remainingChildTime;
    reg 	ring;
    ProcState reg state;

    initial begin
	state = START;
	remainingCpuTime = 0;
	timeToAlarm = 0;
	childCpuTime = 0;
	remainingChildTime = 0;
	ring = 0;
    end

    always @ (posedge clock) begin
	// ring will be 1 for one cycle when timeToAlarm reaches 0.
	ring = timeToAlarm == 1;
    end

    wire scheduled;
    assign scheduled = $ND(0,1);
    wire   runChild;
    assign runChild = $ND(0,1);

    always @ (posedge clock) begin
	// Each clock cycle represents a time slice.  Since timeToAlarm
	// is an elapsed time, it is always decremented.
	if (timeToAlarm > 0) begin
	    timeToAlarm = timeToAlarm - 1;
	end
	if (ring) begin
	    // Signal handling.
	    if (remainingCpuTime > 0) begin
		timeToAlarm = remainingCpuTime; // restart timeout
	    end else begin
		state = FINISH;	// call longjmp
	    end
	end else if (scheduled) begin
	  case (state)
	    START: begin
		// Set the alarm if required and compute how much time
		// this process and its children have to run.
		if (timeOutValue > 0) begin
		    timeToAlarm = timeOutValue; // call alarm
		    if (timeOutValue > cpuTime)
		      remainingCpuTime = cpuTime;
		    else
		      remainingCpuTime = timeOutValue;
		end else begin
		    remainingCpuTime = cpuTime;
		end
		if (remainingCpuTime == 0)
		  state = FINISH;
		else
		  state = COMPUTING;
	    end
	    COMPUTING: begin
		remainingCpuTime = remainingCpuTime - 1;
		if (remainingCpuTime == 0) begin
		    state = FINISH;
		end else begin
		    if (runChild) begin
			state = SPAWNING;
		    end
		end
	    end
	    SPAWNING: begin
		remainingCpuTime = remainingCpuTime - 1;
		if (remainingCpuTime == 0) begin
		    state = FINISH;
		end else begin
		    // The child is given a timeout that is less than
		    // the remaining CPU time, and it is only started
		    // if the allotted time is greater than 0.
		    if (cpuTime < remainingCpuTime) begin
			childCpuTime = cpuTime;
		    end else begin
			childCpuTime = remainingCpuTime - 1;
		    end
		    if (childCpuTime > 0) begin
			remainingChildTime = childCpuTime;
			state = WAITING; // call system
		    end else begin
			state = COMPUTING;
		    end
		end
	    end
	    WAITING: begin
		remainingChildTime = remainingChildTime - 1;
		if (remainingChildTime == 0) begin
		    // The child's CPU time is added to the parent's CPU
		    // time only when the child terminates.
		    remainingCpuTime = remainingCpuTime - childCpuTime;
		    // If there is an alarm pending, adjust its value.
		    // Since the alarm may have gone off while the child
		    // was executing, it may have been set for too far
		    // in the future because the information on the
		    // CPU time spent by the child was not available.
		    if (timeToAlarm > 0) begin
			timeToAlarm = remainingCpuTime; // call alarm
		    end
		    state = COMPUTING;
		end
	    end
	    FINISH: begin
		// Disable timeout in case it hasn't expired.
		timeToAlarm = 0; // call alarm
	    end
	  endcase // case(state)
	end // if (scheduled)
    end

    // The following code is for checking some properties.

    reg [MSB:0] realCpuTime, saveCpuTime, saveTimeOut;
    reg 	latchedSched;
    wire 	earlyTermination;

    assign 	earlyTermination =
		(saveTimeOut > 0) && (saveTimeOut < saveCpuTime);

    initial begin
	latchedSched = 0;
	realCpuTime = 0;
	saveCpuTime = 0;
	saveTimeOut = 0;
    end

    always @ (posedge clock) begin
	// We want to impose the constraint that the process is infinitely
	// often scheduled to do real work, rather than just to handle the
	// SIGALRM signal.
	latchedSched = scheduled && !ring;
	// Save the inputs so that we can later check for correctness.
	// When state=FINISH, realCpuTime should equal one of these two.
	if (state == START && scheduled) begin
	    saveCpuTime = cpuTime;
	    saveTimeOut = timeOutValue;
	end
	// CPU is charged to the process only in some cases.  The one
	// simplifying assumption is that signal handling takes no time.
	// If it is removed, then handling signals while children are
	// running may lead to using more CPU than allotted.
	if (scheduled && !ring && state != START && state != FINISH) begin
	    realCpuTime = realCpuTime + 1;
	end
    end

endmodule // timeout
