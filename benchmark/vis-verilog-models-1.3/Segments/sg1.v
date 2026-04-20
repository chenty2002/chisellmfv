// Simple FSM illustrating that convergence of the dijunction of the partial
// iterates may occur when the fixpoint has not been reached yet in the
// segmented approach to EG computation.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {A,B,C,D,E} States;

module sg1(clock,i,o);
    input clock,i;
    output o;

    States reg state;

    initial state = A;

    always @ (posedge clock) begin
	case (state)
	  A: state = i ? B : A;
	  B: state = i ? C : D;
	  C: state = B;
	  D: state = E;
	  E: state = E;
	endcase // case(state)
    end

    assign o = state == A;

endmodule // sg1
