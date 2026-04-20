// Verilog model for the round-robin arbiter described in
// the CHARME99 paper by Katz, Grumberg, and Geist.

// Author: Fabio Somenzi <Fabio@Colorado.EDU>

module rrobin(clock,ir0,ir1,fair);
    input  clock;
    input  ir0, ir1;
    output fair;

    reg    req0, req1, ack0, ack1, robin;

    initial begin
	ack0 = 0; ack1 = 0; robin = 0;
	req0 = ir0; req1 = ir1;	// nondeterministic initial requests
    end

    always @ (posedge clock) begin
	if (~req0)
	  ack0 = 0;		// no request -> no ack
	else if (~req1)
	  ack0 = 1;		// a single request
	else if (~ack0 & ~ack1)
	  ack0 = ~robin;	// simultaneous request assertions
	else
	  ack0 = ~ack0;		// both requesting: toggle ack
    end

    always @ (posedge clock) begin
	if (~req1)
	  ack1 = 0;		// no request -> no ack
	else if (~req0)
	  ack1 = 1;		// a single request
	else if (~ack0 & ~ack1)
	  ack1 = robin;		// simultaneous request assertions
	else
	  ack1 = ~ack1;		// both requesting: toggle ack
    end

    always @ (posedge clock) begin
	if (req0 & req1 & ~ack0 & ~ack1)
	  robin = ~robin;	// simultaneous request assertions
    end

    // Latched inputs.
    always @ (posedge clock) begin
	req0 = ir0;
	req1 = ir1;
    end

    Buechi monitor(clock,req0,req1,ack0,ack1,fair);

endmodule // rrobin

typedef enum {Init,n1,n2,n3,n4,n5,n6,n7,n8,n9,n10,n11,n12,n13,n14,n15,n17,n18,n19,n20,n21,n22,n23,n24,n25,Trap} states;

