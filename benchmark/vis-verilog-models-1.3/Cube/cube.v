// This is a verilog model for the following puzzle.  We are given a cube
// made of 27 smaller cubes.  Is it possible to trace a path that starts at
// the surface of the cube, and reaches the small cube at the center after
// visiting all other small cubes exactly once under the constraint that one
// can move only in a direction that is parallel to one of the edges of the
// cube?
//
// In this model the top of the cube is made of little cubes 0-8, and so on.
// The view from the top is
//
//   0 1 2    F
//   3 4 5  L-+-R
//   6 7 8    B
//
// Hence, moving right corresponds to adding 1, moving backward to adding 3,
// and moving down to adding 9.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

module cube(clock,dir,start,pos);
    input clock;
    input [2:0] dir;
    input [4:0] start;
    output [4:0] pos;
    parameter 	 U = 0;		// upward  
    parameter 	 D = 1;		// downward
    parameter 	 L = 2;		// left	   
    parameter 	 R = 3;		// right   
    parameter 	 F = 4;		// forward 
    parameter 	 B = 5;		// backward

    reg [4:0] pos;
    reg       visited [0:26];
    wire [4:0] dest;
    integer   i;

    // Initially the position is any exterior little cube, and no little
    // cube is visited, except the initial one.
    initial begin
	pos = start;
	if (pos > 26 || pos == 13)
	  pos = 0;
	for (i = 0; i < 27; i = i + 1)
	  visited[i] = 0;
	visited[pos] = 1;
    end

    // Compute the residue of a 5-bit number mod 3.
    function [1:0] resMod3;
	input [4:0] n;
	begin: _resMod3
	    case (n)
	      0,3,6,9,12,15,18,21,24,27,30:  resMod3 = 0;
	      1,4,7,10,13,16,19,22,25,28,31: resMod3 = 1;
	      default:                       resMod3 = 2;
	    endcase // case(n)
	end
    endfunction // resMod3

    // Compute the residue of a 5-bit number mod 9.
    function [3:0] resMod9;
	input [4:0] n;
	begin: _resMod9
	    case (n)
	      0,9,18,27:  resMod9 = 0;
	      1,10,19,28: resMod9 = 1;
	      2,11,20,29: resMod9 = 2;
	      3,12,21,30: resMod9 = 3;
	      4,13,22,31: resMod9 = 4;
	      5,14,23:    resMod9 = 5;
	      6,15,24:    resMod9 = 6;
	      7,16,25:    resMod9 = 7;
	      default:    resMod9 = 8;
	    endcase
	end
    endfunction // resMod9

    function [4:0] next;
	input [4:0] current;
	input [2:0] where;
	begin: _next
	    next = current;
	    case (where)
	      U:
		if (current > 8)
		  next = current - 9;
	      D:
		if (current < 18)
		  next = current + 9;
	      L:
		if (resMod3(current) != 0)
		  next = current - 1;
	      R:
		if (resMod3(current) != 2)
		  next = current + 1;
	      F:
		if (resMod9(current) > 2)
		  next = current - 3;
	      B:
		if (resMod9(current) < 6)
		  next = current + 3;
	      default: /* NOOP */;
	    endcase
	end
    endfunction // next

    assign dest = next(pos,dir);

    always @ (posedge clock) begin
	if (!visited[dest]) begin
	    pos = dest;
	    visited[dest] = 1;
	end
    end

endmodule // cube
