// Lucky Seven puzzle with just one initial state.
//
// The entries of the matrix are numbered thus:
//
//       1--0--7
//      /   |   \
//     2    |    6
//      \   |   /
//       3--4--5
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

module luckySeven(clock,from,to);
    input       clock;
    input [2:0] from;
    input [2:0] to;

    reg [2:0] 	b[0:7];
    reg [2:0] 	freg, treg;
    wire 	valid, parity, permutation;

    // The initial states include illegal configurations, that is,
    // configurations that are not permutations of (0,...,7).  We use the
    // "permutation" predicate in the properties to restrict considetation
    // to legal initial states.
    initial begin
	b[0] = 0;
	b[1] = 1;
	b[2] = 2;
	b[3] = 3;
	b[4] = 4;
	b[5] = 5;
	b[6] = 6;
	b[7] = 7;
	treg = 0;
	freg = 0;
    end

    // We latch the inputs so that we can refer to them in properties.
    // In particular, we could not refer to "valid" in properties without
    // these latches.
    always @ (posedge clock) begin
	freg = from;
	treg = to;
    end

    always @ (posedge clock) begin
	if (valid) begin
	    b[treg] = b[freg];
	    b[freg] = 0;
	end
    end
    
    // This predicate is true of the valid moves, that is, of moves that
    // swap the empty cell with one of its neighbors.
    assign valid = (b[treg] == 3'b000) &&
		   (treg==(freg+4'b1) || freg==(treg+4'b1) ||
		    (treg[1:0]==0 && freg[1:0]==0 && freg[2]!=treg[2]));

    // This predicate is true of all board contents that represent a
    // permutation of (0,...,7).  As it is formulated, it seems to require
    // only that the sixteen numbers be different, but since these
    // numbers are on three bits, thy must be {0,...,7}.
    assign permutation = b[0]!=b[1] && b[0]!=b[2] && b[0]!=b[3] &&
	   b[0]!=b[4] && b[0]!=b[5] && b[0]!=b[6] && b[0]!=b[7] &&
	   b[1]!=b[2] && b[1]!=b[3] && b[1]!=b[4] && b[1]!=b[5] &&
	   b[1]!=b[6] && b[1]!=b[7] && b[2]!=b[3] && b[2]!=b[4] &&
	   b[2]!=b[5] && b[2]!=b[6] && b[2]!=b[7] && b[3]!=b[4] &&
	   b[3]!=b[5] && b[3]!=b[6] && b[3]!=b[7] && b[4]!=b[5] &&
	   b[4]!=b[6] && b[4]!=b[7] && b[5]!=b[6] && b[5]!=b[7] &&
	   b[6]!=b[7];

endmodule // twoByFour
