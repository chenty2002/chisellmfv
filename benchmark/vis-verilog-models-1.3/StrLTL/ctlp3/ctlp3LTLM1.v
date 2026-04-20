/* Dining philosophers of E. W. Dijkstra
        based on S/R implementation by R. Kurshan
	Ramin Hojati, May 1993

*/
typedef enum {THINKING, HUNGRY, EATING, READING} t_state;

/************************************************************************/
module diners(clk);
input clk;

    t_state wire s0,s1,s2,s3,s4,s5,s6,s7,s8,s9,s10,s11,s12,s13,s14,s15,s16,s17,s18,s19,s20,s21,s22,s23,s24,s25,s26,s27,s28,s29,s30,s31,s32,s33,s34,s35,s36,s37,s38,s39,s40,s41,s42,s43,s44,s45,s46,s47,s48,s49,s40,s41,s42,s43,s44,s45,s46,s47,s48,s49,s50,s51,s52,s53,s54,s55,s56,s57,s58,s59,s60,s61,s62,s63;

    wire ph0Eating,ph2Eating,ph0Hungry,ph1Eating;
    
    assign ph0Eating= s0==EATING;
    assign ph1Eating= s1==EATING;
    assign ph2Eating= s2==EATING;
    assign ph0Hungry= s0==HUNGRY;
    
Buechi Buechi(clk,ph0Hungry,ph2Eating,ph1Eating,ph0Eating,fair,scc);

  
philosopher ph0(clk, s0, s1, s63, EATING);
philosopher ph1(clk, s1, s2, s0, READING);
philosopher ph2(clk, s2, s3, s1, HUNGRY);
philosopher ph3(clk, s3, s4, s2,  THINKING);
philosopher ph4(clk, s4, s5, s3, THINKING);
philosopher ph5(clk, s5, s6, s4, THINKING);
philosopher ph6(clk, s6, s7, s5, THINKING);
philosopher ph7(clk, s7, s8, s6, THINKING);
philosopher ph8(clk, s8, s9, s7, THINKING);
philosopher ph9(clk, s9, s10, s8,THINKING);
philosopher ph10(clk, s10, s11, s9,THINKING);


philosopher ph11(clk, s11, s12, s10, THINKING);
philosopher ph12(clk, s12, s13, s11, THINKING);
philosopher ph13(clk, s13, s14, s12, THINKING);
philosopher ph14(clk, s14, s15, s13, THINKING);
philosopher ph15(clk, s15, s16, s14, THINKING);
philosopher ph16(clk, s16, s17, s15, THINKING);
philosopher ph17(clk, s17, s18, s16, THINKING);
philosopher ph18(clk, s18, s19, s17, THINKING);
philosopher ph19(clk, s19, s20, s18,THINKING);
philosopher ph20(clk, s20, s21, s19,THINKING);

philosopher ph21(clk, s21, s22, s20, THINKING);
philosopher ph22(clk, s22, s23, s21, THINKING);
philosopher ph23(clk, s23, s24, s22, THINKING);
philosopher ph24(clk, s24, s25, s23, THINKING);
philosopher ph25(clk, s25, s26, s24, THINKING);
philosopher ph26(clk, s26, s27, s25, THINKING);
philosopher ph27(clk, s27, s28, s26, THINKING);
philosopher ph28(clk, s28, s29, s27, THINKING);
philosopher ph29(clk, s29, s30, s28,THINKING);
philosopher ph30(clk, s30, s31, s29,THINKING);

philosopher ph31(clk, s31, s32, s30, THINKING);
philosopher ph32(clk, s32, s33, s31, THINKING);
philosopher ph33(clk, s33, s34, s32, THINKING);
philosopher ph34(clk, s34, s35, s33, THINKING);
philosopher ph35(clk, s35, s36, s34, THINKING);
philosopher ph36(clk, s36, s37, s35, THINKING);
philosopher ph37(clk, s37, s38, s36, THINKING);
philosopher ph38(clk, s38, s39, s37, THINKING);
philosopher ph39(clk, s39, s40, s38,THINKING);
philosopher ph40(clk, s40, s41, s39,THINKING);

