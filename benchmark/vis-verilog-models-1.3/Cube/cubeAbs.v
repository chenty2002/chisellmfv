// This is a verilog model for an abstraction of the cube model.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

module cubeAbs(clock,dir,start,pos);
    input clock;
    input [2:0] dir;		// unused
    input [4:0] start;
    output [4:0] pos;

    reg [4:0] pos;
    reg       visited [0:26];
    wire [4:0] dest, next;
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

    // Flip the LSB and choose the others arbitrariliy; use the result as
    // new position if it is between 0 and 26.
    assign next = {start[4:1],~pos[0]};
    assign dest = next < 27 ? next : pos;

    always @ (posedge clock) begin
	if (!visited[dest]) begin
	    pos = dest;
	    visited[dest] = 1;
	end
    end

endmodule // cubeAbs