module Buechi(clock,r0,r1,a0,a1,fair);
  input clock,r0,r1,a0,a1;
  output fair;
  states reg state;
  states wire ND_n14_n18_n21_n25;
  states wire ND_n11_n22_n6;
  states wire ND_n10_n15_n5;
  states wire ND_n11_n22_n8;
  states wire ND_n11_n12_n14_n19_n3;
  states wire ND_n14_n18_n24_n25;
  states wire ND_n12_n14_n17;
  states wire ND_n11_n14_n19_n2_n6;
  states wire ND_n11_n12_n14_n3;
  states wire ND_n14_n18_n21;
  states wire ND_n10_n13;
  states wire ND_n12_n14_n19;
  states wire ND_n11_n22;
  states wire ND_n10_n5;
  states wire ND_n11_n14_n2_n6;
  states wire ND_n14_n19_n3_n4_n6;
  states wire ND_n10_n15;
  states wire ND_n14_n21;
  states wire ND_n11_n6;
  states wire ND_n14_n3_n4_n6;
  states wire ND_n22_n6;
  states wire ND_n11_n8;
  states wire ND_n13_n15;
  states wire ND_n14_n24;
  states wire ND_n11_n22_n6_n8;
  states wire ND_n22_n8;
  states wire ND_n12_n14_n19_n3;
  states wire ND_n14_n17;
  states wire ND_n14_n18;
  states wire ND_n12_n14_n3;
  states wire ND_n14_n19_n4_n6;
  states wire ND_n14_n2_n25;
  states wire ND_n14_n24_n4;
  states wire ND_n14_n4_n6;
  states wire ND_n13_n15_n5;
  states wire ND_n11_n6_n8;
  states wire ND_n22_n6_n8;
  states wire ND_n10_n13_n15_n5;
  states wire ND_n14_n2;
  states wire ND_n10_n8;
  states wire ND_n13_n5;
  states wire ND_n14_n4;
  states wire ND_n11_n14_n19_n2_n3_n6;
  states wire ND_n11_n12_n14_n19;
  states wire ND_n15_n5;
  states wire ND_n11_n14_n2_n3_n6;
  states wire ND_n14_n24_n25_n4;
  states wire ND_n19_n3;
  states wire ND_n11_n12_n14;
  states wire ND_n10_n13_n5;
  states wire ND_n6_n8;
  states wire ND_n10_n13_n15;
  assign ND_n14_n18_n21_n25 = $ND(n14,n18,n21,n25);
  assign ND_n11_n22_n6 = $ND(n11,n22,n6);
  assign ND_n10_n15_n5 = $ND(n10,n15,n5);
  assign ND_n11_n22_n8 = $ND(n11,n22,n8);
  assign ND_n11_n12_n14_n19_n3 = $ND(n11,n12,n14,n19,n3);
  assign ND_n14_n18_n24_n25 = $ND(n14,n18,n24,n25);
  assign ND_n12_n14_n17 = $ND(n12,n14,n17);
  assign ND_n11_n14_n19_n2_n6 = $ND(n11,n14,n19,n2,n6);
  assign ND_n11_n12_n14_n3 = $ND(n11,n12,n14,n3);
  assign ND_n14_n18_n21 = $ND(n14,n18,n21);
  assign ND_n10_n13 = $ND(n10,n13);
  assign ND_n12_n14_n19 = $ND(n12,n14,n19);
  assign ND_n11_n22 = $ND(n11,n22);
  assign ND_n10_n5 = $ND(n10,n5);
  assign ND_n11_n14_n2_n6 = $ND(n11,n14,n2,n6);
  assign ND_n14_n19_n3_n4_n6 = $ND(n14,n19,n3,n4,n6);
  assign ND_n10_n15 = $ND(n10,n15);
  assign ND_n14_n21 = $ND(n14,n21);
  assign ND_n11_n6 = $ND(n11,n6);
  assign ND_n14_n3_n4_n6 = $ND(n14,n3,n4,n6);
  assign ND_n22_n6 = $ND(n22,n6);
  assign ND_n11_n8 = $ND(n11,n8);
  assign ND_n13_n15 = $ND(n13,n15);
  assign ND_n14_n24 = $ND(n14,n24);
  assign ND_n11_n22_n6_n8 = $ND(n11,n22,n6,n8);
  assign ND_n22_n8 = $ND(n22,n8);
  assign ND_n12_n14_n19_n3 = $ND(n12,n14,n19,n3);
  assign ND_n14_n17 = $ND(n14,n17);
  assign ND_n14_n18 = $ND(n14,n18);
  assign ND_n12_n14_n3 = $ND(n12,n14,n3);
  assign ND_n14_n19_n4_n6 = $ND(n14,n19,n4,n6);
  assign ND_n14_n2_n25 = $ND(n14,n2,n25);
  assign ND_n14_n24_n4 = $ND(n14,n24,n4);
  assign ND_n14_n4_n6 = $ND(n14,n4,n6);
  assign ND_n13_n15_n5 = $ND(n13,n15,n5);
  assign ND_n11_n6_n8 = $ND(n11,n6,n8);
  assign ND_n22_n6_n8 = $ND(n22,n6,n8);
  assign ND_n10_n13_n15_n5 = $ND(n10,n13,n15,n5);
  assign ND_n14_n2 = $ND(n14,n2);
  assign ND_n10_n8 = $ND(n10,n8);
  assign ND_n13_n5 = $ND(n13,n5);
  assign ND_n14_n4 = $ND(n14,n4);
  assign ND_n11_n14_n19_n2_n3_n6 = $ND(n11,n14,n19,n2,n3,n6);
  assign ND_n11_n12_n14_n19 = $ND(n11,n12,n14,n19);
  assign ND_n15_n5 = $ND(n15,n5);
  assign ND_n11_n14_n2_n3_n6 = $ND(n11,n14,n2,n3,n6);
  assign ND_n14_n24_n25_n4 = $ND(n14,n24,n25,n4);
  assign ND_n19_n3 = $ND(n19,n3);
  assign ND_n11_n12_n14 = $ND(n11,n12,n14);
  assign ND_n10_n13_n5 = $ND(n10,n13,n5);
  assign ND_n6_n8 = $ND(n6,n8);
  assign ND_n10_n13_n15 = $ND(n10,n13,n15);
  assign fair = (state == n1);
  initial state = Init;
  always @ (posedge clock) begin
    case (state)
      n6,n8,n11,n22:
	case ({a0,a1,r0,r1})
	4'b0000: state = ND_n11_n6;
	4'b0001: state = n6;
	4'b0010: state = n11;
	4'b0011: state = n7;
	4'b0100: state = ND_n11_n6_n8;
	4'b0101: state = ND_n6_n8;
	4'b0110: state = ND_n11_n8;
	4'b0111: state = n8;
	4'b1000: state = ND_n11_n22_n6;
	4'b1001: state = ND_n22_n6;
	4'b1010: state = ND_n11_n22;
	4'b1011: state = n22;
	4'b1100: state = ND_n11_n22_n6_n8;
	4'b1101: state = ND_n22_n6_n8;
	4'b1110: state = ND_n11_n22_n8;
	4'b1111: state = ND_n22_n8;
	endcase
      Trap:
	state = Trap;
      n5,n10,n13,n15:
	case ({a0,a1,r0,r1})
	4'b0000: state = ND_n13_n15;
	4'b0001: state = n13;
	4'b0010: state = n15;
	4'b0011: state = n9;
	4'b0100: state = ND_n13_n15_n5;
	4'b0101: state = ND_n13_n5;
	4'b0110: state = ND_n15_n5;
	4'b0111: state = n5;
	4'b1000: state = ND_n10_n13_n15;
	4'b1001: state = ND_n10_n13;
	4'b1010: state = ND_n10_n15;
	4'b1011: state = n10;
	4'b1100: state = ND_n10_n13_n15_n5;
	4'b1101: state = ND_n10_n13_n5;
	4'b1110: state = ND_n10_n15_n5;
	4'b1111: state = ND_n10_n5;
	endcase
      n4,n9,n24:
	case (a1)
	1'b0: state = n23;
	1'b1: state = Trap;
	endcase
      n1,n3,n19,n20,n23,n25:
	state = n1;
      n17:
	case ({a0,a1})
	2'b00: state = Trap;
	2'b01: state = n8;
	2'b10: state = n10;
	2'b11: state = ND_n10_n8;
	endcase
      n14:
	case ({a0,a1,r0,r1})
	4'b0?00: state = ND_n14_n2;
	4'b0?01: state = ND_n14_n4;
	4'b?010: state = ND_n14_n21;
	4'b0011: state = ND_n14_n17;
	4'b0110: state = ND_n14_n18_n21;
	4'b0111: state = ND_n14_n18;
	4'b1000: state = ND_n14_n2;
	4'b1001: state = ND_n14_n24_n4;
	4'b1011: state = ND_n14_n24;
	4'b1100: state = ND_n14_n2_n25;
	4'b1101: state = ND_n14_n24_n25_n4;
	4'b1110: state = ND_n14_n18_n21_n25;
	4'b1111: state = ND_n14_n18_n24_n25;
	endcase
      n2:
	case ({a0,a1})
	2'b00: state = Trap;
	2'b01: state = n19;
	2'b10: state = n3;
	2'b11: state = ND_n19_n3;
	endcase
      n7,n12,n18,n21:
	case (a0)
	1'b0: state = n20;
	1'b1: state = Trap;
	endcase
      Init:
	case ({a0,a1,r0,r1})
	4'b0000: state = ND_n11_n14_n2_n6;
	4'b0001: state = ND_n14_n4_n6;
	4'b0010: state = ND_n11_n12_n14;
	4'b0011: state = ND_n12_n14_n17;
	4'b0100: state = ND_n11_n14_n19_n2_n6;
	4'b0101: state = ND_n14_n19_n4_n6;
	4'b0110: state = ND_n11_n12_n14_n19;
	4'b0111: state = ND_n12_n14_n19;
	4'b1000: state = ND_n11_n14_n2_n3_n6;
	4'b1001: state = ND_n14_n3_n4_n6;
	4'b1010: state = ND_n11_n12_n14_n3;
	4'b1011: state = ND_n12_n14_n3;
	4'b1100: state = ND_n11_n14_n19_n2_n3_n6;
	4'b1101: state = ND_n14_n19_n3_n4_n6;
	4'b1110: state = ND_n11_n12_n14_n19_n3;
	4'b1111: state = ND_n12_n14_n19_n3;
	endcase
    endcase
  end
endmodule
