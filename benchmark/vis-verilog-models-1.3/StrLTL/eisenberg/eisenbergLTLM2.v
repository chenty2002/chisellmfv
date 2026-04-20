// Eisenberg-McGuire's mutual exclusion algorithm.
//
// This parameterized implementation emulates interleaving
// of the system process by a nondeterministic global selector.
// Ties among processes with the same ticket number are broken according
// to a fixed priority scheme:  a process with lower index has precedence
// over one with higher index.

// Type of program counter locations.
typedef enum {L1, L2, L3, L4, L5, L6, L7, L8, L9, L10, L11, L12, L13,
              L14, L15, L16} loc;

// Type of process activity.
typedef enum {idle, waiting, active} activity;

module eisenberg(clock,select,pause);
    // MSB of the tickets.
    // Highest process index.  Indices start at 0.
    parameter 		HIPROC = 2;
    // MSB of the process index variables.  Enough bits should be given
    // to represent HIPROC+1
    parameter		SELMSB = 1;
    input 		clock;
    // Nondeterministic selection of enabled process.
    input [SELMSB:0] 	select;
    // Nondeterministic choice between pause and progress.
    input 		pause;

    // The activity flags of the processes.
    activity reg	flag[0:HIPROC];
    // Whose turn it is to enter the CS.
    reg [SELMSB:0]	turn;
    // The program counters of the processes.
    loc reg             pc[0:HIPROC];
    // The loop indices of the processors.
    reg [SELMSB:0] 	j[0:HIPROC];
    // The latched values of the process  variables.
    // These variables appear in the fairness constraints.
    reg [SELMSB:0] 	selReg;
    // Register used to hold j[sel].  It could be replaced by a wire,
    // but the BDDs would suffer.
    reg [SELMSB:0] 	k;
    integer 		i;


    // for compositional LTL model checking use
    wire 		pc0L12,pc1L12,pc2L12, pc0L1, pc1L1, pc2L1;
    assign 		pc0L12 = pc[0]==L12;
    assign 		pc1L12 = pc[1]==L12;
    assign 		pc2L12 = pc[2]==L12;

    assign 		pc0L1 = pc[0]==L1;
    assign 		pc1L1 = pc[1]==L1;
    assign 		pc2L1 = pc[2]==L1;

Buechi Buechi(clock,pc2L12,pc0L12,pc1L12,pc2L1,pc0L1,pc1L1,fair0,fair1,scc);
    
    task process;
	input [SELMSB:0] sel;
	begin: _process
	    case (pc[sel])
	      L1: begin flag[sel] = waiting; pc[sel] = L2; end
	      L2: begin j[sel] = turn; pc[sel] = L3; end
	      // while (j != sel)
	      L3: begin if (j[sel]!=sel) pc[sel] = L4; else pc[sel] = L7; end
	      // if (flag[j] != idle)
	      L4: begin
		  k = j[sel];
		  if (flag[k] != idle) pc[sel] = L5; else pc[sel] = L6;
	      end
	      // then j = turn;
	      L5: begin j[sel] = turn; pc[sel] = L3; end
	      // else j = j + 1 mod HIPROC+1
	      L6: begin
		  if (j[sel] == HIPROC) j[sel] = 0; else j[sel] = j[sel] + 1;
		  pc[sel] = L3;
	      end
	      L7: begin flag[sel] = active; pc[sel] = L8; end
	      L8: begin j[sel] = 0; pc[sel] = L9; end
	      // while ((j<=HIPROC) && (j==i || flag[j] != in_cs)) do j=j+1;
	      L9: begin
		  k = j[sel];
		  if (j[sel] <= HIPROC && (k==sel || flag[k]!=active)) begin
		      j[sel] = k+1; pc[sel] = L9;
		  end else begin
		      pc[sel] = L10;
		  end
	      end
	      L10: begin
		  if (j[sel] > HIPROC && (turn==sel || flag[turn]==idle))
		    pc[sel]=L11;
		  else
		    pc[sel]=L1;
	      end
	      L11: begin turn = sel; pc[sel] = L12; end
	      // Critical section.
	      L12: begin if (pause) pc[sel] = L12; else pc[sel] = L13; end
	      // j = turn+1 mod (HIPROC+1);
	      L13: begin
		  if (turn==HIPROC) j[sel] = 0; else j[sel] = turn + 1;
		  pc[sel] = L14;
	      end
	      // while (flag[j] == idle) do j=j+1 mod (HIPROC+1);
	      L14: begin
		  k = j[sel];
		  if (flag[k] == idle) begin
		      if (k==HIPROC) j[sel] = 0; else j[sel] = k + 1;
		      pc[sel] = L14;
		  end else begin
		      pc[sel] = L15;
		  end
	      end
	      L15: begin turn = j[sel]; pc[sel] = L16; end
	      L16: begin
		  flag[sel] = idle;
		  if (pause) pc[sel] = L16; else pc[sel] = L1;
	      end
	    endcase
	end
    endtask // process

    initial begin
	for (i = 0; i <= HIPROC; i = i + 1) begin
	    flag[i] = idle;
	    pc[i] = L1;
	    j[i] = 0;
	end
	k = 0;
	turn = 0;
	selReg = 0;
    end

    always @ (posedge clock) begin
	if (select > HIPROC)
	  selReg = 0;
	else
	  selReg = select;
	process(selReg);
    end

