// Model of Barrier algorithm for synchronization of two processes.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {L0, L1, L2, L3, L4, L5, L6} loc;

module barrier(clock,select,pause);
    input     clock;
    input     select;
    input     pause;

    reg [0:0] rel, self;
    loc reg   pc[0:1];
    reg [1:0] count;

    initial begin
	pc[0] = L0; pc[1] = L0;
	rel = $ND(0,1);
	count = 0;
	self = select;
    end

    always @ (posedge clock) begin
	self = select;
	case (pc[self])
	  L0: if (!pause) pc[self] = L1;	// noncritical section
	  L1: begin rel = 0; pc[self] = L2; end
	  L2: begin count = count + 1; pc[self] = L3; end
	  L3: if (count == 2) pc[self] = L4; else pc[self] = L6;
	  L4: begin count = 0; rel = 1; pc[self] = L5; end
	  L5: pc[self] = L0;
	  L6: if (rel) pc[self] = L5;	// spinning
	endcase
    end

endmodule // barrier
