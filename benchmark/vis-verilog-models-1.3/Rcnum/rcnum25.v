// This Verilog module describes a simple finite state machine for
// the computation of the so-called rollercoaster numbers.
// Given an initial number n[0] > 0, the rollercoaster number
// sequence (n[i]) starting at n[0] is given by these rules:
//   if n[i] is even, n[i+1] = n[i]/2;
//   if n[i] is odd,  n[i+1] = n[i]*3+1.
// The sequences for most (small) numbers eventually reach the
// cycle (4,2,1).
// This finite state machine has a state register storing n[i].
// Since the register is finite, only a few positive integers can
// be represented. Therefore 0 is used as trap state to indicate
// overflow.
//
// This description intentionally avoids the use of '*' and '/'.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>


module rollercoasterNumbers(clock,numOut);
    input	    clock;
    output [24:0] numOut;
    reg [24:0]    numOut;

    wire [24+2:0] tmp;

    initial begin
	numOut[0] = $ND(0,1);	
	numOut[1] = $ND(0,1);	
	numOut[2] = $ND(0,1);	
	numOut[3] = $ND(0,1);	
	numOut[4] = $ND(0,1);	
	numOut[5] = $ND(0,1);	
	numOut[6] = $ND(0,1);	
	numOut[7] = $ND(0,1);	
	numOut[8] = $ND(0,1);	
	numOut[9] = $ND(0,1);	
	numOut[10] = $ND(0,1);	
	numOut[11] = $ND(0,1);	
	numOut[12] = $ND(0,1);	
	numOut[13] = $ND(0,1);	
	numOut[14] = $ND(0,1);	
	numOut[15] = $ND(0,1);	
	numOut[16] = $ND(0,1);	
	numOut[17] = $ND(0,1);	
	numOut[18] = $ND(0,1);	
	numOut[19] = $ND(0,1);	
	numOut[20] = $ND(0,1);	
	numOut[21] = $ND(0,1);	
	numOut[22] = $ND(0,1);	
	numOut[23] = $ND(0,1);	
	numOut[24] = $ND(0,1);	
    end

    // Compute n[i] * 3 + 1.
    assign tmp = {2'b0,numOut} + {1'b0,numOut,1'b1};

    always @ (posedge clock)
	if (numOut[0]) begin
	    // Check overflow.
	    numOut = (tmp[24+2] | tmp[24+1]) ? 0 : tmp[24:0];
	end else begin
	    // Divide by 2.
	    numOut = {1'b0,numOut[24:1]};
	end

endmodule // rollercoasterNumbers
