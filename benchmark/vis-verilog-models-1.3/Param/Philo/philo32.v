// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {THINKING,READING,EATING,HUNGRY} State;

module philo32(clock);
    input clock;

    State wire st0;
    philosopher ph0 (clock,st0,st1,st31,READING);
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
    philosopher ph7 (clock,st7,st8,st6,THINKING);
    State wire st8;
    philosopher ph8 (clock,st8,st9,st7,THINKING);
    State wire st9;
    philosopher ph9 (clock,st9,st10,st8,THINKING);
    State wire st10;
    philosopher ph10 (clock,st10,st11,st9,THINKING);
    State wire st11;
    philosopher ph11 (clock,st11,st12,st10,THINKING);
    State wire st12;
    philosopher ph12 (clock,st12,st13,st11,THINKING);
    State wire st13;
    philosopher ph13 (clock,st13,st14,st12,THINKING);
    State wire st14;
    philosopher ph14 (clock,st14,st15,st13,THINKING);
    State wire st15;
    philosopher ph15 (clock,st15,st16,st14,THINKING);
    State wire st16;
    philosopher ph16 (clock,st16,st17,st15,THINKING);
    State wire st17;
    philosopher ph17 (clock,st17,st18,st16,THINKING);
    State wire st18;
    philosopher ph18 (clock,st18,st19,st17,THINKING);
    State wire st19;
    philosopher ph19 (clock,st19,st20,st18,THINKING);
    State wire st20;
    philosopher ph20 (clock,st20,st21,st19,THINKING);
    State wire st21;
    philosopher ph21 (clock,st21,st22,st20,THINKING);
    State wire st22;
    philosopher ph22 (clock,st22,st23,st21,THINKING);
    State wire st23;
    philosopher ph23 (clock,st23,st24,st22,THINKING);
    State wire st24;
    philosopher ph24 (clock,st24,st25,st23,THINKING);
    State wire st25;
    philosopher ph25 (clock,st25,st26,st24,THINKING);
    State wire st26;
    philosopher ph26 (clock,st26,st27,st25,THINKING);
    State wire st27;
    philosopher ph27 (clock,st27,st28,st26,THINKING);
    State wire st28;
    philosopher ph28 (clock,st28,st29,st27,THINKING);
    State wire st29;
    philosopher ph29 (clock,st29,st30,st28,THINKING);
    State wire st30;
    philosopher ph30 (clock,st30,st31,st29,THINKING);
    State wire st31;
    philosopher ph31 (clock,st31,st0,st30,THINKING);

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
