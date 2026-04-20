// Author: Fabio Somenzi <Fabio@Colorado.EDU>
// Derived from the non-parameterized version of Ramin Hojati.
//
module philo64(clock);
    input clock;
    
    wire [1:0] st0;
    philosopher ph0 (clock,st0,st1,st63,1);
    wire [1:0] st1;
    philosopher ph1 (clock,st1,st2,st0,0);
    wire [1:0] st2;
    philosopher ph2 (clock,st2,st3,st1,0);
    wire [1:0] st3;
    philosopher ph3 (clock,st3,st4,st2,0);
    wire [1:0] st4;
    philosopher ph4 (clock,st4,st5,st3,0);
    wire [1:0] st5;
    philosopher ph5 (clock,st5,st6,st4,0);
    wire [1:0] st6;
    philosopher ph6 (clock,st6,st7,st5,0);
    wire [1:0] st7;
    philosopher ph7 (clock,st7,st8,st6,0);
    wire [1:0] st8;
    philosopher ph8 (clock,st8,st9,st7,0);
    wire [1:0] st9;
    philosopher ph9 (clock,st9,st10,st8,0);
    wire [1:0] st10;
    philosopher ph10 (clock,st10,st11,st9,0);
    wire [1:0] st11;
    philosopher ph11 (clock,st11,st12,st10,0);
    wire [1:0] st12;
    philosopher ph12 (clock,st12,st13,st11,0);
    wire [1:0] st13;
    philosopher ph13 (clock,st13,st14,st12,0);
    wire [1:0] st14;
    philosopher ph14 (clock,st14,st15,st13,0);
    wire [1:0] st15;
    philosopher ph15 (clock,st15,st16,st14,0);
    wire [1:0] st16;
    philosopher ph16 (clock,st16,st17,st15,0);
    wire [1:0] st17;
    philosopher ph17 (clock,st17,st18,st16,0);
    wire [1:0] st18;
    philosopher ph18 (clock,st18,st19,st17,0);
    wire [1:0] st19;
    philosopher ph19 (clock,st19,st20,st18,0);
    wire [1:0] st20;
    philosopher ph20 (clock,st20,st21,st19,0);
    wire [1:0] st21;
    philosopher ph21 (clock,st21,st22,st20,0);
    wire [1:0] st22;
    philosopher ph22 (clock,st22,st23,st21,0);
    wire [1:0] st23;
    philosopher ph23 (clock,st23,st24,st22,0);
    wire [1:0] st24;
    philosopher ph24 (clock,st24,st25,st23,0);
    wire [1:0] st25;
    philosopher ph25 (clock,st25,st26,st24,0);
    wire [1:0] st26;
    philosopher ph26 (clock,st26,st27,st25,0);
    wire [1:0] st27;
    philosopher ph27 (clock,st27,st28,st26,0);
    wire [1:0] st28;
    philosopher ph28 (clock,st28,st29,st27,0);
    wire [1:0] st29;
    philosopher ph29 (clock,st29,st30,st28,0);
    wire [1:0] st30;
    philosopher ph30 (clock,st30,st31,st29,0);
    wire [1:0] st31;
    philosopher ph31 (clock,st31,st32,st30,0);
    wire [1:0] st32;
    philosopher ph32 (clock,st32,st33,st31,0);
    wire [1:0] st33;
    philosopher ph33 (clock,st33,st34,st32,0);
    wire [1:0] st34;
    philosopher ph34 (clock,st34,st35,st33,0);
    wire [1:0] st35;
    philosopher ph35 (clock,st35,st36,st34,0);
    wire [1:0] st36;
    philosopher ph36 (clock,st36,st37,st35,0);
    wire [1:0] st37;
    philosopher ph37 (clock,st37,st38,st36,0);
    wire [1:0] st38;
    philosopher ph38 (clock,st38,st39,st37,0);
    wire [1:0] st39;
    philosopher ph39 (clock,st39,st40,st38,0);
    wire [1:0] st40;
    philosopher ph40 (clock,st40,st41,st39,0);
    wire [1:0] st41;
    philosopher ph41 (clock,st41,st42,st40,0);
    wire [1:0] st42;
    philosopher ph42 (clock,st42,st43,st41,0);
    wire [1:0] st43;
    philosopher ph43 (clock,st43,st44,st42,0);
    wire [1:0] st44;
    philosopher ph44 (clock,st44,st45,st43,0);
    wire [1:0] st45;
    philosopher ph45 (clock,st45,st46,st44,0);
    wire [1:0] st46;
    philosopher ph46 (clock,st46,st47,st45,0);
    wire [1:0] st47;
    philosopher ph47 (clock,st47,st48,st46,0);
    wire [1:0] st48;
    philosopher ph48 (clock,st48,st49,st47,0);
    wire [1:0] st49;
    philosopher ph49 (clock,st49,st50,st48,0);
    wire [1:0] st50;
    philosopher ph50 (clock,st50,st51,st49,0);
    wire [1:0] st51;
    philosopher ph51 (clock,st51,st52,st50,0);
    wire [1:0] st52;
    philosopher ph52 (clock,st52,st53,st51,0);
    wire [1:0] st53;
    philosopher ph53 (clock,st53,st54,st52,0);
    wire [1:0] st54;
    philosopher ph54 (clock,st54,st55,st53,0);
    wire [1:0] st55;
    philosopher ph55 (clock,st55,st56,st54,0);
    wire [1:0] st56;
    philosopher ph56 (clock,st56,st57,st55,0);
    wire [1:0] st57;
    philosopher ph57 (clock,st57,st58,st56,0);
    wire [1:0] st58;
    philosopher ph58 (clock,st58,st59,st57,0);
    wire [1:0] st59;
    philosopher ph59 (clock,st59,st60,st58,0);
    wire [1:0] st60;
    philosopher ph60 (clock,st60,st61,st59,0);
    wire [1:0] st61;
    philosopher ph61 (clock,st61,st62,st60,0);
    wire [1:0] st62;
    philosopher ph62 (clock,st62,st63,st61,0);
    wire [1:0] st63;
    philosopher ph63 (clock,st63,st0,st62,0);

endmodule // philo


module philosopher(clk, out, left, right, init);
    input clk;
    input [1:0] left, right, init;
    output [1:0] out;
    reg [1:0] state;

    wire      coin;
    assign coin = $ND(0,1);

    initial state = init;

    assign out = state;

    always @(posedge clk) begin
	case(state)
	  1:
	    if (left == 0) state = 0;

	  0:
	    if (right == 1) state = 1;
	    else state = coin ? 0 : 3;

	  2:
	    state = coin ? 0 : 2;

	  3:
	    if (left != 2 && right != 3 && right != 2) 
	      state = 2;
	endcase
    end // always @ (posedge clk)

endmodule // philosopher
