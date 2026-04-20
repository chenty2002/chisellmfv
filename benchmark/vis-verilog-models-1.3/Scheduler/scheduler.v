/*
 * This code implements a version of the scheduler example in Milner's book,
 * "Communication and Concurrency", 1989, p. 115.
 * 
 * The basic structure of the system is a token ring.  Each element of the ring, 
 * called a cell, communicates with its "job", and its two nearest neighbor cells.
 * The two properties that the system should satisfy are:
 * 1) the jobs should be started in sequential order (i.e. 1,2, ..., n), but
 *    they may finish in any order, and
 * 2) the ith cell should alternately perform GO and FINISH.
 * 
 * Run with vl2mv options: -k to assign an output non-deterministically when 
 * no explicity assignment is given.
 */

typedef enum {STOPPED, RUNNING} job_status;
typedef enum {GO, ENABLE_NEXT, READY, FINISH} cell_output; 
typedef enum {GO, WAIT_NEXT, FINISH_A, FINISH_B, READY_A, READY_B} cell_state;
typedef enum {ALT_1, ALT_2, BAD} alt_state;
typedef enum {SEQ_1, SEQ_2, BAD} seq_state;

`include "scheduler.v_param"

/*
 * Two state process.  Waits in state STOPPED for a GO signal, then proceeds to
 * state RUNNING.  Once in RUNNING, can go back to STOPPED anytime after receiving
 * the FINISH signal.
 */
module job(clk, in, out); 
input clk; 
input in; 
output out;

cell_output wire in;   
job_status wire out;
job_status reg state;
wire r_state;

initial state = STOPPED;

assign r_state = $ND(0,1);
assign out = state;

always @(posedge clk) begin
    case(state)
        STOPPED:
            begin
	    if (in == GO) 
	        state = RUNNING;
//            out <= STOPPED;
	    end

        RUNNING:
            begin
	    if (in == FINISH) 
		case(r_state)
	        0:state = STOPPED;
	    	1:state = RUNNING;   // non-det transition
		endcase
//            out <= RUNNING;
	    end
     
//        default:;
        endcase;
end
endmodule

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
module cell(clk, job, out, prev, next, init);
input clk;
input job, prev, next, init;
output out;

job_status wire job;
cell_output wire out, prev, next;
cell_state wire init;
cell_state reg state;
wire r_state;

initial state = init;

assign r_state = $ND(0,1);
assign out = 	state==GO?GO:
		state==WAIT_NEXT?ENABLE_NEXT:
		state==FINISH_A?FINISH:
		state==FINISH_B?FINISH:READY;
always @(posedge clk) begin
    case(state)
	GO:
	    begin
	    state = WAIT_NEXT;        
//	    out <= GO;
	    end

	WAIT_NEXT:
	    begin
	    if (next == READY)
		case(r_state)
	        0:state = FINISH_A;
	        1:state = READY_B;   // non-det transition
		endcase
//            out <= ENABLE_NEXT;
	    end

	FINISH_A:
	    begin
	    if (job == STOPPED)
		state = READY_A;
//	    out <= FINISH;
	    end

	FINISH_B:
	    begin
	    if (job == STOPPED)
		state = GO;
//	    out <= FINISH;
	    end

	READY_A:
	    begin
	    if (prev == ENABLE_NEXT) 
		state = GO;
//	    out <= READY;
	    end

	READY_B:
	    begin
	    if (prev == ENABLE_NEXT) 
		state = FINISH_B;
//	    out <= READY;
	    end
	
//	default:;
	endcase;
end
endmodule

/*
 * Task: Accepted language is ((in=GO)(in=FINISH)+)w
 */
module alt(clk, in);
input clk, in;
cell_output wire in;

alt_state reg state;

initial state = ALT_1;

always @(posedge clk) begin
    case(state)
        ALT_1:
            begin
	    if (in == GO) 
	        state = ALT_2;
	    end

        ALT_2:
            begin
	    if (in == GO) 
	        state = BAD;
	    else if (in == FINISH)
		state = ALT_1;
	    end

	BAD:
	    state = BAD;
     
//        default:;
        endcase;
end
endmodule


/*
 * Task: Accepted language is ((in1=GO)(in2=GO))w
 */
module sequence(clk, in1, in2);
input clk, in1, in2;
cell_output wire in1, in2;

seq_state reg state;

initial state = SEQ_1;

always @(posedge clk) begin
    case(state)
        SEQ_1:
            begin
	    if (in1 == GO) 
	        state = SEQ_2;
	    else if (in2 == GO)
		state = BAD;
	    end

        SEQ_2:
            begin
	    if (in2 == GO) 
	        state = SEQ_1;
	    else if (in1 == GO)
		state = BAD;
	    end

	BAD:
	    state = BAD;
     
//        default:;
        endcase;
end
endmodule

