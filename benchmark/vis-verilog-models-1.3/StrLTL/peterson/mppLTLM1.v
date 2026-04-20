typedef enum {L0, L1, L2, L3, L4, L5, L6, L7} loc;

module peterson(clock,select,pause);
    parameter        SELMSB = 2;
    parameter 	     HIPROC = 7;
    input 	     clock;
    input [SELMSB:0] select;
    input 	     pause;

    reg 	     interested[0:HIPROC];
    reg [SELMSB:0]   turn;
    reg [SELMSB:0]   self, k;
    loc reg          pc[0:HIPROC];
    reg [SELMSB:0]   j[0:HIPROC];
    integer 	     i;

    wire 	     pc2L6,pc1L6,pc1L0,pc0L6,interested0is1,pc2L0;

    assign 	     pc2L6 = pc[2]==L6;
    
    assign 	     pc1L6 = pc[1]==L6;
    
    assign 	     pc1L0 = pc[1]==L0;
    
    assign 	     pc0L6 = pc[0]==L6;
    
    assign 	     interested0is1 = interested[0]==1;
    
    assign 	     pc2L0 = pc[2]==L0;

Buechi  Buechi(clock,pc2L6,pc1L6,pc1L0,pc0L6,interested0is1,pc2L0,fair0,fair1,fair2,fair3,scc);
    
    initial begin
	for (i = 0; i <= HIPROC; i = i + 1) begin
	    pc[i] = L0;
	    j[i] = 0;
	    interested[i] = 0;
	end
	turn = 0;
	self = 0;
	k = 0;
    end

    always @ (posedge clock) begin
	if (select > HIPROC)
	  self = 0;
	else
	  self = select;

	case (pc[self])
	  // Noncritical region.
	  L0: begin if (pause) pc[self] = L0; else pc[self] = L1; end
	  L1: begin interested[self] = 1; pc[self] = L2; end
	  L2: begin turn = (self==0) ? HIPROC : self-1; pc[self] = L3; end
	  L3: begin j[self] = (self==HIPROC) ? 0 : self+1; pc[self] = L4; end
	  L4: if (j[sel] == self) pc[self] = L6; else pc[self] = L5;
	  L5: begin
	      k = j[self];
	      if (interested[k] && turn == k)
		pc[self] = L5;
	      else
		pc[self] = L4;
	  end
	  // Critical region.
	  L6: begin 
	    if (pause &&(self==0 ||self ==1 ||self ==2)) pc[self] = L6; 
	    else pc[self] = L7; 
	  end
	  L7: begin interested[self] = 0; pc[self] = L0; end
	endcase // case(pc[self])
    end // always @ (posedge clock)

endmodule // peterson

typedef enum {n2,n8,n12,n16,n18,n20,n21,n22,n23,n26,n27,n32,n33,n34,n36,n42,n45,Trap} states;