philosopher ph41(clk, s41, s42, s40, THINKING);
philosopher ph42(clk, s42, s43, s41, THINKING);
philosopher ph43(clk, s43, s44, s42, THINKING);
philosopher ph44(clk, s44, s45, s43, THINKING);
philosopher ph45(clk, s45, s46, s44, THINKING);
philosopher ph46(clk, s46, s47, s45, THINKING);
philosopher ph47(clk, s47, s48, s46, THINKING);
philosopher ph48(clk, s48, s49, s47, THINKING);
philosopher ph49(clk, s49, s50, s48,THINKING);
philosopher ph50(clk, s50, s51, s49,THINKING);

philosopher ph51(clk, s51, s52, s50, THINKING);
philosopher ph52(clk, s52, s53, s51, THINKING);
philosopher ph53(clk, s53, s54, s52, THINKING);
philosopher ph54(clk, s54, s55, s53, THINKING);
philosopher ph55(clk, s55, s56, s54, THINKING);
philosopher ph56(clk, s56, s57, s55, THINKING);
philosopher ph57(clk, s57, s58, s56, THINKING);
philosopher ph58(clk, s58, s59, s57, THINKING);
philosopher ph59(clk, s59, s60, s58,THINKING);
philosopher ph60(clk, s60, s61, s59,THINKING);

philosopher ph61(clk, s61, s62, s60, THINKING);
philosopher ph62(clk, s62, s63, s61, THINKING);
philosopher ph63(clk, s63, s0, s62, THINKING);

//philosopher ph1(clk, s1, s2, s0, THINKING);
//philosopher ph2(clk, s2, s3, s1, THINKING);
//philosopher ph3(clk, s3, s4, s2, THINKING);
//philosopher ph4(clk, s4, s5, s3, THINKING);
//philosopher ph5(clk, s5, s6, s4, THINKING);
//philosopher ph6(clk, s6, s7, s5, THINKING);
//philosopher ph7(clk, s7, s8, s6, THINKING);
//philosopher ph8(clk, s8, s9, s7, THINKING);
//philosopher ph9(clk, s9, s10, s8,THINKING);
//philosopher ph10(clk, s10, s11, s9,THINKING);
    
starvation str(clk, s0);

endmodule

typedef enum {Init,n1,n2,n3,n4,n5,n6,n7,n8,n9,Trap} states;

