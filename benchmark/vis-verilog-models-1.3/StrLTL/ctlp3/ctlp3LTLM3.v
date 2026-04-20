/* Dining philosophers of E. W. Dijkstra
        based on S/R implementation by R. Kurshan
	Ramin Hojati, May 1993

*/
typedef enum {THINKING, HUNGRY, EATING, READING} t_state;

/************************************************************************/
module diners(clk);
input clk;

    t_state wire s0,s1,s2,s3,s4,s5,s6,s7,s8,s9,s10,s11,s12,s13,s14,s15,s16,s17,s18,s19,s20,s21,s22,s23,s24,s25,s26,s27,s28,s29,s30,s31,s32,s33,s34,s35,s36,s37,s38,s39,s40,s41,s42,s43,s44,s45,s46,s47,s48,s49,s40,s41,s42,s43,s44,s45,s46,s47,s48,s49,s50,s51,s52,s53,s54,s55,s56,s57,s58,s59,s60,s61,s62,s63;

    wire ph0Eating,ph2Eating,ph0Hungry,ph1Eating,ph3Eating;
    
    assign ph0Eating= s0==EATING;
    assign ph1Eating= s1==EATING;
    assign ph2Eating= s2==EATING;
    assign ph3Eating= s3==EATING;
    assign ph0Hungry= s0==HUNGRY;
    
Buechi Buechi(clk,ph0Eating,ph3Eating,ph1Eating,ph2Eating,ph0Hungry,fair0,fair1,fair2,scc);
  
philosopher ph0(clk, s0, s1, s63, EATING);
philosopher ph1(clk, s1, s2, s0, READING);
philosopher ph2(clk, s2, s3, s1, HUNGRY);
philosopher ph3(clk, s3, s4, s2,  THINKING);
Nphilosopher ph4(clk, s4, s5, s3, THINKING);
Nphilosopher ph5(clk, s5, s6, s4, THINKING);
Nphilosopher ph6(clk, s6, s7, s5, THINKING);
Nphilosopher ph7(clk, s7, s8, s6, THINKING);
Nphilosopher ph8(clk, s8, s9, s7, THINKING);
Nphilosopher ph9(clk, s9, s10, s8,THINKING);
Nphilosopher ph10(clk, s10, s11, s9,THINKING);


Nphilosopher ph11(clk, s11, s12, s10, THINKING);
Nphilosopher ph12(clk, s12, s13, s11, THINKING);
Nphilosopher ph13(clk, s13, s14, s12, THINKING);
Nphilosopher ph14(clk, s14, s15, s13, THINKING);
Nphilosopher ph15(clk, s15, s16, s14, THINKING);
Nphilosopher ph16(clk, s16, s17, s15, THINKING);
Nphilosopher ph17(clk, s17, s18, s16, THINKING);
Nphilosopher ph18(clk, s18, s19, s17, THINKING);
Nphilosopher ph19(clk, s19, s20, s18,THINKING);
Nphilosopher ph20(clk, s20, s21, s19,THINKING);

Nphilosopher ph21(clk, s21, s22, s20, THINKING);
Nphilosopher ph22(clk, s22, s23, s21, THINKING);
Nphilosopher ph23(clk, s23, s24, s22, THINKING);
Nphilosopher ph24(clk, s24, s25, s23, THINKING);
Nphilosopher ph25(clk, s25, s26, s24, THINKING);
Nphilosopher ph26(clk, s26, s27, s25, THINKING);
Nphilosopher ph27(clk, s27, s28, s26, THINKING);
Nphilosopher ph28(clk, s28, s29, s27, THINKING);
Nphilosopher ph29(clk, s29, s30, s28,THINKING);
Nphilosopher ph30(clk, s30, s31, s29,THINKING);

Nphilosopher ph31(clk, s31, s32, s30, THINKING);
Nphilosopher ph32(clk, s32, s33, s31, THINKING);
Nphilosopher ph33(clk, s33, s34, s32, THINKING);
Nphilosopher ph34(clk, s34, s35, s33, THINKING);
Nphilosopher ph35(clk, s35, s36, s34, THINKING);
Nphilosopher ph36(clk, s36, s37, s35, THINKING);
Nphilosopher ph37(clk, s37, s38, s36, THINKING);
Nphilosopher ph38(clk, s38, s39, s37, THINKING);
Nphilosopher ph39(clk, s39, s40, s38,THINKING);
Nphilosopher ph40(clk, s40, s41, s39,THINKING);