endmodule // eisenberg

typedef enum {Init,n1,n2,n3,n4,n5,n6,n7,n8,n9,n10,n11,n12,n13,n14,n15,n16,n17,n18,n19,n20,n21,n22,n23,n24,n25,n26,n27,Trap} states;

module Buechi(clock,pc2L12,pc0L12,pc1L12,pc2L1,pc0L1,pc1L1,fair0,fair1,scc);
  input clock,pc2L12,pc0L12,pc1L12,pc2L1,pc0L1,pc1L1;
  output fair0,fair1,scc;
  states reg state;
  states wire ND_n15_n9;
  states wire ND_n15_n21_n25_n26;
  states wire ND_n7_n9;
  states wire ND_n1_n16_n17_n18_n23_n4;
  states wire ND_n15_n2_n20_n26_n7_n9;
  states wire ND_n13_n17_n18_n5;
  states wire ND_n13_n18_n23_n27;
  states wire ND_n19_n9;
  states wire ND_n16_n17_n18_n4;
  states wire ND_n13_n16_n17_n18_n4_n5;
  states wire ND_n10_n11_n15_n20_n22_n25;
  states wire ND_n10_n11_n12_n14_n15_n19_n2_n20_n21_n22_n24_n25_n26_n3_n6_n7_n8_n9;
  states wire ND_n15_n22_n25;
  states wire ND_n15_n21_n25_n26_n6_n9;
  states wire ND_n15_n19_n22_n26_n3_n9;
  states wire ND_n10_n11_n15_n19_n20_n22_n24_n25_n6_n7_n8_n9;
  states wire ND_n10_n25;
  states wire ND_n15_n20;
  states wire ND_n15_n19_n22_n25_n6_n9;
  states wire ND_n17_n18_n23;
  states wire ND_n21_n25;
  states wire ND_n1_n18_n23_n4;
  states wire ND_n15_n22;
  states wire ND_n24_n6;
  states wire ND_n15_n22_n26_n3;
  states wire ND_n1_n13_n16_n17_n18_n23_n27_n4_n5;
  states wire ND_n13_n27;
  states wire ND_n13_n18;
  states wire ND_n15_n25;
  states wire ND_n18_n23;
  states wire ND_n15_n26;
  states wire ND_n16_n17;
  states wire ND_n15_n20_n7_n9;
  states wire ND_n15_n19_n21_n22_n25_n26_n3_n6_n9;
  states wire ND_n10_n15_n20_n24_n25_n6_n7_n9;
  states wire ND_n10_n14_n15_n2_n20_n21_n24_n25_n26_n6_n7_n9;
  states wire ND_n10_n11_n12_n14_n15_n2_n20_n21_n22_n25_n26_n3;
  states wire ND_n17_n18;
  states wire ND_n15_n25_n6_n9;
  states wire ND_n11_n12_n15_n19_n2_n20_n22_n26_n3_n7_n8_n9;
  states wire ND_n11_n12_n15_n2_n20_n22_n26_n3;
  states wire ND_n15_n19_n22_n9;
  states wire ND_n13_n18_n4;
  states wire ND_n11_n15_n19_n20_n22_n7_n8_n9;
  states wire ND_n11_n15_n20_n22;
  states wire ND_n13_n17_n18_n23_n27_n5;
  states wire ND_n10_n14_n21_n25;
  states wire ND_n10_n14_n15_n2_n20_n21_n25_n26;
  states wire ND_n10_n15_n20_n25;
  states wire ND_n19_n7_n8_n9;
  states wire ND_n15_n2_n20_n26;
  states wire ND_n15_n26_n9;
  states wire ND_n18_n4;
  states wire ND_n15_n21_n22_n25_n26_n3;
  states wire ND_n1_n13_n18_n23_n27_n4;
  assign ND_n15_n9 = $ND(n15,n9);
  assign ND_n15_n21_n25_n26 = $ND(n15,n21,n25,n26);
  assign ND_n7_n9 = $ND(n7,n9);
  assign ND_n1_n16_n17_n18_n23_n4 = $ND(n1,n16,n17,n18,n23,n4);
  assign ND_n15_n2_n20_n26_n7_n9 = $ND(n15,n2,n20,n26,n7,n9);
  assign ND_n13_n17_n18_n5 = $ND(n13,n17,n18,n5);
  assign ND_n13_n18_n23_n27 = $ND(n13,n18,n23,n27);
  assign ND_n19_n9 = $ND(n19,n9);
  assign ND_n16_n17_n18_n4 = $ND(n16,n17,n18,n4);
  assign ND_n13_n16_n17_n18_n4_n5 = $ND(n13,n16,n17,n18,n4,n5);
  assign ND_n10_n11_n15_n20_n22_n25 = $ND(n10,n11,n15,n20,n22,n25);
  assign ND_n10_n11_n12_n14_n15_n19_n2_n20_n21_n22_n24_n25_n26_n3_n6_n7_n8_n9 = $ND(n10,n11,n12,n14,n15,n19,n2,n20,n21,n22,n24,n25,n26,n3,n6,n7,n8,n9);
  assign ND_n15_n22_n25 = $ND(n15,n22,n25);
  assign ND_n15_n21_n25_n26_n6_n9 = $ND(n15,n21,n25,n26,n6,n9);
  assign ND_n15_n19_n22_n26_n3_n9 = $ND(n15,n19,n22,n26,n3,n9);
  assign ND_n10_n11_n15_n19_n20_n22_n24_n25_n6_n7_n8_n9 = $ND(n10,n11,n15,n19,n20,n22,n24,n25,n6,n7,n8,n9);
  assign ND_n10_n25 = $ND(n10,n25);
  assign ND_n15_n20 = $ND(n15,n20);
  assign ND_n15_n19_n22_n25_n6_n9 = $ND(n15,n19,n22,n25,n6,n9);
  assign ND_n17_n18_n23 = $ND(n17,n18,n23);
  assign ND_n21_n25 = $ND(n21,n25);
  assign ND_n1_n18_n23_n4 = $ND(n1,n18,n23,n4);
  assign ND_n15_n22 = $ND(n15,n22);
  assign ND_n24_n6 = $ND(n24,n6);
  assign ND_n15_n22_n26_n3 = $ND(n15,n22,n26,n3);
  assign ND_n1_n13_n16_n17_n18_n23_n27_n4_n5 = $ND(n1,n13,n16,n17,n18,n23,n27,n4,n5);
  assign ND_n13_n27 = $ND(n13,n27);
  assign ND_n13_n18 = $ND(n13,n18);
  assign ND_n15_n25 = $ND(n15,n25);
  assign ND_n18_n23 = $ND(n18,n23);
  assign ND_n15_n26 = $ND(n15,n26);
  assign ND_n16_n17 = $ND(n16,n17);
  assign ND_n15_n20_n7_n9 = $ND(n15,n20,n7,n9);
  assign ND_n15_n19_n21_n22_n25_n26_n3_n6_n9 = $ND(n15,n19,n21,n22,n25,n26,n3,n6,n9);
  assign ND_n10_n15_n20_n24_n25_n6_n7_n9 = $ND(n10,n15,n20,n24,n25,n6,n7,n9);
  assign ND_n10_n14_n15_n2_n20_n21_n24_n25_n26_n6_n7_n9 = $ND(n10,n14,n15,n2,n20,n21,n24,n25,n26,n6,n7,n9);
  assign ND_n10_n11_n12_n14_n15_n2_n20_n21_n22_n25_n26_n3 = $ND(n10,n11,n12,n14,n15,n2,n20,n21,n22,n25,n26,n3);
  assign ND_n17_n18 = $ND(n17,n18);
  assign ND_n15_n25_n6_n9 = $ND(n15,n25,n6,n9);
  assign ND_n11_n12_n15_n19_n2_n20_n22_n26_n3_n7_n8_n9 = $ND(n11,n12,n15,n19,n2,n20,n22,n26,n3,n7,n8,n9);
  assign ND_n11_n12_n15_n2_n20_n22_n26_n3 = $ND(n11,n12,n15,n2,n20,n22,n26,n3);
  assign ND_n15_n19_n22_n9 = $ND(n15,n19,n22,n9);
  assign ND_n13_n18_n4 = $ND(n13,n18,n4);
  assign ND_n11_n15_n19_n20_n22_n7_n8_n9 = $ND(n11,n15,n19,n20,n22,n7,n8,n9);
  assign ND_n11_n15_n20_n22 = $ND(n11,n15,n20,n22);
  assign ND_n13_n17_n18_n23_n27_n5 = $ND(n13,n17,n18,n23,n27,n5);
  assign ND_n10_n14_n21_n25 = $ND(n10,n14,n21,n25);
  assign ND_n10_n14_n15_n2_n20_n21_n25_n26 = $ND(n10,n14,n15,n2,n20,n21,n25,n26);
  assign ND_n10_n15_n20_n25 = $ND(n10,n15,n20,n25);
  assign ND_n19_n7_n8_n9 = $ND(n19,n7,n8,n9);
  assign ND_n15_n2_n20_n26 = $ND(n15,n2,n20,n26);
  assign ND_n15_n26_n9 = $ND(n15,n26,n9);
  assign ND_n18_n4 = $ND(n18,n4);
  assign ND_n15_n21_n22_n25_n26_n3 = $ND(n15,n21,n22,n25,n26,n3);
  assign ND_n1_n13_n18_n23_n27_n4 = $ND(n1,n13,n18,n23,n27,n4);
  assign fair0 = (state == n1) || (state == n4) || (state == n5) || (state == n13) || (state == n16) || (state == n27);
  assign fair1 = (state == n1) || (state == n5) || (state == n16) || (state == n17) || (state == n23) || (state == n27);
    
    assign scc = (state == n4) || (state == n13) || (state == n23) || (state == n5) || (state == n16) || (state == n17) || (state == n27) || (state == n1) || (state == n18);

  initial state = Init;
  always @ (posedge clock) begin
    case (state)
      Trap:
	state = Trap;
      n10,n11:
	case ({pc0L1,pc1L12,pc2L12})
	3'b000: state = n17;
	3'b0?1: state = Trap;
	3'b010: state = ND_n16_n17;
	3'b1??: state = Trap;
	endcase
      Init:
	case ({pc0L1,pc0L12,pc1L1,pc1L12,pc2L1,pc2L12})
	6'b00000?: state = ND_n15_n25_n6_n9;
	6'b000010: state = ND_n10_n15_n20_n24_n25_n6_n7_n9;
	6'b000011: state = ND_n15_n25_n6_n9;
	6'b00010?: state = ND_n15_n21_n25_n26_n6_n9;
	6'b000110: state = ND_n10_n14_n15_n2_n20_n21_n24_n25_n26_n6_n7_n9;
	6'b000111: state = ND_n15_n21_n25_n26_n6_n9;
	6'b00100?: state = ND_n15_n25;
	6'b001010: state = ND_n10_n15_n20_n25;
	6'b001011: state = ND_n15_n25;
	6'b00110?: state = ND_n15_n21_n25_n26;
	6'b001110: state = ND_n10_n14_n15_n2_n20_n21_n25_n26;
	6'b001111: state = ND_n15_n21_n25_n26;
	6'b01000?: state = ND_n15_n19_n22_n25_n6_n9;
	6'b010010: state = ND_n10_n11_n15_n19_n20_n22_n24_n25_n6_n7_n8_n9;
	6'b010011: state = ND_n15_n19_n22_n25_n6_n9;
	6'b01010?: state = ND_n15_n19_n21_n22_n25_n26_n3_n6_n9;
	6'b010110: state = ND_n10_n11_n12_n14_n15_n19_n2_n20_n21_n22_n24_n25_n26_n3_n6_n7_n8_n9;
	6'b010111: state = ND_n15_n19_n21_n22_n25_n26_n3_n6_n9;
	6'b01100?: state = ND_n15_n22_n25;
	6'b011010: state = ND_n10_n11_n15_n20_n22_n25;
	6'b011011: state = ND_n15_n22_n25;
	6'b01110?: state = ND_n15_n21_n22_n25_n26_n3;
	6'b011110: state = ND_n10_n11_n12_n14_n15_n2_n20_n21_n22_n25_n26_n3;
	6'b011111: state = ND_n15_n21_n22_n25_n26_n3;
	6'b10000?: state = ND_n15_n9;
	6'b100010: state = ND_n15_n20_n7_n9;
	6'b100011: state = ND_n15_n9;
	6'b10010?: state = ND_n15_n26_n9;
	6'b100110: state = ND_n15_n2_n20_n26_n7_n9;
	6'b100111: state = ND_n15_n26_n9;
	6'b10100?: state = n15;
	6'b101010: state = ND_n15_n20;
	6'b101011: state = n15;
	6'b10110?: state = ND_n15_n26;
	6'b101110: state = ND_n15_n2_n20_n26;
	6'b101111: state = ND_n15_n26;
	6'b11000?: state = ND_n15_n19_n22_n9;
	6'b110010: state = ND_n11_n15_n19_n20_n22_n7_n8_n9;
	6'b110011: state = ND_n15_n19_n22_n9;
	6'b11010?: state = ND_n15_n19_n22_n26_n3_n9;
	6'b110110: state = ND_n11_n12_n15_n19_n2_n20_n22_n26_n3_n7_n8_n9;
	6'b110111: state = ND_n15_n19_n22_n26_n3_n9;
	6'b11100?: state = ND_n15_n22;
	6'b111010: state = ND_n11_n15_n20_n22;
	6'b111011: state = ND_n15_n22;
	6'b11110?: state = ND_n15_n22_n26_n3;
	6'b111110: state = ND_n11_n12_n15_n2_n20_n22_n26_n3;
	6'b111111: state = ND_n15_n22_n26_n3;
	endcase
      n3,n6,n19,n21:
	case ({pc0L1,pc1L1,pc2L1,pc2L12})
	4'b000?: state = n6;
	4'b0010: state = ND_n24_n6;
	4'b0011: state = n6;
	4'b01??: state = Trap;
	4'b1???: state = Trap;
	endcase
      n18,n20:
	case ({pc0L12,pc1L12,pc2L12})
	3'b000: state = n18;
	3'b??1: state = Trap;
	3'b010: state = ND_n18_n4;
	3'b100: state = ND_n18_n23;
	3'b110: state = ND_n1_n18_n23_n4;
	endcase
      n17,n23:
	case ({pc0L1,pc0L12,pc1L12,pc2L12})
	4'b0000: state = ND_n17_n18;
	4'b???1: state = Trap;
	4'b0010: state = ND_n16_n17_n18_n4;
	4'b0100: state = ND_n17_n18_n23;
	4'b0110: state = ND_n1_n16_n17_n18_n23_n4;
	4'b1000: state = n18;
	4'b1010: state = ND_n18_n4;
	4'b1100: state = ND_n18_n23;
	4'b1110: state = ND_n1_n18_n23_n4;
	endcase
      n8,n12,n14,n24:
	case ({pc0L1,pc1L1,pc2L12})
	3'b000: state = n5;
	3'b001: state = Trap;
	3'b01?: state = Trap;
	3'b1??: state = Trap;
	endcase
      n15:
	case ({pc0L12,pc1L12,pc2L1,pc2L12})
	4'b000?: state = n15;
	4'b0010: state = ND_n15_n20;
	4'b0011: state = n15;
	4'b010?: state = ND_n15_n26;
	4'b0110: state = ND_n15_n2_n20_n26;
	4'b0111: state = ND_n15_n26;
	4'b100?: state = ND_n15_n22;
	4'b1010: state = ND_n11_n15_n20_n22;
	4'b1011: state = ND_n15_n22;
	4'b110?: state = ND_n15_n22_n26_n3;
	4'b1110: state = ND_n11_n12_n15_n2_n20_n22_n26_n3;
	4'b1111: state = ND_n15_n22_n26_n3;
	endcase
      n22,n25:
	case ({pc0L1,pc1L12,pc2L1,pc2L12})
	4'b000?: state = n25;
	4'b0010: state = ND_n10_n25;
	4'b0011: state = n25;
	4'b010?: state = ND_n21_n25;
	4'b0110: state = ND_n10_n14_n21_n25;
	4'b0111: state = ND_n21_n25;
	4'b1???: state = Trap;
	endcase
      n9,n26:
	case ({pc0L12,pc1L1,pc2L1,pc2L12})
	4'b000?: state = n9;
	4'b0010: state = ND_n7_n9;
	4'b0011: state = n9;
	4'b?1??: state = Trap;
	4'b100?: state = ND_n19_n9;
	4'b1010: state = ND_n19_n7_n8_n9;
	4'b1011: state = ND_n19_n9;
	endcase
      n2,n7:
	case ({pc0L12,pc1L1,pc2L12})
	3'b000: state = n13;
	3'b?01: state = Trap;
	3'b?1?: state = Trap;
	3'b100: state = ND_n13_n27;
	endcase
      n1,n5,n16,n27:
	case ({pc0L1,pc0L12,pc1L1,pc1L12,pc2L12})
	5'b00000: state = ND_n13_n17_n18_n5;
	5'b????1: state = Trap;
	5'b00010: state = ND_n13_n16_n17_n18_n4_n5;
	5'b00100: state = ND_n17_n18;
	5'b00110: state = ND_n16_n17_n18_n4;
	5'b01000: state = ND_n13_n17_n18_n23_n27_n5;
	5'b01010: state = ND_n1_n13_n16_n17_n18_n23_n27_n4_n5;
	5'b01100: state = ND_n17_n18_n23;
	5'b01110: state = ND_n1_n16_n17_n18_n23_n4;
	5'b10000: state = ND_n13_n18;
	5'b10010: state = ND_n13_n18_n4;
	5'b10100: state = n18;
	5'b10110: state = ND_n18_n4;
	5'b11000: state = ND_n13_n18_n23_n27;
	5'b11010: state = ND_n1_n13_n18_n23_n27_n4;
	5'b11100: state = ND_n18_n23;
	5'b11110: state = ND_n1_n18_n23_n4;
	endcase
      n4,n13:
	case ({pc0L12,pc1L1,pc1L12,pc2L12})
	4'b0000: state = ND_n13_n18;
	4'b???1: state = Trap;
	4'b0010: state = ND_n13_n18_n4;
	4'b0100: state = n18;
	4'b0110: state = ND_n18_n4;
	4'b1000: state = ND_n13_n18_n23_n27;
	4'b1010: state = ND_n1_n13_n18_n23_n27_n4;
	4'b1100: state = ND_n18_n23;
	4'b1110: state = ND_n1_n18_n23_n4;
	endcase
    endcase
  end
endmodule
