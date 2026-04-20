// Model of Peterson's algorithm for mutual exclusion of two processes.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {L0, L1, L2, L3, L4, L5} loc;

module peterson(clock,select,pause);
    input     clock;
    input     select;
    input     pause;

    reg       interested[0:1];
    reg [0:0] turn, self;
    loc reg   pc[0:1];

    initial begin
	pc[0] = L0; pc[1] = L0;
	interested[0] = 0; interested[1] = 0;
	turn = 0;
	self = 0;
    end

    always @ (posedge clock) begin
	self = select;
	case (pc[self])
	  L0: if (!pause) pc[self] = L1;	// noncritical section
	  L1: begin interested[self] = 1; pc[self] = L2; end
	  L2: begin turn = ~self; pc[self] = L3; end
	  L3: if (!interested[~self] || turn == self) pc[self] = L4;
	  L4: if (!pause) pc[self] = L5;	// critical section
	  L5: begin interested[self] = 0; pc[self] = L0; end
	endcase
    end

endmodule // peterson
