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

    // for compositional LTL model checking
    wire 		pc0L1,pc2L9,pc2L11,pc1L9,pc1L11,selReg1,selReg0,selReg2,pc0L9;
    assign 		pc0L1 = pc[0]==L1;
    assign 		pc0L9 = pc[0]==L9;
    
    assign 		pc2L9 = pc[2]==L9;
    assign 		pc2L11 = pc[2]==L11;

    assign 		pc1L9 = pc[1]==L9;
    assign 		pc1L11 = pc[1]==L11;

    assign 		selReg0 = selReg==0;
    assign 		selReg1 = selReg==1;
    assign 		selReg2 = selReg==2;
    
    Buechi Buechi(clock,pc0L1,pc2L9,pc2L11,pc1L9,pc1L11,selReg1,selReg0,selReg2,pc0L9,fair0,fair1,fair2,fair3,fair4,scc);

    
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

endmodule // bakery


//The monitor for LTL formula
typedef enum {n4,n6,n10,n12,n15,n22,n25,n30,n31,n33,n37,n43,n46,n52,n53,n54,n57,n58,n59,n63,n65,n69,n72,n77,n78,n81,n82,n83,n85,n86,n87,n89,n93,Trap} states;

module Buechi(clock,pc0L1,pc2L9,pc2L11,pc1L9,pc1L11,selReg1,selReg0,selReg2,pc0L9,fair0,fair1,fair2,fair3,fair4,scc);
  input clock,pc0L1,pc2L9,pc2L11,pc1L9,pc1L11,selReg1,selReg0,selReg2,pc0L9;
  output fair0,fair1,fair2,fair3,fair4,scc;
  states reg state;
  states wire ND_n12_n15_n30_n33_n4_n52_n78_n86;
  states wire ND_n10_n25_n30_n33_n4_n53_n59_n78;
  states wire ND_n12_n31_n33_n43_n63_n78_n86_n89;
  states wire ND_n10_n78;
  states wire ND_n15_n4_n43_n63_n65_n78_n83_n86;
  states wire ND_n46_n72;
  states wire ND_n30_n33_n4_n78;
  states wire ND_n10_n4_n53_n78;
  states wire ND_n4_n78;
  states wire ND_n43_n63_n78_n86;
  states wire ND_n10_n22_n63_n78;
  states wire ND_n10_n25_n33_n78;
  states wire ND_n10_n12_n25_n33_n57_n6_n78_n86;
  states wire ND_n10_n22_n25_n30_n31_n33_n4_n53_n54_n59_n63_n77_n78_n82_n83_n85;
  states wire ND_n33_n78;
  states wire ND_n10_n22_n4_n53_n63_n78_n83_n85;
  states wire ND_n10_n12_n15_n22_n25_n30_n31_n33_n37_n4_n43_n52_n53_n54_n57_n58_n59_n6_n63_n65_n69_n77_n78_n81_n82_n83_n85_n86_n87_n89_n93;
  states wire ND_n10_n12_n15_n25_n30_n33_n4_n52_n53_n57_n58_n59_n6_n69_n78_n86;
  states wire ND_n30_n31_n33_n4_n63_n77_n78_n83;
  states wire ND_n10_n15_n22_n4_n43_n53_n6_n63_n65_n69_n78_n83_n85_n86_n87_n93;
  states wire ND_n63_n78;
  states wire ND_n31_n33_n63_n78;
  states wire ND_n10_n15_n4_n53_n6_n69_n78_n86;
  states wire ND_n10_n6_n78_n86;
  states wire ND_n10_n22_n25_n31_n33_n54_n63_n78;
  states wire ND_n10_n22_n43_n6_n63_n78_n86_n87;
  states wire ND_n10_n12_n22_n25_n31_n33_n37_n43_n54_n57_n6_n63_n78_n86_n87_n89;
  states wire ND_n12_n15_n30_n31_n33_n4_n43_n52_n63_n65_n77_n78_n81_n83_n86_n89;
  states wire ND_n78_n86;
  states wire ND_n12_n33_n78_n86;
  states wire ND_n4_n63_n78_n83;
  states wire ND_n15_n4_n78_n86;
  assign ND_n12_n15_n30_n33_n4_n52_n78_n86 = $ND(n12,n15,n30,n33,n4,n52,n78,n86);
  assign ND_n10_n25_n30_n33_n4_n53_n59_n78 = $ND(n10,n25,n30,n33,n4,n53,n59,n78);
  assign ND_n12_n31_n33_n43_n63_n78_n86_n89 = $ND(n12,n31,n33,n43,n63,n78,n86,n89);
  assign ND_n10_n78 = $ND(n10,n78);
  assign ND_n15_n4_n43_n63_n65_n78_n83_n86 = $ND(n15,n4,n43,n63,n65,n78,n83,n86);
  assign ND_n46_n72 = $ND(n46,n72);
  assign ND_n30_n33_n4_n78 = $ND(n30,n33,n4,n78);
  assign ND_n10_n4_n53_n78 = $ND(n10,n4,n53,n78);
  assign ND_n4_n78 = $ND(n4,n78);
  assign ND_n43_n63_n78_n86 = $ND(n43,n63,n78,n86);
  assign ND_n10_n22_n63_n78 = $ND(n10,n22,n63,n78);
  assign ND_n10_n25_n33_n78 = $ND(n10,n25,n33,n78);
  assign ND_n10_n12_n25_n33_n57_n6_n78_n86 = $ND(n10,n12,n25,n33,n57,n6,n78,n86);
  assign ND_n10_n22_n25_n30_n31_n33_n4_n53_n54_n59_n63_n77_n78_n82_n83_n85 = $ND(n10,n22,n25,n30,n31,n33,n4,n53,n54,n59,n63,n77,n78,n82,n83,n85);
  assign ND_n33_n78 = $ND(n33,n78);
  assign ND_n10_n22_n4_n53_n63_n78_n83_n85 = $ND(n10,n22,n4,n53,n63,n78,n83,n85);
  assign ND_n10_n12_n15_n22_n25_n30_n31_n33_n37_n4_n43_n52_n53_n54_n57_n58_n59_n6_n63_n65_n69_n77_n78_n81_n82_n83_n85_n86_n87_n89_n93 = $ND(n10,n12,n15,n22,n25,n30,n31,n33,n37,n4,n43,n52,n53,n54,n57,n58,n59,n6,n63,n65,n69,n77,n78,n81,n82,n83,n85,n86,n87,n89,n93);
  assign ND_n10_n12_n15_n25_n30_n33_n4_n52_n53_n57_n58_n59_n6_n69_n78_n86 = $ND(n10,n12,n15,n25,n30,n33,n4,n52,n53,n57,n58,n59,n6,n69,n78,n86);
  assign ND_n30_n31_n33_n4_n63_n77_n78_n83 = $ND(n30,n31,n33,n4,n63,n77,n78,n83);
  assign ND_n10_n15_n22_n4_n43_n53_n6_n63_n65_n69_n78_n83_n85_n86_n87_n93 = $ND(n10,n15,n22,n4,n43,n53,n6,n63,n65,n69,n78,n83,n85,n86,n87,n93);
  assign ND_n63_n78 = $ND(n63,n78);
  assign ND_n31_n33_n63_n78 = $ND(n31,n33,n63,n78);
  assign ND_n10_n15_n4_n53_n6_n69_n78_n86 = $ND(n10,n15,n4,n53,n6,n69,n78,n86);
  assign ND_n10_n6_n78_n86 = $ND(n10,n6,n78,n86);
  assign ND_n10_n22_n25_n31_n33_n54_n63_n78 = $ND(n10,n22,n25,n31,n33,n54,n63,n78);
  assign ND_n10_n22_n43_n6_n63_n78_n86_n87 = $ND(n10,n22,n43,n6,n63,n78,n86,n87);
  assign ND_n10_n12_n22_n25_n31_n33_n37_n43_n54_n57_n6_n63_n78_n86_n87_n89 = $ND(n10,n12,n22,n25,n31,n33,n37,n43,n54,n57,n6,n63,n78,n86,n87,n89);
  assign ND_n12_n15_n30_n31_n33_n4_n43_n52_n63_n65_n77_n78_n81_n83_n86_n89 = $ND(n12,n15,n30,n31,n33,n4,n43,n52,n63,n65,n77,n78,n81,n83,n86,n89);
  assign ND_n78_n86 = $ND(n78,n86);
  assign ND_n12_n33_n78_n86 = $ND(n12,n33,n78,n86);
  assign ND_n4_n63_n78_n83 = $ND(n4,n63,n78,n83);
  assign ND_n15_n4_n78_n86 = $ND(n15,n4,n78,n86);
  assign fair0 = (state == n54) || (state == n63) || (state == n65) || (state == n77) || (state == n22) || (state == n31) || (state == n82) || (state == n81) || (state == n37) || (state == n83) || (state == n85) || (state == n87) || (state == n89) || (state == n43) || (state == n93);
  assign fair1 = (state == n54) || (state == n52) || (state == n57) || (state == n59) || (state == n58) || (state == n12) || (state == n77) || (state == n25) || (state == n30) || (state == n31) || (state == n82) || (state == n81) || (state == n33) || (state == n37) || (state == n89);
  assign fair2 = (state == n52) || (state == n57) || (state == n58) || (state == n6) || (state == n65) || (state == n12) || (state == n69) || (state == n15) || (state == n81) || (state == n37) || (state == n86) || (state == n87) || (state == n89) || (state == n43) || (state == n93);
  assign fair3 = (state == n54) || (state == n53) || (state == n57) || (state == n59) || (state == n58) || (state == n6) || (state == n10) || (state == n69) || (state == n22) || (state == n25) || (state == n82) || (state == n37) || (state == n85) || (state == n87) || (state == n93);
  assign fair4 = (state == n53) || (state == n52) || (state == n59) || (state == n58) || (state == n4) || (state == n65) || (state == n69) || (state == n15) || (state == n77) || (state == n30) || (state == n82) || (state == n81) || (state == n83) || (state == n85) || (state == n93);

    assign scc = (state !=n72) &&(state !=n46)&&(state !=Trap);
    
  initial state = n46;
  always @ (posedge clock) begin
    case (state)
      Trap:
	state = Trap;
      n72:
	case (pc0L1)
	1'b0: state = n78;
	1'b1: state = Trap;
	endcase
      n46:
	case ({pc0L1,pc0L9})
	2'b00: state = n46;
	2'b01: state = ND_n46_n72;
	2'b1?: state = n46;
	endcase
      n4,n6,n10,n12,n15,n22,n25,n30,n31,n33,n37,n43,n52,n53,n54,n57,n58,n59,n63,n65,n69,n77,n78,n81,n82,n83,n85,n86,n87,n89,n93:
	case ({pc0L1,pc1L11,pc1L9,pc2L11,pc2L9,selReg0,selReg1,selReg2})
	8'b00?0?000: state = n78;
	8'b00?0?001: state = ND_n63_n78;
	8'b00?0?010: state = ND_n10_n78;
	8'b00?0?011: state = ND_n10_n22_n63_n78;
	8'b00?0?100: state = ND_n33_n78;
	8'b00?0?101: state = ND_n31_n33_n63_n78;
	8'b00?0?110: state = ND_n10_n25_n33_n78;
	8'b00?0?111: state = ND_n10_n22_n25_n31_n33_n54_n63_n78;
	8'b00?10000: state = ND_n4_n78;
	8'b00?10001: state = ND_n4_n63_n78_n83;
	8'b00?10010: state = ND_n10_n4_n53_n78;
	8'b00?10011: state = ND_n10_n22_n4_n53_n63_n78_n83_n85;
	8'b00?10100: state = ND_n30_n33_n4_n78;
	8'b00?10101: state = ND_n30_n31_n33_n4_n63_n77_n78_n83;
	8'b00?10110: state = ND_n10_n25_n30_n33_n4_n53_n59_n78;
	8'b00?10111: state = ND_n10_n22_n25_n30_n31_n33_n4_n53_n54_n59_n63_n77_n78_n82_n83_n85;
	8'b00?11000: state = n78;
	8'b00?11001: state = ND_n63_n78;
	8'b00?11010: state = ND_n10_n78;
	8'b00?11011: state = ND_n10_n22_n63_n78;
	8'b00?11100: state = ND_n33_n78;
	8'b00?11101: state = ND_n31_n33_n63_n78;
	8'b00?11110: state = ND_n10_n25_n33_n78;
	8'b00?11111: state = ND_n10_n22_n25_n31_n33_n54_n63_n78;
	8'b0100?000: state = ND_n78_n86;
	8'b0100?001: state = ND_n43_n63_n78_n86;
	8'b0100?010: state = ND_n10_n6_n78_n86;
	8'b0100?011: state = ND_n10_n22_n43_n6_n63_n78_n86_n87;
	8'b0100?100: state = ND_n12_n33_n78_n86;
	8'b0100?101: state = ND_n12_n31_n33_n43_n63_n78_n86_n89;
	8'b0100?110: state = ND_n10_n12_n25_n33_n57_n6_n78_n86;
	8'b0100?111: state = ND_n10_n12_n22_n25_n31_n33_n37_n43_n54_n57_n6_n63_n78_n86_n87_n89;
	8'b01010000: state = ND_n15_n4_n78_n86;
	8'b01010001: state = ND_n15_n4_n43_n63_n65_n78_n83_n86;
	8'b01010010: state = ND_n10_n15_n4_n53_n6_n69_n78_n86;
	8'b01010011: state = ND_n10_n15_n22_n4_n43_n53_n6_n63_n65_n69_n78_n83_n85_n86_n87_n93;
	8'b01010100: state = ND_n12_n15_n30_n33_n4_n52_n78_n86;
	8'b01010101: state = ND_n12_n15_n30_n31_n33_n4_n43_n52_n63_n65_n77_n78_n81_n83_n86_n89;
	8'b01010110: state = ND_n10_n12_n15_n25_n30_n33_n4_n52_n53_n57_n58_n59_n6_n69_n78_n86;
	8'b01010111: state = ND_n10_n12_n15_n22_n25_n30_n31_n33_n37_n4_n43_n52_n53_n54_n57_n58_n59_n6_n63_n65_n69_n77_n78_n81_n82_n83_n85_n86_n87_n89_n93;
	8'b01011000: state = ND_n78_n86;
	8'b01011001: state = ND_n43_n63_n78_n86;
	8'b01011010: state = ND_n10_n6_n78_n86;
	8'b01011011: state = ND_n10_n22_n43_n6_n63_n78_n86_n87;
	8'b01011100: state = ND_n12_n33_n78_n86;
	8'b01011101: state = ND_n12_n31_n33_n43_n63_n78_n86_n89;
	8'b01011110: state = ND_n10_n12_n25_n33_n57_n6_n78_n86;
	8'b01011111: state = ND_n10_n12_n22_n25_n31_n33_n37_n43_n54_n57_n6_n63_n78_n86_n87_n89;
	8'b0110?000: state = n78;
	8'b0110?001: state = ND_n63_n78;
	8'b0110?010: state = ND_n10_n78;
	8'b0110?011: state = ND_n10_n22_n63_n78;
	8'b0110?100: state = ND_n33_n78;
	8'b0110?101: state = ND_n31_n33_n63_n78;
	8'b0110?110: state = ND_n10_n25_n33_n78;
	8'b0110?111: state = ND_n10_n22_n25_n31_n33_n54_n63_n78;
	8'b01110000: state = ND_n4_n78;
	8'b01110001: state = ND_n4_n63_n78_n83;
	8'b01110010: state = ND_n10_n4_n53_n78;
	8'b01110011: state = ND_n10_n22_n4_n53_n63_n78_n83_n85;
	8'b01110100: state = ND_n30_n33_n4_n78;
	8'b01110101: state = ND_n30_n31_n33_n4_n63_n77_n78_n83;
	8'b01110110: state = ND_n10_n25_n30_n33_n4_n53_n59_n78;
	8'b01110111: state = ND_n10_n22_n25_n30_n31_n33_n4_n53_n54_n59_n63_n77_n78_n82_n83_n85;
	8'b01111000: state = n78;
	8'b01111001: state = ND_n63_n78;
	8'b01111010: state = ND_n10_n78;
	8'b01111011: state = ND_n10_n22_n63_n78;
	8'b01111100: state = ND_n33_n78;
	8'b01111101: state = ND_n31_n33_n63_n78;
	8'b01111110: state = ND_n10_n25_n33_n78;
	8'b01111111: state = ND_n10_n22_n25_n31_n33_n54_n63_n78;
	8'b1???????: state = Trap;
	endcase
    endcase
  end
endmodule
