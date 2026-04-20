// Model fot the priority inversion problem of the Pathfinder.
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
//
// The problem is the following:
// Suppose the meteo task starts and obtains exclusive access to the bus.
// Next the communications task starts and preempts the meteo task.
// At this point the bus management task has to wait for the communications
// task to finish because the meteo task cannot release the bus unless it
// is allowed to finish. Since the communications task takes a long time,
// the watchdog is led to believe that something is wrong, and resets
// the system.
//
// To avoid modeling the watchdog processor and the "long" delay, we adopt
// the following technique: We make the termination of the communications
// task depend on the termination of the bus management task. In this way,
// we create the following situation:
// 1. The communication task cannot finish until the bus management task
//    is idle;
// 2. If indeed the bus management task has to wait for the communications
//    task to finish, then we have a circular dependency and the system
//    deadlocks.
// Therefore we have transformed our problem into one of deadlock detection.
// We check for the liveness of the meteo task. This check fails, indicating 
// that the bus management task may indeed have to wait for the end of the
// communications task.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

module pathfinder(clock,start);
    input	clock;
    input [2:0]	start;

    wire [1:0]	busRequest;
    wire [1:0]	busGrant;
    wire [2:0]	ready;
    wire [2:0]	run;

    busTask   meteo(clock,start[0],ready[0],run[0],busGrant[0],busRequest[0]);
    comm         cm(clock,start[1],ready[2],ready[1],run[1]);
    busTask busMgmt(clock,start[2],ready[2],run[2],busGrant[1],busRequest[1]);

    busArbiter   ba(clock,busRequest,busGrant);
    scheduler   sch(ready,run);

endmodule // pathfinder


module busTask(clock,start,ready,run,grant,request);
    input     clock;
    input     start;
    output    ready;
    input     run;
    input     grant;
    output    request;

    reg [1:0] state;

    parameter idle = 2'd0,
	      locking = 2'd1,
	      busy = 2'd2,
	      unlocking = 2'd3;

    initial begin
	state = idle;
    end

    always @ (posedge clock) begin
	if (run) begin
	    case (state)
	      idle:
		  if (start)
		      state = locking;
	      locking:
		  if (grant)
		      state = busy;
	      busy:
		  if (start)
		      state = unlocking;
	      unlocking:
		  state = idle;
	    endcase // case (state)
	end // if (run)
    end // always @ (posedge clock)

    assign request = state == locking ||
	state == busy || state == idle && start;
    assign ready = state != idle;

endmodule // busTask


// Communications task model. Notice the stopb input that allows the
// task to return to the idle state.
module comm(clock,start,stopb,ready,run);
    input  clock;
    input  start;
    input  stopb;
    output ready;
    input  run;

    reg	   state;

    parameter idle = 1'd0,
	      busy = 1'd1;

    initial begin
	state = idle;
    end

    always @ (posedge clock) begin
	if (run) begin
	    case (state)
	      idle:
		  if (start)
		      state = busy;
	      busy:
		  if (!stopb)
		      state = idle;
	    endcase // case (state)
	end // if (run)
    end // always @ (posedge clock)

    assign ready = state != idle;

endmodule // comm


// This synchronous bus arbiter grants locks to the bus to requestors.
// Two requestors are connected to the arbiter. The one with index 1 has
// precedence over the one with index 0.
module busArbiter(clock,request,grant);
    input	 clock;
    input [1:0]	 request;
    output [1:0] grant;

    reg		 lock;
    reg		 locker;

    initial begin
	lock = 0;		// bus free (0) or locked (1)
	locker = 0;		// who is locking the bus
    end

    always @ (posedge clock) begin
	if (lock) begin
	    if (locker == 0 && !request[0] || locker == 1 && !request[1]) begin
		lock = 0;
	    end
	end else begin
	    if (request[1]) begin
		lock = 1;
		locker = 1;
	    end else if (request[0]) begin
		lock = 1;
		locker = 0;
	    end
	end
    end // always @ (posedge clock)

    assign grant[0] = lock & ~locker;
    assign grant[1] = lock &  locker;

endmodule // busArbiter


// This is an extremely simple model of a preemptive scheduler.
// Process 2 runs on a separate processor, and is therefore always enabled.
// Processes 1 and 0 share the same processor. Process 1 is always enabled.
// Process 0 is enabled only when Process 1 is not running.
module scheduler(ready,run);
    input [2:0]	ready;
    output [2:0]	run;

    assign run[2] = 1'd1;	// if all processes on one processor:
    assign run[1] = 1'd1;	// run[1] = ~ready[2];
    assign run[0] = ~ready[1];	// run[0] = ~ready[2] & ~ready[1];

endmodule // scheduler
