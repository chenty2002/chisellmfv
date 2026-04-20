// Model of Dekker's algorithm for mutual exclusion of two processes.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {L0, L1, L2, L3, L4, L5, L6} loc;

module dekker(clock,select,pause);
    input     clock;
    input     select;
    input     pause;

    reg       c[0:1];
    reg [0:0] turn, self;
    loc reg   pc[0:1];

    initial begin
	pc[0] = L0; pc[1] = L0;
	c[0] = 1; c[1] = 1;
	turn = $ND(0,1);
	self = select;
    end

    always @ (posedge clock) begin
	self = select;
	case (pc[self])
	  L0: if (!pause) pc[self] = L1;	// noncritical section
	  L1: begin c[self] = 0; pc[self] = L2; end
	  L2: if (c[~self] == 1) pc[self] = L5; else pc[self] = L3;
	  L3:
	    if (turn == self)
	      pc[self] = L2;
	    else begin
		c[self] = 1;
		pc[self] = L4;
	    end
	  L4: if (turn == self) begin c[self] = 0; pc[self] = L2; end
	  L5: if (!pause) pc[self] = L6;	// critical section
	  L6: begin c[self] = 1; turn = ~self; pc[self] = L0; end
	endcase
    end

endmodule // dekker
