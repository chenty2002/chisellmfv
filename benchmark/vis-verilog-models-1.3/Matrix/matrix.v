// This module describes a bit matrix used to store a symmetric, reflexive
// relation.  Only the lower triangle of the matrix (diagonal excluded)
// is stored in a linear bit array.  The values of the diagonal must be
// fixed.  (Either all ones, or all zeroes.)
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

module matrix(clock,row,col,r_w,bitIn,bitOut);
    // MSB of the row and column indices.
    parameter	  MSB = 2;
    // Number of rows and columns of the full square matrix.
    // It must be N = 2**(MSB+1).
    parameter 	  N = 8;
    // Number of locations in the lower triangle of the matrix (diagonal
    // excluded).  It must be L = \sum_{0 < i < N} i = (N * (N-1))/2.
    parameter 	  L = 28;
    // Value to return for the diagonal elements, which are not stored.
    input	  clock;
    input [MSB:0] row, col;
    input 	  r_w;		// 1: read, 0: write
    input 	  bitIn;
    output 	  bitOut;

    reg [MSB*2:0] offset[1:N-1], posn;
    reg 	  M[0:L-1];
    integer 	  j;

    // The precomputed offsets are a necessity imposed by vl2mv.
    // Since the offsets are not changing, they do not affect forward
    // reachability analysis much.
    initial begin
	posn = 0;
	for (j = 1; j < N; j = j + 1) begin
	    offset[j] = posn;
	    if (j != N-1) posn = posn + j;
	end
	for (j = 0; j < L; j = j + 1)
	  M[j] = 0;
    end

    always @ (posedge clock) begin
	if (row != col) begin
	    if (row < col)
	      posn = offset[col] + {{MSB{1'b0}},row};
	    else
	      posn = offset[row] + {{MSB{1'b0}},col};
	    if (!r_w)
	      M[posn] = bitIn;
	end
    end

    assign bitOut = (row == col) ? 1 : M[posn];

endmodule // matrix

