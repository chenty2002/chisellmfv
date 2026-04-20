// This module illustrates a coding style that can be used to specify an
// arbitrary set of initial states.
//
// This particular model has two state variables, a and b, and two valid
// initial states, 00 and 11.  The model itself simply swaps the values of
// a and b.  Hence, the initial states are also the only reachable states.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

module relinit(clock);
    input clock;

    reg   a,b;

    // This function returns 1 if the inputs correspond to a valid initial
    // state.
    function valid;
	input a; input b;
	begin: _valid
	    valid = a == b;
	end
    endfunction // valid

    initial begin
	// Start by assuming all states initial.
	a = $ND(0, 1); b = $ND(0, 1);
	// Then remap invalid initial states to valid one.
	if (!valid(a,b)) begin
	    a = 0; b = 0;
	end
    end // initial begin

    always @ (posedge clock) begin
	a = b;
    end

    always @ (posedge clock) begin
	b = a;
    end

endmodule // relinit
