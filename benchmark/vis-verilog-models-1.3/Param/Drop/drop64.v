// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {THINKING,READING,EATING,HUNGRY} State;

module philo64(clock);
    input clock;

    State wire st0;
    philosopher ph0 (clock,st0,st1,st63,READING);
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
    philosopher ph31 (clock,st31,st32,st30,THINKING);
    State wire st32;
    philosopher ph32 (clock,st32,st33,st31,THINKING);
    State wire st33;
    philosopher ph33 (clock,st33,st34,st32,THINKING);
    State wire st34;
    philosopher ph34 (clock,st34,st35,st33,THINKING);
    State wire st35;
    philosopher ph35 (clock,st35,st36,st34,THINKING);
    State wire st36;
    philosopher ph36 (clock,st36,st37,st35,THINKING);
    State wire st37;
    philosopher ph37 (clock,st37,st38,st36,THINKING);
    State wire st38;
    philosopher ph38 (clock,st38,st39,st37,THINKING);
    State wire st39;
    philosopher ph39 (clock,st39,st40,st38,THINKING);
    State wire st40;
    philosopher ph40 (clock,st40,st41,st39,THINKING);
    State wire st41;
    philosopher ph41 (clock,st41,st42,st40,THINKING);
    State wire st42;
    philosopher ph42 (clock,st42,st43,st41,THINKING);
    State wire st43;
    philosopher ph43 (clock,st43,st44,st42,THINKING);
    State wire st44;
    philosopher ph44 (clock,st44,st45,st43,THINKING);
    State wire st45;
    philosopher ph45 (clock,st45,st46,st44,THINKING);
    State wire st46;
    philosopher ph46 (clock,st46,st47,st45,THINKING);
    State wire st47;
    philosopher ph47 (clock,st47,st48,st46,THINKING);
    State wire st48;
    philosopher ph48 (clock,st48,st49,st47,THINKING);
    State wire st49;
    philosopher ph49 (clock,st49,st50,st48,THINKING);
    State wire st50;
    philosopher ph50 (clock,st50,st51,st49,THINKING);
    State wire st51;
    philosopher ph51 (clock,st51,st52,st50,THINKING);
    State wire st52;
    philosopher ph52 (clock,st52,st53,st51,THINKING);
    State wire st53;
    philosopher ph53 (clock,st53,st54,st52,THINKING);
    State wire st54;
    philosopher ph54 (clock,st54,st55,st53,THINKING);
    State wire st55;
    philosopher ph55 (clock,st55,st56,st54,THINKING);
    State wire st56;
    philosopher ph56 (clock,st56,st57,st55,THINKING);
    State wire st57;
    philosopher ph57 (clock,st57,st58,st56,THINKING);
    State wire st58;
    philosopher ph58 (clock,st58,st59,st57,THINKING);
    State wire st59;
    philosopher ph59 (clock,st59,st60,st58,THINKING);
    State wire st60;
    philosopher ph60 (clock,st60,st61,st59,THINKING);
    State wire st61;
    philosopher ph61 (clock,st61,st62,st60,THINKING);
    State wire st62;
    philosopher ph62 (clock,st62,st63,st61,THINKING);
    State wire st63;
    philosopher ph63 (clock,st63,st0,st62,THINKING);

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
