// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {THINKING,READING,EATING,HUNGRY} State;

module philo8(clock);
    input clock;

    State wire st0;
    philosopher ph0 (clock,st0,st1,st7,READING);
    State wire st1;
    philosopher ph1 (clock,st1,st2,st0,THINKING);
    State wire st2;
    philosopher ph2 (clock,st2,st3,st1,THINKING);
    State wire st3;
    philosopher ph3 (clock,st3,st4,st2,THINKING);
    State wire st4;
    philosopher ph4 (clock,st4,st5,st3,THINKING);
    State wire st5;
    philosopher ph5 (clock,st5,st6,st4,THINKING);
    State wire st6;
    philosopher ph6 (clock,st6,st7,st5,THINKING);
    State wire st7;
    philosopher ph7 (clock,st7,st0,st6,THINKING);

endmodule // philo


module philosopher(clk, out, left, right, init);
    input clk;
    output out;
    input left, right, init;
    State wire left, right, init;
    State wire  out;
    State reg self;

    wire      coin;
    assign coin = $ND(0,1);

    initial self = init;

    assign out = self;

    always @(posedge clk) begin
	case(self)
	  READING:
	    if (left == THINKING) self = THINKING;

	  THINKING:
	    if (coin && right == READING) self = READING;
	    else self = coin ? THINKING : HUNGRY;

	  EATING:
	    self = coin ? THINKING : EATING;

	  HUNGRY:
	    if (left != EATING && right != HUNGRY && right != EATING) 
	      self = EATING;
	endcase
    end // always @ (posedge clk)

endmodule // philosopher
