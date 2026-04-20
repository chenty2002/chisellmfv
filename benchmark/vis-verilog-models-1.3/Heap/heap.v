// Model of a heap.
//
// The heap holds WORDS keys, each of BITS bits ordered in ascending order.
// Keys may repeat.  The key in first position is always a minimum key.
//
// The heap supports 4 operations:
//
// - NOOP: remain idle
// - PUSH: add a key to the heap if it is not full
// - POP : remove the first element from the heap if it is not empty
// - TEST: check the heap property
//
// When ready is asserted, dout gives the minimum value of the keys held
// in the heap.  Commands are accepted only when ready is asserted.
//
// The number of bits in a key is the logarithm of the number
// of slots in the heap, so that all keys may be distinct.

// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {NOOP, PUSH, POP, TEST} Op;
typedef enum {IDLE, PUSH1, PUSH2, POP1, POP2, POP3, TEST1, TEST2} State;

module heap(clock,cmd,din,dout,ready,full,empty,error);
    parameter      BITS = 2;
    parameter 	   WORDS = 4;
    parameter 	   MSW = WORDS-1;
    parameter 	   MSB = BITS-1;
    input 	   clock;
    input 	   cmd;
    input [MSB:0]  din;
    output [MSB:0] dout;
    output 	   ready;
    output 	   full;
    output 	   empty;
    output 	   error;

    Op wire        cmd;

    reg [BITS:0]   nitems, posn;
    reg [MSB:0]    h0, h1, h2;
    reg [MSB:0]    h [0:MSW];
    State reg      state;
    reg 	   error;
    wire [BITS:0]  prnt, lft, rght;
    integer 	   j;

    initial begin
	state = IDLE;
	nitems = 0;
	posn = 0;
	h0 = 0;
	h1 = 0;
	h2 = 0;
	error = 0;
	for (j = 0; j < WORDS; j = j+1)
	  h[j] = 0;
    end

    assign dout = h[0];
    assign ready = state == IDLE;
    assign full = nitems == WORDS;
    assign empty = nitems == 0;

    function [BITS:0] parent;
	input [BITS:0] i;
	reg [BITS:0] tmp;
	begin: _parent
	    tmp = i-1;
	    parent = {1'b0,tmp[BITS:1]};
	end
    endfunction // parent

    function [BITS:0] left;
	input [BITS:0] i;
	begin: _left
	    left = {i[BITS-1:0],1'b0} + 1;
	end
    endfunction // left

    function [BITS:0] right;
	input [BITS:0] i;
	reg [BITS:0] tmp;
	begin: _right
	    tmp = i+1;
	    right = {tmp[BITS-1:0],1'b0};
	end
    endfunction // right

    always @ (posedge clock) begin
	case (state)
	  IDLE:
	    case (cmd)
	      PUSH: if (full == 0) begin
		  posn = nitems;
		  h0 = din;
		  nitems = nitems + 1;
		  state = PUSH1;
	      end
	      POP: if (empty == 0) begin
		  nitems = nitems - 1;
		  posn = 0;
		  h0 = h[nitems]; // watch out for the extra bit!
		  h[0] = h0;
		  state = POP1;
	      end
	      TEST: begin
		  posn = 1;
		  error = 0;
		  state = TEST1;
	      end

	      NOOP: ;

	    endcase // case(cmd)

	  PUSH1: begin
	      h1 = h[prnt];
	      state = PUSH2;
	  end

          PUSH2: if (posn == 0 || h1 <= h0) begin
	      h[posn] = h0;
	      state = IDLE;
	  end else begin
	      h[posn] = h1;
	      posn = prnt;
	      state = PUSH1;
	  end

	  POP1: begin
	      h1 = h[lft];
	      state = POP2;
	  end

	  POP2: begin
	      h2 = h[rght];
	      state = POP3;
	  end

	  POP3: begin
	      if (lft < nitems && h1 < h0 &&
		  (rght >= nitems || h1 <= h2)) begin
		  h[posn] = h1;
		  posn = lft;
		  state = POP1;
	      end else if (rght < nitems && h2 < h0) begin
		  h[posn] = h2;
		  posn = rght;
		  state = POP1;
	      end else begin
		  h[posn] = h0;
		  state = IDLE;
	      end
	  end

	  TEST1: if (posn >= nitems) begin
	      state = IDLE;
	  end else begin
	      h1 = h[prnt];
	      state = TEST2;
	  end

	  TEST2: if (h[posn] < h1) begin
	      error = 1;
	      state = IDLE;
	  end else begin
	      posn = posn + 1;
	      state = TEST1;
	  end

	endcase // case(state)
    end

    assign prnt = parent(posn);
    assign lft = left(posn);
    assign rght = right(posn);

endmodule // heap