module Buechi(clock,ph0Hungry,ph2Eating,ph1Eating,ph0Eating,fair,scc);
  input clock,ph0Hungry,ph2Eating,ph1Eating,ph0Eating;
  output fair,scc;
  states reg state;
  states wire ND_n7_n8;
  states wire ND_n2_n6_n8_n9;
  states wire ND_n1_n2_n5_n6_n8;
  states wire ND_n1_n2_n4_n6_n9;
  states wire ND_n1_n2;
  states wire ND_n1_n2_n4_n5;
  states wire ND_n3_n4_n9;
  states wire ND_n2_n6_n8;
  states wire ND_n2_n6_n9;
  states wire ND_n1_n2_n4_n5_n6_n8_n9;
  states wire ND_n1_n4;
  states wire ND_n1_n5;
  states wire ND_n3_n4;
  states wire ND_n5_n7_n8;
  states wire ND_n2_n6;
  states wire ND_n1_n2_n4;
  states wire ND_n1_n2_n5;
  states wire ND_n5_n7;
  states wire ND_n3_n9;
  states wire ND_n1_n2_n6;
  states wire ND_n1_n4_n5;
  assign ND_n7_n8 = $ND(n7,n8);
  assign ND_n2_n6_n8_n9 = $ND(n2,n6,n8,n9);
  assign ND_n1_n2_n5_n6_n8 = $ND(n1,n2,n5,n6,n8);
  assign ND_n1_n2_n4_n6_n9 = $ND(n1,n2,n4,n6,n9);
  assign ND_n1_n2 = $ND(n1,n2);
  assign ND_n1_n2_n4_n5 = $ND(n1,n2,n4,n5);
  assign ND_n3_n4_n9 = $ND(n3,n4,n9);
  assign ND_n2_n6_n8 = $ND(n2,n6,n8);
  assign ND_n2_n6_n9 = $ND(n2,n6,n9);
  assign ND_n1_n2_n4_n5_n6_n8_n9 = $ND(n1,n2,n4,n5,n6,n8,n9);
  assign ND_n1_n4 = $ND(n1,n4);
  assign ND_n1_n5 = $ND(n1,n5);
  assign ND_n3_n4 = $ND(n3,n4);
  assign ND_n5_n7_n8 = $ND(n5,n7,n8);
  assign ND_n2_n6 = $ND(n2,n6);
  assign ND_n1_n2_n4 = $ND(n1,n2,n4);
  assign ND_n1_n2_n5 = $ND(n1,n2,n5);
  assign ND_n5_n7 = $ND(n5,n7);
  assign ND_n3_n9 = $ND(n3,n9);
  assign ND_n1_n2_n6 = $ND(n1,n2,n6);
  assign ND_n1_n4_n5 = $ND(n1,n4,n5);
  assign fair = (state == n8) || (state == n9) || (state == n4) || (state == n5);
    assign scc =(state==n5) || (state ==n7) || (state ==n8) || (state ==n3) || (state ==n4) || (state ==n9);
    
  initial state = Init;
  always @ (posedge clock) begin
    case (state)
      n3:
	case ({ph0Eating,ph2Eating})
	2'b?0: state = Trap;
	2'b01: state = n3;
	2'b11: state = ND_n3_n9;
	endcase
      Trap:
	state = Trap;
      Init:
	case ({ph0Eating,ph0Hungry,ph1Eating,ph2Eating})
	4'b0000: state = ND_n1_n2;
	4'b0001: state = ND_n1_n2_n4;
	4'b0010: state = ND_n1_n2_n5;
	4'b0011: state = ND_n1_n2_n4_n5;
	4'b01??: state = n2;
	4'b1000: state = ND_n1_n2_n6;
	4'b1001: state = ND_n1_n2_n4_n6_n9;
	4'b1010: state = ND_n1_n2_n5_n6_n8;
	4'b1011: state = ND_n1_n2_n4_n5_n6_n8_n9;
	4'b1100: state = ND_n2_n6;
	4'b1101: state = ND_n2_n6_n9;
	4'b1110: state = ND_n2_n6_n8;
	4'b1111: state = ND_n2_n6_n8_n9;
	endcase
      n7:
	case ({ph0Eating,ph1Eating})
	2'b?0: state = Trap;
	2'b01: state = n7;
	2'b11: state = ND_n7_n8;
	endcase
      n1,n6:
	case ({ph0Hungry,ph1Eating,ph2Eating})
	3'b000: state = n1;
	3'b001: state = ND_n1_n4;
	3'b010: state = ND_n1_n5;
	3'b011: state = ND_n1_n4_n5;
	3'b1??: state = Trap;
	endcase
      n5,n8:
	case ({ph0Eating,ph0Hungry,ph1Eating})
	3'b??0: state = Trap;
	3'b001: state = ND_n5_n7;
	3'b011: state = n7;
	3'b101: state = ND_n5_n7_n8;
	3'b111: state = ND_n7_n8;
	endcase
      n4,n9:
	case ({ph0Eating,ph0Hungry,ph2Eating})
	3'b??0: state = Trap;
	3'b001: state = ND_n3_n4;
	3'b011: state = n3;
	3'b101: state = ND_n3_n4_n9;
	3'b111: state = ND_n3_n9;
	endcase
      n2:
	case ({ph0Eating,ph1Eating,ph2Eating})
	3'b0??: state = n2;
	3'b100: state = ND_n2_n6;
	3'b101: state = ND_n2_n6_n9;
	3'b110: state = ND_n2_n6_n8;
	3'b111: state = ND_n2_n6_n8_n9;
	endcase
    endcase
  end
endmodule

/************************************************************************/
module philosopher(clk, out, left, right, init);
input clk;
input left, right, init;
output out;
t_state wire left, right, init, out;
t_state reg state;
t_state wire r0_state,r1_state;


initial state = init;

assign r0_state = $ND(THINKING,HUNGRY);
assign r1_state = $ND(THINKING,EATING);
assign out = state;

always @(posedge clk) begin
    case(state)
        READING:
		if (left == THINKING) state = THINKING;

        THINKING:
            begin
		if ( right == READING ) state = READING;
		else state = r0_state; 
            end
    
        EATING:
                  state = r1_state; 

        HUNGRY:
		if ( left != EATING && right != HUNGRY && right != EATING) 
                state = EATING; 
        endcase
end
endmodule

/************************************************************************/
module starvation( clk, starv );
	input	clk;
	input	starv;
	t_state wire starv;
	reg	state;

initial state = 0;

always @(posedge clk) begin
    case(state)	
	0: if ( starv == HUNGRY ) state = 1;

	1: if ( starv == THINKING ) state = 0;

    endcase
end
endmodule	
