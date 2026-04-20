// MinMax circuit. Translated into Verilog from the LDS description in
// "Verification of Sequential Machines Using Boolean Functional Vectors"
// by Coudert, Berthet, and Madre.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

module minMax (clock,clear,enable,reset,in,out);
    parameter	   MSB = 8;	// index of the MSB
    input	   clock;
    input	   clear;
    input	   enable;
    input	   reset;
    input [MSB:0]  in;
    output [MSB:0] out;

    reg [MSB:0]	   min;
    reg [MSB:0]	   last;
    reg [MSB:0]	   max;

    wire [MSB:0]   sup;
    wire [MSB:0]   inf;
    wire [MSB:0]   avg;
    wire	   aux;

    initial begin
	min = {MSB+1{1'b1}};	// fill min with all ones.
	max = 0;
	last = in;		// nondeterministic initial state
    end

    // Next state logic.
    assign {avg,aux} = {1'b0,sup} + {1'b0,inf};	// average of min and max
    assign sup = (in > max) ? in : max;		// unsigned comparison
    assign inf = (in < min) ? in : min;

    always @ (posedge clock) begin
	if (clear) begin
	    last = 0; max = 0; min = {MSB+1{1'b1}};
	end else begin
	    if (!enable) begin
		max = 0; min = {MSB+1{1'b1}};
	    end else begin
		last = in;
		if (reset) begin
		    max = 0; min = {MSB+1{1'b1}};
		end else begin
		    max = sup; min = inf;
		end
	    end
	end
    end

    // Output logic.
    assign out = clear ?  0 :
	!enable ?  last :
	reset ?  in : avg;

endmodule // minMax
