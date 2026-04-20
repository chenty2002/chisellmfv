// The bakery algorithm for mutual exclusion simulates a bakery in
// which customers (processes) take a numbered ticket when they enter the
// store, and then wait for their number to be called.
//
// This (almost) parameterized implementation emulates interleaving
// of the system process by a nondeterministic global selector.
//
// This implementation is finite state.  Rather than holding a numerical
// ticket, processes update a matrix that keeps track of the relative age
// of their tickets.  When a process wants to enter the critical region,
// it records the indices of all processes currently holding a ticket.
// These are the processes to which it will defer.  Hence, the information
// stored in a matrix called "defer."
//
// Ties among processes with ticket of the same age are broken according
// to a fixed priority scheme:  a process with lower index has precedence
// over one with higher index.
//
// On exit from the critical region, a process has to clear all the deference
// bits in which it is one of the two parties to prevent deadlock.
//
// Due to restrictions imposed by vl2mv, the parameterization of this
// description is incomplete.  Besides changing the values of the
// parameters, one also has to modify the two functions extract and
// setBit.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

// Type of program counter locations.
typedef enum {L1, L2a, L2b, L2c, L3, L4, L5, L6, L7, L8, L9,
              L10a, L10b, L10c, L11} loc;

module bakery(clock,select,pause);
    // MSB of the process index variables.  Enough bits should be given
    // to represent HIPROC+1
    parameter		SELMSB = 2;
    // Highest process index.  Indices start at 0.
    // It must be HIPROC < 2**(SELMSB+1).
    parameter 		HIPROC = 2;
    input 		clock;
    // Nondeterministic selection of enabled process.
    input [SELMSB:0] 	select;
    // Nondeterministic choice between pause and progress.
    input 		pause;

    // The ticket-holding flags of the processes.
    reg			ticket[0:HIPROC];
    // More than one process may be choosing a ticket.  Hence, more than
    // one process may end up with the same ticket number.  These global
    // variables allow one process to wait for completion of a choice
    // that is in progress before comparing its ticket to that of another
    // process.  If the ticket number is the same, the process index is
    // used to decide which process accesses the critical section.
    reg 		choosing[0:HIPROC];
    // The program counters of the processes.
    loc reg             pc[0:HIPROC];
    // The loop indices of the processors.
    reg [SELMSB:0] 	j[0:HIPROC];
    // The latched value of the process selection variable.
    // Th1s variable appears in the fairness constraints.
    reg [SELMSB:0] 	selReg;
    // Register used to hold j[sel].  It could be replaced by a wire,
    // but the BDDs would suffer.
    reg [SELMSB:0] 	k;
    reg [HIPROC:0]	defer[0:HIPROC];
    reg [SELMSB:0] 	pri[0:HIPROC];
    reg 		defSelK, defKSel;
    integer 		i;

    wire 	pc0L4,pc1L4,pc2L9,pc2L4,pc1L9,pc0L9,scc;	


    // Extract one bit from a vector.
    // WARNING: change if HIPROC is modified.
    function extract;
	input [HIPROC:0] in;
	input [SELMSB:0] index;
	begin: _extract
	    if (index == 0)
	      extract = in[0];
	    else if (index == 1)
	      extract = in[1];
	    else if (index == 2)
	      extract = in[2];
	    else
	      extract = 0;	// should not happen
	end
    endfunction // extract


    // Returns the first input with the bit selected by the second input
    // set to the value of the third input.
    // WARNING: change if HIPROC is modified.
    function [HIPROC:0] setBit;
	input [HIPROC:0] in;
	input [SELMSB:0] index;
	input val;
	begin: _setBit
	    setBit = in;
	    if (index == 0)
	      setBit[0] = val;
	    else if (index == 1)
	      setBit[1] = val;
	    else if (index == 2)
	      setBit[2] = val;
	end
    endfunction // setBit


    task process;
	input [SELMSB:0] sel;
	begin: _process
	    case (pc[sel])
	      L1: begin choosing[sel] = 1; pc[sel] = L2a; end
	      L2a: begin j[sel] = 0; pc[sel] = L2b; end
	      L2b: if (j[sel] <= HIPROC) pc[sel] = L2c; else pc[sel] = L3;
	      L2c: begin
		  k = j[sel];
		  defer[sel] = setBit(defer[sel], k, ticket[k]);
		  j[sel] = k + 1;
		  pc[sel] = L2b;
	      end
	      L3: begin ticket[sel] = 1; choosing[sel] = 0; pc[sel] = L4; end
	      // Loop over all processes to check ticket.
	      L4: begin j[sel] = 0; pri[sel] = sel; pc[sel] = L5; end
	      L5: begin
		  if (j[sel] <= HIPROC) pc[sel] = L6; else pc[sel] = L9;
	      end
	      // Wait while (choosing[j[sel]])
	      L6: begin
		  k = j[sel];
		  if (choosing[k]) pc[sel] = L6; else pc[sel] = L7;
	      end
	      // Wait while process j[sel] has an older ticket, or it
	      // has a ticket of the same age and higher priority.
	      L7: begin
		  k = j[sel];
		  defSelK = extract(defer[sel],k);
		  defKSel = extract(defer[k],sel);
		  if (ticket[k] && defSelK && !defKSel && pri[sel] < pri[k])
		      pri[k] = pri[sel];
		  if (ticket[k] && (defSelK || (!defKSel && (pri[k] < pri[sel]))))
		    pc[sel] = L7;
		  else
		    pc[sel] = L8;
	      end
	      L8: begin j[sel] = j[sel] + 1; pri[sel] = sel; pc[sel] = L5; end
	      // Enter critical section.
	      L9: begin if (pause) pc[sel] = L9; else pc[sel] = L10a; end
	      // Leave critical section.
	      L10a: begin ticket[sel] = 0; j[sel] = 0; pc[sel] = L10b; end
	      L10b: if (j[sel] <= HIPROC) pc[sel] = L10c; else pc[sel] = L11;
	      L10c: begin
		  k = j[sel];
		  defer[k] = setBit(defer[k],sel,1'b0);
		  if (pri[k]==sel) pri[k] = k;
		  j[sel] = k + 1;
		  pc[sel] = L10b;
	      end
	      L11: begin if (pause) pc[sel] = L11; else pc[sel] = L1; end
	    endcase
	end
    endtask // process

    initial begin
	for (i = 0; i <= HIPROC; i = i + 1) begin
	    ticket[i] = 0;
	    defer[i] = 0;
	    pri[i] = i;
	    choosing[i] = 0;
	    pc[i] = L1;
	    j[i] = 0;
	end
	k = 0;
	selReg = 0;
	defSelK = 0;
	defKSel = 0;
    end

    always @ (posedge clock) begin
	if (select > HIPROC)
	  selReg = 0;
	else
	  selReg = select;
	process(selReg);
    end

    assign pc0L4 = pc[0]==L4;
    assign pc0L9 = pc[0]==L9;
    assign pc1L4 = pc[1]==L4;
    assign pc1L9 = pc[1]==L9;
    assign pc2L4 = pc[2]==L4;
    assign pc2L9 = pc[2]==L9;
    Buechi Buechi(clock,pc0L4,pc1L4,pc2L9,pc2L4,pc1L9,pc0L9,fair0,fair1,scc);

endmodule // bakery_ot


typedef enum {Init,n1,n2,n3,n4,n5,n6,n7,n8,n9,n10,n11,n12,n13,n14,n15,n16,n17,n18,n19,n20,n21,n22,n23,n24,n25,n26,n27,Trap} states;

module Buechi(clock,pc0L4,pc1L4,pc2L9,pc2L4,pc1L9,pc0L9,fair0,fair1,scc);
  input clock,pc0L4,pc1L4,pc2L9,pc2L4,pc1L9,pc0L9;
  output fair0,fair1,scc;
  states reg state;
  states wire ND_n1_n12_n19_n25_n5_n7;
  states wire ND_n16_n8;
  states wire ND_n16_n17_n21_n24;
  states wire ND_n15_n16_n22_n24_n4_n6;
  states wire ND_n13_n15_n16_n3_n6_n8;
  states wire ND_n13_n15_n16_n22_n24_n8;
  states wire ND_n10_n15_n16_n17_n21_n22_n24_n27;
  states wire ND_n14_n16_n17_n8;
  states wire ND_n14_n16_n17_n21_n24_n8;
  states wire ND_n10_n13_n14_n15_n16_n17_n2_n20_n21_n22_n23_n24_n27_n3_n4_n6_n8_n9;
  states wire ND_n12_n18_n19_n26;
  states wire ND_n11_n12_n18_n19_n26_n7;
  states wire ND_n20_n4;
  states wire ND_n12_n19_n7;
  states wire ND_n12_n5;
  states wire ND_n21_n24;
  states wire ND_n22_n24;
  states wire ND_n12_n7;
  states wire ND_n25_n5;
  states wire ND_n12_n18;
  states wire ND_n11_n12_n18_n7;
  states wire ND_n1_n11_n12_n18_n19_n25_n26_n5_n7;
  states wire ND_n12_n19;
  states wire ND_n16_n24;
  states wire ND_n15_n16;
  states wire ND_n16_n17_n2_n6;
  states wire ND_n14_n16_n17_n2_n20_n21_n23_n24_n3_n4_n6_n8;
  states wire ND_n16_n24_n4_n6;
  states wire ND_n16_n3_n6_n8;
  states wire ND_n16_n17;
  states wire ND_n12_n19_n25_n5;
  states wire ND_n16_n24_n3_n4_n6_n8;
  states wire ND_n13_n15_n16_n8;
  states wire ND_n14_n16_n17_n2_n23_n3_n6_n8;
  states wire ND_n10_n13_n14_n15_n16_n17_n2_n23_n3_n6_n8_n9;
  states wire ND_n12_n18_n5;
  states wire ND_n10_n13_n14_n15_n16_n17_n8_n9;
  states wire ND_n15_n16_n6;
  states wire ND_n2_n23_n3_n6;
  states wire ND_n1_n12_n5_n7;
  states wire ND_n2_n6;
  states wire ND_n12_n18_n19_n25_n26_n5;
  states wire ND_n16_n24_n8;
  states wire ND_n13_n15_n16_n22_n24_n3_n4_n6_n8;
  states wire ND_n3_n6;
  states wire ND_n11_n7;
  states wire ND_n1_n11_n12_n18_n5_n7;
  states wire ND_n10_n13_n14_n15_n16_n17_n21_n22_n24_n27_n8_n9;
  states wire ND_n16_n17_n2_n20_n21_n24_n4_n6;
  states wire ND_n10_n15_n16_n17_n2_n6;
  states wire ND_n21_n22_n24_n27;
  states wire ND_n10_n15_n16_n17;
  states wire ND_n16_n6;
  states wire ND_n10_n15_n16_n17_n2_n20_n21_n22_n24_n27_n4_n6;
  states wire ND_n15_n16_n22_n24;
  assign ND_n1_n12_n19_n25_n5_n7 = $ND(n1,n12,n19,n25,n5,n7);
  assign ND_n16_n8 = $ND(n16,n8);
  assign ND_n16_n17_n21_n24 = $ND(n16,n17,n21,n24);
  assign ND_n15_n16_n22_n24_n4_n6 = $ND(n15,n16,n22,n24,n4,n6);
  assign ND_n13_n15_n16_n3_n6_n8 = $ND(n13,n15,n16,n3,n6,n8);
  assign ND_n13_n15_n16_n22_n24_n8 = $ND(n13,n15,n16,n22,n24,n8);
  assign ND_n10_n15_n16_n17_n21_n22_n24_n27 = $ND(n10,n15,n16,n17,n21,n22,n24,n27);
  assign ND_n14_n16_n17_n8 = $ND(n14,n16,n17,n8);
  assign ND_n14_n16_n17_n21_n24_n8 = $ND(n14,n16,n17,n21,n24,n8);
  assign ND_n10_n13_n14_n15_n16_n17_n2_n20_n21_n22_n23_n24_n27_n3_n4_n6_n8_n9 = $ND(n10,n13,n14,n15,n16,n17,n2,n20,n21,n22,n23,n24,n27,n3,n4,n6,n8,n9);
  assign ND_n12_n18_n19_n26 = $ND(n12,n18,n19,n26);
  assign ND_n11_n12_n18_n19_n26_n7 = $ND(n11,n12,n18,n19,n26,n7);
  assign ND_n20_n4 = $ND(n20,n4);
  assign ND_n12_n19_n7 = $ND(n12,n19,n7);
  assign ND_n12_n5 = $ND(n12,n5);
  assign ND_n21_n24 = $ND(n21,n24);
  assign ND_n22_n24 = $ND(n22,n24);
  assign ND_n12_n7 = $ND(n12,n7);
  assign ND_n25_n5 = $ND(n25,n5);
  assign ND_n12_n18 = $ND(n12,n18);
  assign ND_n11_n12_n18_n7 = $ND(n11,n12,n18,n7);
  assign ND_n1_n11_n12_n18_n19_n25_n26_n5_n7 = $ND(n1,n11,n12,n18,n19,n25,n26,n5,n7);
  assign ND_n12_n19 = $ND(n12,n19);
  assign ND_n16_n24 = $ND(n16,n24);
  assign ND_n15_n16 = $ND(n15,n16);
  assign ND_n16_n17_n2_n6 = $ND(n16,n17,n2,n6);
  assign ND_n14_n16_n17_n2_n20_n21_n23_n24_n3_n4_n6_n8 = $ND(n14,n16,n17,n2,n20,n21,n23,n24,n3,n4,n6,n8);
  assign ND_n16_n24_n4_n6 = $ND(n16,n24,n4,n6);
  assign ND_n16_n3_n6_n8 = $ND(n16,n3,n6,n8);
  assign ND_n16_n17 = $ND(n16,n17);
  assign ND_n12_n19_n25_n5 = $ND(n12,n19,n25,n5);
  assign ND_n16_n24_n3_n4_n6_n8 = $ND(n16,n24,n3,n4,n6,n8);
  assign ND_n13_n15_n16_n8 = $ND(n13,n15,n16,n8);
  assign ND_n14_n16_n17_n2_n23_n3_n6_n8 = $ND(n14,n16,n17,n2,n23,n3,n6,n8);
  assign ND_n10_n13_n14_n15_n16_n17_n2_n23_n3_n6_n8_n9 = $ND(n10,n13,n14,n15,n16,n17,n2,n23,n3,n6,n8,n9);
  assign ND_n12_n18_n5 = $ND(n12,n18,n5);
  assign ND_n10_n13_n14_n15_n16_n17_n8_n9 = $ND(n10,n13,n14,n15,n16,n17,n8,n9);
  assign ND_n15_n16_n6 = $ND(n15,n16,n6);
  assign ND_n2_n23_n3_n6 = $ND(n2,n23,n3,n6);
  assign ND_n1_n12_n5_n7 = $ND(n1,n12,n5,n7);
  assign ND_n2_n6 = $ND(n2,n6);
  assign ND_n12_n18_n19_n25_n26_n5 = $ND(n12,n18,n19,n25,n26,n5);
  assign ND_n16_n24_n8 = $ND(n16,n24,n8);
  assign ND_n13_n15_n16_n22_n24_n3_n4_n6_n8 = $ND(n13,n15,n16,n22,n24,n3,n4,n6,n8);
  assign ND_n3_n6 = $ND(n3,n6);
  assign ND_n11_n7 = $ND(n11,n7);
  assign ND_n1_n11_n12_n18_n5_n7 = $ND(n1,n11,n12,n18,n5,n7);
  assign ND_n10_n13_n14_n15_n16_n17_n21_n22_n24_n27_n8_n9 = $ND(n10,n13,n14,n15,n16,n17,n21,n22,n24,n27,n8,n9);
  assign ND_n16_n17_n2_n20_n21_n24_n4_n6 = $ND(n16,n17,n2,n20,n21,n24,n4,n6);
  assign ND_n10_n15_n16_n17_n2_n6 = $ND(n10,n15,n16,n17,n2,n6);
  assign ND_n21_n22_n24_n27 = $ND(n21,n22,n24,n27);
  assign ND_n10_n15_n16_n17 = $ND(n10,n15,n16,n17);
  assign ND_n16_n6 = $ND(n16,n6);
  assign ND_n10_n15_n16_n17_n2_n20_n21_n22_n24_n27_n4_n6 = $ND(n10,n15,n16,n17,n2,n20,n21,n22,n24,n27,n4,n6);
  assign ND_n15_n16_n22_n24 = $ND(n15,n16,n22,n24);
  assign fair0 = (state == n1) || (state == n19) || (state == n25) || (state == n7) || (state == n26) || (state == n11);
  assign fair1 = (state == n1) || (state == n5) || (state == n11) || (state == n18) || (state == n25) || (state == n26);
    assign scc = (state ==n11) || (state ==n12) || (state ==n5) || (state ==n25) || (state ==n7) || (state ==n26) || (state ==n18) || (state ==n19) || (state ==n1);
  initial state = Init;
  always @ (posedge clock) begin
    case (state)
      Trap:
	state = Trap;
      n9,n20,n23,n27:
	case ({pc0L4,pc1L4,pc2L9})
	3'b000: state = n1;
	3'b001: state = Trap;
	3'b01?: state = Trap;
	3'b1??: state = Trap;
	endcase
      n8,n24:
	case ({pc0L9,pc1L4,pc2L4,pc2L9})
	4'b000?: state = n24;
	4'b0010: state = ND_n21_n24;
	4'b0011: state = n24;
	4'b?1??: state = Trap;
	4'b100?: state = ND_n22_n24;
	4'b1010: state = ND_n21_n22_n24_n27;
	4'b1011: state = ND_n22_n24;
	endcase
      n7,n19:
	case ({pc0L4,pc0L9,pc1L9,pc2L9})
	4'b0000: state = ND_n12_n7;
	4'b???1: state = Trap;
	4'b0010: state = ND_n11_n12_n18_n7;
	4'b0100: state = ND_n12_n19_n7;
	4'b0110: state = ND_n11_n12_n18_n19_n26_n7;
	4'b1000: state = n12;
	4'b1010: state = ND_n12_n18;
	4'b1100: state = ND_n12_n19;
	4'b1110: state = ND_n12_n18_n19_n26;
	endcase
      n1,n11,n25,n26:
	case ({pc0L4,pc0L9,pc1L4,pc1L9,pc2L9})
	5'b00000: state = ND_n1_n12_n5_n7;
	5'b????1: state = Trap;
	5'b00010: state = ND_n1_n11_n12_n18_n5_n7;
	5'b00100: state = ND_n12_n7;
	5'b00110: state = ND_n11_n12_n18_n7;
	5'b01000: state = ND_n1_n12_n19_n25_n5_n7;
	5'b01010: state = ND_n1_n11_n12_n18_n19_n25_n26_n5_n7;
	5'b01100: state = ND_n12_n19_n7;
	5'b01110: state = ND_n11_n12_n18_n19_n26_n7;
	5'b10000: state = ND_n12_n5;
	5'b10010: state = ND_n12_n18_n5;
	5'b10100: state = n12;
	5'b10110: state = ND_n12_n18;
	5'b11000: state = ND_n12_n19_n25_n5;
	5'b11010: state = ND_n12_n18_n19_n25_n26_n5;
	5'b11100: state = ND_n12_n19;
	5'b11110: state = ND_n12_n18_n19_n26;
	endcase
      n3,n4,n13,n22:
	case ({pc0L4,pc1L4,pc2L4,pc2L9})
	4'b000?: state = n4;
	4'b0010: state = ND_n20_n4;
	4'b0011: state = n4;
	4'b01??: state = Trap;
	4'b1???: state = Trap;
	endcase
      n5,n18:
	case ({pc0L9,pc1L4,pc1L9,pc2L9})
	4'b0000: state = ND_n12_n5;
	4'b???1: state = Trap;
	4'b0010: state = ND_n12_n18_n5;
	4'b0100: state = n12;
	4'b0110: state = ND_n12_n18;
	4'b1000: state = ND_n12_n19_n25_n5;
	4'b1010: state = ND_n12_n18_n19_n25_n26_n5;
	4'b1100: state = ND_n12_n19;
	4'b1110: state = ND_n12_n18_n19_n26;
	endcase
      n6,n15:
	case ({pc0L4,pc1L9,pc2L4,pc2L9})
	4'b000?: state = n6;
	4'b0010: state = ND_n2_n6;
	4'b0011: state = n6;
	4'b010?: state = ND_n3_n6;
	4'b0110: state = ND_n2_n23_n3_n6;
	4'b0111: state = ND_n3_n6;
	4'b1???: state = Trap;
	endcase
      n12,n17:
	case ({pc0L9,pc1L9,pc2L9})
	3'b000: state = n12;
	3'b??1: state = Trap;
	3'b010: state = ND_n12_n18;
	3'b100: state = ND_n12_n19;
	3'b110: state = ND_n12_n18_n19_n26;
	endcase
      n2,n10:
	case ({pc0L4,pc1L9,pc2L9})
	3'b000: state = n7;
	3'b0?1: state = Trap;
	3'b010: state = ND_n11_n7;
	3'b1??: state = Trap;
	endcase
      n16:
	case ({pc0L9,pc1L9,pc2L4,pc2L9})
	4'b000?: state = n16;
	4'b0010: state = ND_n16_n17;
	4'b0011: state = n16;
	4'b010?: state = ND_n16_n8;
	4'b0110: state = ND_n14_n16_n17_n8;
	4'b0111: state = ND_n16_n8;
	4'b100?: state = ND_n15_n16;
	4'b1010: state = ND_n10_n15_n16_n17;
	4'b1011: state = ND_n15_n16;
	4'b110?: state = ND_n13_n15_n16_n8;
	4'b1110: state = ND_n10_n13_n14_n15_n16_n17_n8_n9;
	4'b1111: state = ND_n13_n15_n16_n8;
	endcase
      n14,n21:
	case ({pc0L9,pc1L4,pc2L9})
	3'b000: state = n5;
	3'b?01: state = Trap;
	3'b?1?: state = Trap;
	3'b100: state = ND_n25_n5;
	endcase
      Init:
	case ({pc0L4,pc0L9,pc1L4,pc1L9,pc2L4,pc2L9})
	6'b00000?: state = ND_n16_n24_n4_n6;
	6'b000010: state = ND_n16_n17_n2_n20_n21_n24_n4_n6;
	6'b000011: state = ND_n16_n24_n4_n6;
	6'b00010?: state = ND_n16_n24_n3_n4_n6_n8;
	6'b000110: state = ND_n14_n16_n17_n2_n20_n21_n23_n24_n3_n4_n6_n8;
	6'b000111: state = ND_n16_n24_n3_n4_n6_n8;
	6'b00100?: state = ND_n16_n6;
	6'b001010: state = ND_n16_n17_n2_n6;
	6'b001011: state = ND_n16_n6;
	6'b00110?: state = ND_n16_n3_n6_n8;
	6'b001110: state = ND_n14_n16_n17_n2_n23_n3_n6_n8;
	6'b001111: state = ND_n16_n3_n6_n8;
	6'b01000?: state = ND_n15_n16_n22_n24_n4_n6;
	6'b010010: state = ND_n10_n15_n16_n17_n2_n20_n21_n22_n24_n27_n4_n6;
	6'b010011: state = ND_n15_n16_n22_n24_n4_n6;
	6'b01010?: state = ND_n13_n15_n16_n22_n24_n3_n4_n6_n8;
	6'b010110: state = ND_n10_n13_n14_n15_n16_n17_n2_n20_n21_n22_n23_n24_n27_n3_n4_n6_n8_n9;
	6'b010111: state = ND_n13_n15_n16_n22_n24_n3_n4_n6_n8;
	6'b01100?: state = ND_n15_n16_n6;
	6'b011010: state = ND_n10_n15_n16_n17_n2_n6;
	6'b011011: state = ND_n15_n16_n6;
	6'b01110?: state = ND_n13_n15_n16_n3_n6_n8;
	6'b011110: state = ND_n10_n13_n14_n15_n16_n17_n2_n23_n3_n6_n8_n9;
	6'b011111: state = ND_n13_n15_n16_n3_n6_n8;
	6'b10000?: state = ND_n16_n24;
	6'b100010: state = ND_n16_n17_n21_n24;
	6'b100011: state = ND_n16_n24;
	6'b10010?: state = ND_n16_n24_n8;
	6'b100110: state = ND_n14_n16_n17_n21_n24_n8;
	6'b100111: state = ND_n16_n24_n8;
	6'b10100?: state = n16;
	6'b101010: state = ND_n16_n17;
	6'b101011: state = n16;
	6'b10110?: state = ND_n16_n8;
	6'b101110: state = ND_n14_n16_n17_n8;
	6'b101111: state = ND_n16_n8;
	6'b11000?: state = ND_n15_n16_n22_n24;
	6'b110010: state = ND_n10_n15_n16_n17_n21_n22_n24_n27;
	6'b110011: state = ND_n15_n16_n22_n24;
	6'b11010?: state = ND_n13_n15_n16_n22_n24_n8;
	6'b110110: state = ND_n10_n13_n14_n15_n16_n17_n21_n22_n24_n27_n8_n9;
	6'b110111: state = ND_n13_n15_n16_n22_n24_n8;
	6'b11100?: state = ND_n15_n16;
	6'b111010: state = ND_n10_n15_n16_n17;
	6'b111011: state = ND_n15_n16;
	6'b11110?: state = ND_n13_n15_n16_n8;
	6'b111110: state = ND_n10_n13_n14_n15_n16_n17_n8_n9;
	6'b111111: state = ND_n13_n15_n16_n8;
	endcase
    endcase
  end
endmodule
