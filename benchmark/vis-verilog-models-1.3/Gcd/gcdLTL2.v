// Testbench for the gcd circuit.
module testGcd(clock,x,y,s,fair);
    parameter	  N = 8;
    parameter	  logN = 3;
    input	  clock;
    input [N-1:0] x,y;
    input	  s;
    reg [N-1:0]	  a,b;
    reg		  start;
    wire	  busy;
    wire [N-1:0]  o;
    output 	  fair;

    // Unit under test.
    gcd #(N,logN) g(clock,start,a,b,busy,o);

    initial begin
	a = 0;
	b = 0;
	start = 0;
    end

    always @ (posedge clock) begin
        a = x;
        b = y;
	start = s;
    end // always @ (posedge clock)

    monitor mtr(clock,start,busy,fair);

endmodule // testGcd


module monitor(clock,start,busy,fair);
    input clock;
    input start;
    input busy;
    output fair;

    reg [1:0] state;
    wire [1:0] zeroorone;

    assign zeroorone[0] = $ND(0,1);
    assign zeroorone[1] = 0;

    initial state = 0;
    assign fair = state == 1;

    always @ (posedge clock)
      case (state)
	0: state = start ? zeroorone : 0;
	1: state = busy ? 1 : 2;
	2: state = 2;
      endcase // case(state)

endmodule // monitor


// GCD circuit for unsigned N-bit numbers
// a[0], b[0], and o[0] are the least significant bits
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>
module gcd(clock,start,a,b,busy,o);
    parameter	   N = 8;
    parameter	   logN = 3;
    input	   clock;
    input	   start;
    input [N-1:0]  a;
    input [N-1:0]  b;
    output	   busy;
    output [N-1:0] o;

    reg [logN-1:0] lsb;
    reg [N-1:0]	   x;
    reg [N-1:0]	   y;
    wire	   done;
    wire	   load;
    reg		   busy;
    reg [N-1:0]	   o;

    wire [1:0]     xy_lsb;
    wire [N-1:0]   diff;

    function select;
	input [N-1:0] z;
	input [logN-1:0] lsb;
	begin: _select
	    if (lsb == 3'd0)
	      select = z[0];
	    else if (lsb == 3'd1)
	      select = z[1];
	    else if (lsb == 3'd2)
	      select = z[2];
	    else if (lsb == 3'd3)
	      select = z[3];
	    else if (lsb == 3'd4)
	      select = z[4];
	    else if (lsb == 3'd5)
	      select = z[5];
	    else if (lsb == 3'd6)
	      select = z[6];
	    else
	      select = z[7];
	end // block: _select
    endfunction // select

    assign xy_lsb[1] = select(x,lsb);
    assign xy_lsb[0] = select(y,lsb);
    assign diff = x < y ? y - x : x - y;
    
    initial begin
	busy = 0;
	x = 0;
	y = 0;
	o = 0;
	lsb = 0;
    end // initial begin

    assign done = ((x == y) | (x == 0) | (y == 0)) & busy;

    // Data path.
    always @(posedge clock) begin
	if (load) begin
	    x = a;
	    y = b;
	    lsb = 0;
	end // if (load)
	else if (busy & ~done) begin
	    case (xy_lsb)
	      2'b00:
		  lsb = lsb + 1;
	      2'b01:
		  begin
		  x[N-2:0] = x[N-1:1];
  		  x[N-1] = 0;
		  end
	      2'b10:
		  begin
		  y[N-2:0] = y[N-1:1];
		  y[N-1] = 0;
		  end
	      2'b11: begin
		  if (x < y) begin
		      y[N-2:0] = diff[N-1:1];
                      y[N-1] = 0;
		  end // if (x < y)
		  else begin
		      x[N-2:0] = diff[N-1:1];
 		      x[N-1] = 0;
		  end // else: !if(x < y)
	      end // case: 2b'11
	    endcase // case (xy_lsb)
	end // if (~done)
	else if (done) begin
	    o = (x < y) ? x : y;
	end // else: !if(~done)
    end // always @ (posedge clock)

    assign load = start & ~busy;

    // Controller.
    always @(posedge clock) begin
	if (~busy) begin
	    if (start) begin
		busy = 1;
	    end // if (start)
	end // if (~busy)
	else begin
	    if (done) begin
		busy = 0;
	    end
	end // else: !if(~busy)
    end // always @ (posedge clock)

endmodule // gcd
