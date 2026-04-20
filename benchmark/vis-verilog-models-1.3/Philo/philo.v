// This version of the infamous dining philosopher example uses a scheduler
// to enable only one philosopher at the time.  This is to mimic an
// asynchronous model.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>
//
module philo10(clock,select);
    input clock;
    input [3:0] select;

    reg [3:0] selreg;
    wire [1:0] st0;
    wire go0 = selreg == 0;
    philosopher ph0 (clock,go0,st0,st1,st9,0);
    wire [1:0] st1;
    wire go1 = selreg == 1;
    philosopher ph1 (clock,go1,st1,st2,st0,0);
    wire [1:0] st2;
    wire go2 = selreg == 2;
    philosopher ph2 (clock,go2,st2,st3,st1,0);
    wire [1:0] st3;
    wire go3 = selreg == 3;
    philosopher ph3 (clock,go3,st3,st4,st2,0);
    wire [1:0] st4;
    wire go4 = selreg == 4;
    philosopher ph4 (clock,go4,st4,st5,st3,0);
    wire [1:0] st5;
    wire go5 = selreg == 5;
    philosopher ph5 (clock,go5,st5,st6,st4,0);
    wire [1:0] st6;
    wire go6 = selreg == 6;
    philosopher ph6 (clock,go6,st6,st7,st5,0);
    wire [1:0] st7;
    wire go7 = selreg == 7;
    philosopher ph7 (clock,go7,st7,st8,st6,0);
    wire [1:0] st8;
    wire go8 = selreg == 8;
    philosopher ph8 (clock,go8,st8,st9,st7,0);
    wire [1:0] st9;
    wire go9 = selreg == 9;
    philosopher ph9 (clock,go9,st9,st0,st8,0);
    always @ (posedge clock) selreg = select;
    initial selreg = 10;

endmodule // philo


module philosopher(clk, go, out, left, right, init);
    input clk;
    input go;
    input [1:0] left, right, init;
    output [1:0] out;
    reg [1:0] state;

    initial state = init;

    assign out = state;

    always @(posedge clk) begin
	if (go)
	  case(state)
	    0:
	      if (left != 2 && left != 3) state = 1;

	    1:
	      if (right != 1 && right != 3) state = 3;

	    3:
	      state = 2;

	    2:
	      state = 0;
	  endcase
    end // always @ (posedge clk)

endmodule // philosopher