module Buechi(clock,pc2L6,pc1L6,pc1L0,pc0L6,interested0is1,pc2L0,fair0,fair1,fair2,fair3,scc);
  input clock,pc2L6,pc1L6,pc1L0,pc0L6,interested0is1,pc2L0;
  output fair0,fair1,fair2,fair3,scc;
  states reg state;
  states wire ND_n21_n22;
  states wire ND_n18_n20_n22_n23_n27_n33_n34_n36;
  states wire ND_n21_n22_n36_n45;
  states wire ND_n2_n21_n22_n26_n27_n34_n36_n45;
  states wire ND_n22_n23;
  states wire ND_n20_n22_n23_n34;
  states wire ND_n18_n22_n23_n36;
  states wire ND_n22_n34;
  states wire ND_n22_n27_n34_n36;
  states wire ND_n12_n16_n20_n21_n22_n23_n26_n34;
  states wire ND_n32_n8;
  states wire ND_n22_n36;
  states wire ND_n12_n18_n21_n22_n23_n36_n42_n45;
  states wire ND_n21_n22_n26_n34;
  states wire ND_n12_n21_n22_n23;
  states wire ND_n12_n16_n18_n2_n20_n21_n22_n23_n26_n27_n33_n34_n36_n42_n45;
  assign ND_n21_n22 = $ND(n21,n22);
  assign ND_n18_n20_n22_n23_n27_n33_n34_n36 = $ND(n18,n20,n22,n23,n27,n33,n34,n36);
  assign ND_n21_n22_n36_n45 = $ND(n21,n22,n36,n45);
  assign ND_n2_n21_n22_n26_n27_n34_n36_n45 = $ND(n2,n21,n22,n26,n27,n34,n36,n45);
  assign ND_n22_n23 = $ND(n22,n23);
  assign ND_n20_n22_n23_n34 = $ND(n20,n22,n23,n34);
  assign ND_n18_n22_n23_n36 = $ND(n18,n22,n23,n36);
  assign ND_n22_n34 = $ND(n22,n34);
  assign ND_n22_n27_n34_n36 = $ND(n22,n27,n34,n36);
  assign ND_n12_n16_n20_n21_n22_n23_n26_n34 = $ND(n12,n16,n20,n21,n22,n23,n26,n34);
  assign ND_n32_n8 = $ND(n32,n8);
  assign ND_n22_n36 = $ND(n22,n36);
  assign ND_n12_n18_n21_n22_n23_n36_n42_n45 = $ND(n12,n18,n21,n22,n23,n36,n42,n45);
  assign ND_n21_n22_n26_n34 = $ND(n21,n22,n26,n34);
  assign ND_n12_n21_n22_n23 = $ND(n12,n21,n22,n23);
  assign ND_n12_n16_n18_n2_n20_n21_n22_n23_n26_n27_n33_n34_n36_n42_n45 = $ND(n12,n16,n18,n2,n20,n21,n22,n23,n26,n27,n33,n34,n36,n42,n45);
  assign fair0 = (state == n33) || (state == n12) || (state == n16) || (state == n18) || (state == n42) || (state == n20) || (state == n23);
  assign fair1 = (state == n2) || (state == n33) || (state == n36) || (state == n18) || (state == n42) || (state == n45) || (state == n27);
  assign fair2 = (state == n2) || (state == n33) || (state == n34) || (state == n16) || (state == n20) || (state == n26) || (state == n27);
  assign fair3 = (state == n2) || (state == n12) || (state == n16) || (state == n42) || (state == n21) || (state == n45) || (state == n26);

    assign scc = (state == n20) || (state == n2) || (state == n21) || (state == n12) || (state == n22) || (state == n23) || (state == n33) || (state == n42) || (state == n34) || (state == n16) || (state == n26) || (state == n27) || (state == n45) || (state == n36) || (state == n18);

  initial state = n32;
  always @ (posedge clock) begin
    case (state)
      n2,n12,n16,n18,n20,n21,n22,n23,n26,n27,n33,n34,n36,n42,n45:
	case ({pc0L6,pc1L0,pc1L6,pc2L0,pc2L6})
	5'b00000: state = ND_n12_n16_n18_n2_n20_n21_n22_n23_n26_n27_n33_n34_n36_n42_n45;
	5'b00001: state = ND_n12_n18_n21_n22_n23_n36_n42_n45;
	5'b00010: state = ND_n2_n21_n22_n26_n27_n34_n36_n45;
	5'b00011: state = ND_n21_n22_n36_n45;
	5'b00100: state = ND_n12_n16_n20_n21_n22_n23_n26_n34;
	5'b00101: state = ND_n12_n21_n22_n23;
	5'b00110: state = ND_n21_n22_n26_n34;
	5'b00111: state = ND_n21_n22;
	5'b01000: state = ND_n18_n20_n22_n23_n27_n33_n34_n36;
	5'b01001: state = ND_n18_n22_n23_n36;
	5'b01010: state = ND_n22_n27_n34_n36;
	5'b01011: state = ND_n22_n36;
	5'b01100: state = ND_n20_n22_n23_n34;
	5'b01101: state = ND_n22_n23;
	5'b01110: state = ND_n22_n34;
	5'b01111: state = n22;
	5'b1????: state = Trap;
	endcase
      Trap:
	state = Trap;
      n8:
	case (pc0L6)
	1'b0: state = n22;
	1'b1: state = Trap;
	endcase
      n32:
	case ({interested0is1,pc0L6})
	2'b0?: state = n32;
	2'b10: state = ND_n32_n8;
	2'b11: state = n32;
	endcase
    endcase
  end
endmodule
