module rgraph(clock,i,o);
    parameter MSB = 11;
    input     clock;
    input     i;
    output    o;

    reg [MSB:0] cnt;
    reg 	mode;

    initial begin
	cnt = 0;
	mode = 0;
    end

    always @ (posedge clock) begin
	if (mode == 0) begin
	    cnt = cnt + 1;
	end else begin
	    if (i & cnt != 0)
	      cnt = cnt - 1;
	end
	if (mode == 0 & i)
	  mode = 1;
    end

    assign o = cnt == 0;

endmodule // rgraph
