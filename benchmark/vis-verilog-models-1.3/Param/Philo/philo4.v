// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {THINKING,READING,EATING,HUNGRY} State;

module philo4(clock);
    input clock;
    
    State wire st0;
    philosopher ph0 (clock,st0,st1,st3,READING);
    State wire st1;
    philosopher ph1 (clock,st1,st2,st0,THINKING);
    State wire st2;
    philosopher ph2 (clock,st2,st3,st1,THINKING);
    State wire st3;
    philosopher ph3 (clock,st3,st0,st2,THINKING);

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
	    if (right == READING) self = READING;
	    else self = coin ? THINKING : HUNGRY;

	  EATING:
	    self = coin ? THINKING : EATING;

	  HUNGRY:
	    if (left != EATING && right != HUNGRY && right != EATING) 
	      self = EATING;
	endcase
    end // always @ (posedge clk)

endmodule // philosopher