Nphilosopher ph41(clk, s41, s42, s40, THINKING);
Nphilosopher ph42(clk, s42, s43, s41, THINKING);
Nphilosopher ph43(clk, s43, s44, s42, THINKING);
Nphilosopher ph44(clk, s44, s45, s43, THINKING);
Nphilosopher ph45(clk, s45, s46, s44, THINKING);
Nphilosopher ph46(clk, s46, s47, s45, THINKING);
Nphilosopher ph47(clk, s47, s48, s46, THINKING);
Nphilosopher ph48(clk, s48, s49, s47, THINKING);
Nphilosopher ph49(clk, s49, s50, s48,THINKING);
Nphilosopher ph50(clk, s50, s51, s49,THINKING);

Nphilosopher ph51(clk, s51, s52, s50, THINKING);
Nphilosopher ph52(clk, s52, s53, s51, THINKING);
Nphilosopher ph53(clk, s53, s54, s52, THINKING);
Nphilosopher ph54(clk, s54, s55, s53, THINKING);
Nphilosopher ph55(clk, s55, s56, s54, THINKING);
Nphilosopher ph56(clk, s56, s57, s55, THINKING);
Nphilosopher ph57(clk, s57, s58, s56, THINKING);
Nphilosopher ph58(clk, s58, s59, s57, THINKING);
Nphilosopher ph59(clk, s59, s60, s58,THINKING);
Nphilosopher ph60(clk, s60, s61, s59,THINKING);

Nphilosopher ph61(clk, s61, s62, s60, THINKING);
Nphilosopher ph62(clk, s62, s63, s61, THINKING);
Nphilosopher ph63(clk, s63, s0, s62, THINKING);

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

typedef enum {n2,n3,n4,n6,n8,n11,n17,n21,n23,Trap} states;

module Buechi(clock,ph0Eating,ph3Eating,ph1Eating,ph2Eating,ph0Hungry,fair0,fair1,fair2,scc);
  input clock,ph0Eating,ph3Eating,ph1Eating,ph2Eating,ph0Hungry;
  output fair0,fair1,fair2,scc;
  states reg state;
  states wire ND_n4_n8;
  states wire ND_n11_n21_n4_n8;
  states wire ND_n11_n21_n23_n3_n4_n6_n8;
  states wire ND_n23_n4;
  states wire ND_n11_n23_n3_n4;
  states wire ND_n23_n4_n6_n8;
  states wire ND_n11_n4;
  states wire ND_n17_n2;
  assign ND_n4_n8 = $ND(n4,n8);
  assign ND_n11_n21_n4_n8 = $ND(n11,n21,n4,n8);
  assign ND_n11_n21_n23_n3_n4_n6_n8 = $ND(n11,n21,n23,n3,n4,n6,n8);
  assign ND_n23_n4 = $ND(n23,n4);
  assign ND_n11_n23_n3_n4 = $ND(n11,n23,n3,n4);
  assign ND_n23_n4_n6_n8 = $ND(n23,n4,n6,n8);
  assign ND_n11_n4 = $ND(n11,n4);
  assign ND_n17_n2 = $ND(n17,n2);
  assign fair0 = (state == n6) || (state == n21) || (state == n8);
  assign fair1 = (state == n3) || (state == n11) || (state == n21);
  assign fair2 = (state == n3) || (state == n6) || (state == n23);
    assign scc = (state ==n11) || (state ==n21) || (state ==n3) || (state ==n4) || (state ==n23) || (state ==n6) || (state ==n8);
  initial state = n2;
  always @ (posedge clock) begin
    case (state)
      n3,n4,n6,n8,n11,n17,n21,n23:
	case ({ph0Eating,ph1Eating,ph2Eating,ph3Eating})
	4'b0000: state = ND_n11_n21_n23_n3_n4_n6_n8;
	4'b0001: state = ND_n11_n21_n4_n8;
	4'b0010: state = ND_n11_n23_n3_n4;
	4'b0011: state = ND_n11_n4;
	4'b0100: state = ND_n23_n4_n6_n8;
	4'b0101: state = ND_n4_n8;
	4'b0110: state = ND_n23_n4;
	4'b0111: state = n4;
	4'b1???: state = Trap;
	endcase
      n2:
	case ({ph0Eating,ph0Hungry})
	2'b00: state = n2;
	2'b01: state = ND_n17_n2;
	2'b1?: state = n2;
	endcase
      Trap:
	state = Trap;
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

module Nphilosopher(clk, out, left, right, init);
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
            state = THINKING;//r1_state; 

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
