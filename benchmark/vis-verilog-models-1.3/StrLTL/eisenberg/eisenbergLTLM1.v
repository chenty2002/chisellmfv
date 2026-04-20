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
    wire 		pc0L12,pc1L12,pc2L12, pc0L16, pc1L16, pc2L16, pc0L1;
    assign 		pc0L12 = pc[0]==L12;
    assign 		pc1L12 = pc[1]==L12;
    assign 		pc2L12 = pc[2]==L12;

    assign 		pc0L16 = pc[0]==L16;
    assign 		pc1L16 = pc[1]==L16;
    assign 		pc2L16 = pc[2]==L16;

    assign 		pc0L1 =  pc[0]==L1;

Buechi Buechi(clock,pc1L16,pc2L12,pc0L12,pc1L12,pc2L16,pc0L1,fair0,fair1,fair2,fair3,scc);    
    
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

typedef enum {n2,n4,n11,n12,n19,n20,n21,n30,n31,n32,n34,n38,n40,n42,n43,n47,n48,Trap} states;

module Buechi(clock,pc1L16,pc2L12,pc0L12,pc1L12,pc2L16,pc0L1,fair0,fair1,fair2,fair3,scc);
  input clock,pc1L16,pc2L12,pc0L12,pc1L12,pc2L16,pc0L1;
  output fair0,fair1,fair2,fair3,scc;
  states reg state;
  states wire ND_n34_n40;
  states wire ND_n20_n30_n34_n40;
  states wire ND_n38_n40;
  states wire ND_n34_n38_n40_n42;
  states wire ND_n2_n20_n30_n34_n38_n40_n42_n43;
  states wire ND_n12_n40;
  states wire ND_n12_n19_n38_n40;
  states wire ND_n31_n4;
  states wire ND_n12_n21_n34_n40;
  states wire ND_n12_n19_n21_n34_n38_n40_n42_n47;
  states wire ND_n11_n12_n19_n2_n20_n32_n38_n40;
  states wire ND_n11_n12_n20_n21_n30_n34_n40_n48;
  states wire ND_n11_n12_n19_n2_n20_n21_n30_n32_n34_n38_n40_n42_n43_n47_n48;
  states wire ND_n20_n40;
  states wire ND_n2_n20_n38_n40;
  states wire ND_n11_n12_n20_n40;
  assign ND_n34_n40 = $ND(n34,n40);
  assign ND_n20_n30_n34_n40 = $ND(n20,n30,n34,n40);
  assign ND_n38_n40 = $ND(n38,n40);
  assign ND_n34_n38_n40_n42 = $ND(n34,n38,n40,n42);
  assign ND_n2_n20_n30_n34_n38_n40_n42_n43 = $ND(n2,n20,n30,n34,n38,n40,n42,n43);
  assign ND_n12_n40 = $ND(n12,n40);
  assign ND_n12_n19_n38_n40 = $ND(n12,n19,n38,n40);
  assign ND_n31_n4 = $ND(n31,n4);
  assign ND_n12_n21_n34_n40 = $ND(n12,n21,n34,n40);
  assign ND_n12_n19_n21_n34_n38_n40_n42_n47 = $ND(n12,n19,n21,n34,n38,n40,n42,n47);
  assign ND_n11_n12_n19_n2_n20_n32_n38_n40 = $ND(n11,n12,n19,n2,n20,n32,n38,n40);
  assign ND_n11_n12_n20_n21_n30_n34_n40_n48 = $ND(n11,n12,n20,n21,n30,n34,n40,n48);
  assign ND_n11_n12_n19_n2_n20_n21_n30_n32_n34_n38_n40_n42_n43_n47_n48 = $ND(n11,n12,n19,n2,n20,n21,n30,n32,n34,n38,n40,n42,n43,n47,n48);
  assign ND_n20_n40 = $ND(n20,n40);
  assign ND_n2_n20_n38_n40 = $ND(n2,n20,n38,n40);
  assign ND_n11_n12_n20_n40 = $ND(n11,n12,n20,n40);
  assign fair0 = (state == n2) || (state == n32) || (state == n38) || (state == n42) || (state == n43) || (state == n19) || (state == n47);
  assign fair1 = (state == n2) || (state == n30) || (state == n32) || (state == n11) || (state == n43) || (state == n48) || (state == n20);
  assign fair2 = (state == n30) || (state == n34) || (state == n42) || (state == n43) || (state == n48) || (state == n47) || (state == n21);
  assign fair3 = (state == n32) || (state == n11) || (state == n12) || (state == n19) || (state == n48) || (state == n47) || (state == n21);

    assign scc = (state == n20) || (state == n2) || (state == n11) || (state == n21) || (state == n30) || (state == n12) || (state == n40) || (state == n32) || (state == n42) || (state == n34) || (state == n43) || (state == n19) || (state == n38) || (state == n47) || (state == n48);

  initial state = n4;
  always @ (posedge clock) begin
    case (state)
      n4:
	case ({pc0L1,pc0L12})
	2'b0?: state = n4;
	2'b10: state = ND_n31_n4;
	2'b11: state = n4;
	endcase
      n31:
	case (pc0L12)
	1'b0: state = n40;
	1'b1: state = Trap;
	endcase
      Trap:
	state = Trap;
      n2,n11,n12,n19,n20,n21,n30,n32,n34,n38,n40,n42,n43,n47,n48:
	case ({pc0L12,pc1L12,pc1L16,pc2L12,pc2L16})
	5'b00000: state = ND_n11_n12_n19_n2_n20_n21_n30_n32_n34_n38_n40_n42_n43_n47_n48;
	5'b00001: state = ND_n11_n12_n19_n2_n20_n32_n38_n40;
	5'b00010: state = ND_n12_n19_n21_n34_n38_n40_n42_n47;
	5'b00011: state = ND_n12_n19_n38_n40;
	5'b00100: state = ND_n11_n12_n20_n21_n30_n34_n40_n48;
	5'b00101: state = ND_n11_n12_n20_n40;
	5'b00110: state = ND_n12_n21_n34_n40;
	5'b00111: state = ND_n12_n40;
	5'b01000: state = ND_n2_n20_n30_n34_n38_n40_n42_n43;
	5'b01001: state = ND_n2_n20_n38_n40;
	5'b01010: state = ND_n34_n38_n40_n42;
	5'b01011: state = ND_n38_n40;
	5'b01100: state = ND_n20_n30_n34_n40;
	5'b01101: state = ND_n20_n40;
	5'b01110: state = ND_n34_n40;
	5'b01111: state = n40;
	5'b1????: state = Trap;
	endcase
    endcase
  end
endmodule
