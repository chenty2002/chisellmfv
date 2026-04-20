// Binary-to-Gray-to-binary conversion.  That is, the identity.

// Author: Fabio Somenzi <Fabio@Colorado.EDU>

module gray(clock,i,z);
    input clock;
    input i;
    output z;

    reg    p,q,r;
    wire   w;

    initial begin
	p = $ND(0,1);
	q = $ND(0,1);
	r = $ND(0,1);
    end

    always @ (posedge clock) begin
	r = z;
	q = p;
	p = i;
    end

    assign w = p ^ q;
    assign z = w ^ r;

endmodule // gray
