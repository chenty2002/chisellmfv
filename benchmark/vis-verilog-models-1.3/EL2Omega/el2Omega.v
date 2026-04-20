// This is a parameterized model showing that EL2 is Omega((|C|dh + N - N').
// The graphs of the models in this family look like the one below.
//
//   x->x->x->x->x->x->o-+->1
//                       |
//                       |
//                       +->2
//
//   x->x->x->x->x->x->o-+->1->o-+->1
//                       |     ^ |
//                       |     | |
//                       +->2--+ +->2
//
//   x->x->x->x->x->x->o-+->1->o-+->1->o-+->1
//                       |     ^ |     ^ |
//                       |     | |     | |
//                       +->2--+ +->2--+ +->2
//
// See Ravi, Bloem, and Somenzi "Analysis of Symbolic SCC Hull Algorithms"
// for the details.
// This verilog model restricts the number of rows to a power of 2.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

module el2Omega(clock, pause, rchoice, cchoice, dchoice, colmsb, collsb);
    parameter        ROWMSB = 1;
    parameter 	     ROWBITS = ROWMSB+1;
    parameter 	     COLMSB = ROWMSB+2;
    parameter 	     COLBITS = COLMSB+1;
    parameter 	     DIGMSB = 1;
    input 	     clock;
    input 	     pause;
    input [ROWMSB:0] rchoice;
    input [COLMSB:0] cchoice;
    input [DIGMSB:0] dchoice;
    output 	     colmsb, collsb;

    // The position in the graph is encoded by three numbers: row, column,
    // and digit.  The digit is 0 when the column corresponds to an "x" or
    // "o" state.
    reg [ROWMSB:0]   row;
    reg [COLMSB:0]   col;
    reg [DIGMSB:0]   digit;

    // These are convenient to avoid modifying the fairness conditions when
    // the number of rows of the graph changes.
    assign 	     colmsb = col[COLMSB];
    assign 	     collsb = col[0];

    initial begin
	row = rchoice;
	col = 0;
	digit = 0;
    end

    always @ (posedge clock) begin
	if (col + {1'b0,row,1'b0} != {COLBITS{1'b1}}) begin
	    // Not a sink.
	    if (col <= {1'b0,{COLMSB{1'b1}}}) begin
		// An "x" state.  Go to any "x" state to the right or first
		// "o" state on the same row.
		if (cchoice > col && cchoice <= {1'b1,{COLMSB{1'b0}}})
		  col = cchoice;
		else
		  col = col + 1;
	    end else begin
		// An "o" (col[0]==0) or digit (col[0]==1) state.   If the
		// state is a digit state and pause==1, take the self loop.
		if (col[0] == 0 || pause == 0) begin
		    // Move forward without overshooting the end of the row.
		    if (cchoice > col && {1'b0,cchoice} + {2'b0,row,1'b0}
			<= {1'b0,{COLBITS{1'b1}}})
		      col = cchoice;
		    else
		      col = col + 1;
		    // If new state is a digit state, choose the digit.
		    if (col[0] == 1)
		      digit = dchoice;
		    else
		      digit = 0;
		end
	    end
	end
    end

endmodule // el2Omega
