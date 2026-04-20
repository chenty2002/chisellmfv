// Serial/parallel multiplier for unsigned numbers based on carry-save
// addition.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>
module SerialCSAMult(clock,reset,i_raw,j_raw,o);
    parameter	     BITS = 32;	// operand width
    input	     clock;	// clock
    input	     reset;	// reset
    input [BITS-1:0] i_raw;	// multiplicand
    input	     j_raw;	// multiplier bit
    output	     o;		// one bit of the product

    reg [BITS-2:0]   s;		// sum register
    reg [BITS-2:0]   c;		// carry register
    reg [BITS-1:0]   i;
    reg 	     j;
    wire [BITS-2:0]  faS;	// sum outputs of the CSA
    wire [BITS-2:0]  faC;	// carry outputs of the CSA
    wire [BITS-1:0]  andA;	// product of multiplicand and multiplier bit

    initial begin
	s = 0;
	c = 0;
	i = 0;
	j = 0;
    end

    always @ (posedge clock) begin
	i = i_raw;
	j = j_raw;
    end

    always @ (posedge clock) begin
	if (reset) begin
	    s = 0;
	    c = 0;
	end else begin
	    s = {andA[BITS-1], faS[BITS-2:1]};
	    c = faC;
	end
    end

    assign andA = {BITS{j}} & i;
    assign faC = c & s | c & andA[BITS-2:0] | s & andA[BITS-2:0];
    assign faS = c ^ s ^ andA[BITS-2:0];
    assign o = faS[0];

endmodule // SerialCSAMult
