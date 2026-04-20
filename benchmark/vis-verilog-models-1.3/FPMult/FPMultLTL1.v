// VIS testbench for a sequential floating point multiplier.
// The purpose of this testbench is exclusively to latch the inputs, so
// that CTL properties may refer to them.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>
//
module fvFPMult(clock,i,j);
    parameter		  MBITS = 3;	// size of significand minus hidden bit
    parameter		  EBITS = 4;	// size of exponent
    input		  clock;	// global clock
    input [MBITS+EBITS:0] i;		// multiplicand
    input [MBITS+EBITS:0] j;		// multiplier
    reg   [MBITS+EBITS:0] x;		// multiplicand
    reg   [MBITS+EBITS:0] y;		// multiplier
    wire  [MBITS+EBITS:0] z;		// output register
    reg			  start;	// starts multiplier
    wire  [1:0] 	  state;	// to the monitor
    wire 		  fair;		// from the monitor

    IEEEfpMult #(MBITS,EBITS) FPM (clock,start,x,y,z,state);

    always @ (posedge clock) begin
	x = i;
	y = j;
    end // always @ (posedge clock)

    monitor #(MBITS,EBITS) mtr (clock,state,x,y,z,fair);

endmodule // fvFPMult

// Floating point multiplier.
// Not exactly IEEE 754-compliant, but largely inspired to the standard.
//
// The significand uses the hidden bit and is between 1 (included) and 2
// (excluded).
// The exponent uses the excess (2**(n-1) - 1) representation. For single
// precision, this is excess 127.
// The smallest exponent (0) is used for the represenation of 0. Denormals
// are not supported.
// The largest exponent is used for infinities and NaNs. Infinities use
// the smallest possible significand (all zeroes). Everything else is deemed
// a NaN. No distinction is made between signalling and non-signalling NaNs.
// When the multiplier generates a NaN, it uses the all-one significand.
// One multiplication takes three clock cycles and it is not pipelined.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>
//
module IEEEfpMult(clock,start,x,y,z,state);
    parameter		   MBITS = 3; // size of significand minus hidden bit
    parameter		   EBITS = 4; // size of exponent
    input		   clock;
    input		   start;
    input [MBITS+EBITS:0]  x, y;
    output [MBITS+EBITS:0] z;
    output [1:0] 	   state;

    reg [MBITS+EBITS:0]	   z;
    reg			   xSign;	// unpacked x with hidden bit exposed
    reg [EBITS-1:0]	   xExp;
    reg [MBITS:0]	   xMant;
    reg			   ySign;	// unpacked y with hidden bit exposed
    reg [EBITS-1:0]	   yExp;
    reg [MBITS:0]	   yMant;
    reg [1:0]		   state;	// idle, computing, postprocessing
    reg			   signProd;	// components of the product
    reg [EBITS+1:0]	   expProd;	// before rounding and normalization
    reg [2*MBITS+1:0]	   mantProd;
    wire		   msb;
    wire		   lsb;
    wire		   guard;
    wire		   round;
    wire		   sticky;
    wire [MBITS+1:0]	   preMant;
    wire [EBITS+1:0]	   scaledExp;
    wire [MBITS-1:0]	   scaledMant;
    wire [2*MBITS+1:0]	   combZ;

    intMult im(xMant, yMant, combZ);

    function NaN;
	input [EBITS-1:0] aExp;
	input [MBITS-1:0] aMant;
    begin: isNaN
	if (aExp == {EBITS{1'b1}} && aMant != 0)
	    NaN = 1;
	else
	    NaN = 0;
    end // block: isNaN
    endfunction // NaN

    function Zero;
	input [EBITS-1:0] aExp;
	input [MBITS-1:0] aMant;
    begin: isZero
	if (aExp == 0 && aMant == 0)
	    Zero = 1;
	else
	    Zero = 0;
    end // block: isZero
    endfunction // Zero

    function Infinity;
	input [EBITS-1:0] aExp;
	input [MBITS-1:0] aMant;
    begin: isInfinity
	if (aExp == {EBITS{1'b1}} && aMant == 0)
	    Infinity = 1;
	else
	    Infinity = 0;
    end // block: isInfinity
    endfunction // Infinity

    parameter
	idle = 2'd0,
	computing = 2'd1,
	postprocessing = 2'd2;

    initial begin
	xSign    = 0;
	xExp     = 0;
	xMant    = 0;
	ySign    = 0;
	yExp     = 0;
	yMant    = 0;
	signProd = 0;
	expProd  = 0;
	mantProd = 0;
	z        = 0;
	state    = idle;
    end

    always @ (posedge clock) begin
	case (state)
	  idle:
	      begin
		  if (start) begin	// unpack operands
		      xSign = x[MBITS+EBITS];
		      xExp  = x[MBITS+EBITS-1:MBITS];
		      xMant = {1'b1,x[MBITS-1:0]};
		      ySign = y[MBITS+EBITS];
		      yExp  = y[MBITS+EBITS-1:MBITS];
		      yMant = {1'b1,y[MBITS-1:0]};
		      state = computing;
		  end // if (start)
	      end // case: idle
	  computing:
	      begin
		  mantProd = combZ;
		  if (Zero(xExp,xMant) || Zero(yExp,yMant))
		      expProd = 0;
		  else
		      expProd  = xExp + yExp - {EBITS-1{1'b1}};
		  signProd = xSign ^ ySign;
		  state = postprocessing;
	      end // case: computing
	  postprocessing:
	      begin
		  if (NaN(xExp,xMant) || NaN(yExp,yMant) ||
		      Infinity(xExp,xMant) && Zero(yExp,yMant) ||
		      Zero(xExp,xMant) && Infinity(yExp,yMant))
		      z = {1'b0,{EBITS{1'b1}},{MBITS{1'b1}}}; // NaN
		  else if (Infinity(xExp,xMant) || Infinity(yExp,yMant))
		      z = {signProd,{EBITS{1'b1}},{MBITS{1'b0}}}; // +/- Infinity
		  else
		      // check for underflow and overflow
		      if (scaledExp[EBITS+1] || scaledExp == 0)
			  z = {signProd,{MBITS+EBITS{1'b0}}}; // signed zero
		      else if (scaledExp >= {EBITS{1'b1}}) // overflow
			  z = {signProd,{EBITS{1'b1}},{MBITS{1'b0}}}; // +/- Infinity
		      else
			  z = {signProd,scaledExp[EBITS-1:0],scaledMant};
		  state = idle;
	      end // case: postprocessing
	endcase // case (state)
    end // always @ (posedge clock)

    // Combinational logic for rounding and normalization
    assign msb    = mantProd[2*MBITS+1];	// MSB of the product
    assign lsb    = mantProd[MBITS+1];		// LSB of the result
    assign guard  = mantProd[MBITS];		// guard bit
    assign round  = mantProd[MBITS-1];		// round bit
    assign sticky = | mantProd[MBITS-2:0];	// sticky bit
    // round to nearest even
    assign preMant = msb ?
	mantProd[2*MBITS+1:MBITS] +
	    {{MBITS{1'b0}}, guard & (round | sticky | lsb), 1'b0}
	    :
	    mantProd[2*MBITS+1:MBITS] +
		{{MBITS+1{1'b0}}, round & (sticky | guard)};
    // normalize
    assign scaledExp = preMant[MBITS+1] ?
	expProd + 1 :
	    expProd;
    assign scaledMant = preMant[MBITS+1] ?
	preMant[MBITS:1] :
	    preMant[MBITS-1:0];

endmodule // IEEEfpMult

module intMult(x,y,z);
    input  [3:0] x, y;
    output [7:0] z;

    wire [3:0] int0;
    wire [5:0] int1;
    wire [6:0] int2;
    wire [7:0] int3;

    assign int0 = {4{y[0]}} & x;
    assign int1 = int0 + {{4{y[1]}} & x, {1{1'b0}}};
    assign int2 = int1 + {{4{y[2]}} & x, {2{1'b0}}};
    assign int3 = int2 + {{4{y[3]}} & x, {3{1'b0}}};

    assign z = int3;

endmodule // intMult


module monitor(clock,FPMstate,x,y,z,fair);
    parameter             MBITS = 3;
    parameter             EBITS = 4;
    input                 clock;
    input [1:0]           FPMstate;
    input [MBITS+EBITS:0] x, y, z;
    output 		  fair;

    reg [2:0]		  state;
    wire 		  validx, validy, validz;

    initial state = 0;

    assign validx = x[MBITS+EBITS-1:MBITS] != 0 ||
		    x[MBITS-1:0] == 0;
    assign validy = y[MBITS+EBITS-1:MBITS] != 0 ||
		    y[MBITS-1:0] == 0;
    assign validz = z[MBITS+EBITS-1:MBITS] != 0 ||
		    z[MBITS-1:0] == 0;
    assign fair = (state == 5);

    always @ (posedge clock) begin
	case (state)
	  0: state = (FPMstate == 0 && validx && validy) ? 1 : 0;
	  1: state = 2;
	  2: state = 3;
	  3: state = validz ? 4 : 5;
	  4: state = 4;
	  5: state = 5;
	endcase // case(state)
    end // always @ (posedge clock)

endmodule // monitor
