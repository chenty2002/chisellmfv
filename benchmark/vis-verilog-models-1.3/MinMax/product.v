// Composition of two equivalent minmax cirsuits.
// This model is to study equivalence verification.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

module product(clock, clear, enable, reset, in, equal);
    parameter     MSB = 7;
    input 	  clock;
    input 	  clear;
    input 	  enable;
    input 	  reset;
    input [MSB:0] in;
    output 	  equal;

    wire [MSB:0]  out1, out2;
    reg 	  equal;

    initial equal = 1;

    always @ (posedge clock) equal = out1 == out2;

    minMax  #(MSB) mm  (clock,clear,enable,reset,in,out1);
    minMaxR #(MSB) mmr (clock,clear,enable,reset,in,out2);

endmodule // product


// MinMax circuit. Translated into Verilog from the LDS description in
// "Verification of Sequential Machines Using Boolean Functional Vectors"
// by Coudert, Berthet, and Madre.

module minMax (clock,clear,enable,reset,in,out);
    parameter	   MSB = 7;	// index of the MSB
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

// "Optimized" MinMax circuit. Translated into Verilog from the LDS
// description of Figure 6 in
// "Verification of Sequential Machines Using Boolean Functional Vectors"
// by Coudert, Berthet, and Madre.
//
// The optimization saves one flip-flop.  The idea is as follows:
// If the MSB of last is 0, then the MSB of min is also 0.  Conversely,
// if the MSB of last is 1, then the MSB of max is also 1.  Therefore,
// the MSB flip-flop for min and max can be shared: it stores the value that
// cannot be inferred from last.
//
// For the initial states, we make sure that the shared bit be equal to the
// MSB of last.  This is means that the MSB of min and max would be the same.
// Therefore, we look at the next bit to decide whether we have min > max.

module minMaxR (clock,clear,enable,reset,in,out);
    parameter	   MSB = 7;	// index of the MSB
    input	   clock;
    input	   clear;
    input	   enable;
    input	   reset;
    input [MSB:0]  in;
    output [MSB:0] out;

    reg [MSB-1:0]  rmin;
    reg [MSB:0]    last;
    reg [MSB-1:0]  rmax;
    reg 	   shared;

    wire [MSB:0]   min;
    wire [MSB:0]   max;
    wire [MSB:0]   inf;
    wire [MSB:0]   sup;
    wire [MSB:0]   avg;
    wire	   aux;
    wire 	   flag;

    initial begin
	rmin = {MSB{1'b1}};	// fill rmin with all ones
	rmax = 0;
	last = in;		// nondeterministic initial state
	shared = in[MSB];	// make sure shared == last[MSB]
    end

    // Next state logic.
    assign flag = shared == last[MSB] & rmin[MSB-1] & ~rmax[MSB-1];
    assign min = {flag  | (shared & last[MSB]), rmin};
    assign max = {~flag & (shared | last[MSB]), rmax};
    assign inf = (in < min) ? in : min;		// unsigned comparison
    assign sup = (in > max) ? in : max;
    assign {avg,aux} = {1'b0,sup} + {1'b0,inf};	// average of min and max

    always @ (posedge clock) begin
	if (clear) begin
	    last = 0; rmax = 0; rmin = {MSB{1'b1}}; shared = 0;
	end else begin
	    if (!enable) begin
		rmax = 0; rmin = {MSB{1'b1}}; shared = last[MSB];
	    end else begin
		last = in;
		if (reset) begin
		    rmax = 0; rmin = {MSB{1'b1}}; shared = in[MSB];
		end else begin
		    rmax = sup[MSB-1:0]; rmin = inf[MSB-1:0];
		    shared = in[MSB] ? inf[MSB] : sup[MSB];
		end
	    end
	end
    end

    // Output logic.
    assign out = clear ?  0 :
	!enable ?  last :
	reset ?  in : avg;

endmodule // minMaxR
