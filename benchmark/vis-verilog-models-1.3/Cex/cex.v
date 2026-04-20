// Simple module illustrating counterexample generation.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

module cex(clock,i,p,q);
    input clock;
    input i;
    output p, q;

    reg [1:0] state;

    initial state = 0;

    always @ (posedge clock) begin
	case (state)
	  0: state = 1;
	  1: state = i ? 2 : 0;
	  2: state = i ? 3 : 2;
	  3: state = 3;
	endcase // case(state)
    end

    assign p = state == 1;
    assign q = state == 2;

endmodule // cex
