// Spinner.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>


module spinner(clock,spin,amount,din,dout);
    input	 clock;
    input 	 spin;
    input [1:0]  amount;
    input [3:0]  din;
    output [3:0] dout;

    reg [3:0] 	 dout;
    reg [3:0] 	 inr;
    reg 	 spl;

    wire [3:0] 	 tmp0;
    wire [3:0] 	 tmp1;
    wire [3:0] 	 tmp2;

    initial begin
	dout = 0;
	inr = 0;
	spl = 0;
    end

    assign tmp0 = inr;
    assign tmp1[2:0] = amount[0] ?  tmp0[3:1] : tmp0[2:0];
    assign tmp1[3]   = amount[0] ?  tmp0[0]   : tmp0[3];
    assign tmp2[1:0] = amount[1] ?  tmp1[3:2] : tmp1[1:0];
    assign tmp2[3:2] = amount[1] ?  tmp1[1:0] : tmp1[3:2];

    always @ (posedge clock) begin
	if (spl)
	  inr = dout;
	else
	  inr = din;
	dout = tmp2;
	spl = spin;
    end // always @ (posedge clock)

endmodule // rotate
