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
    parameter		SELMSB = 1;
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

    wire 		p,q,r1,s1,r0,s0;

    assign p = select == 0;
    assign q = select == 1 && pause == 0;
    assign r1 = pc[0]==L4 && pc[1]==L9;
    assign s1 = pc[0]==L9;
    assign r0 = pc[0]==L6;
    assign s0 = pc[0]==L5;
    
    Buechi Buechi(clock,r0,q,s1,p,s0,r1,fair0,fair1,fair2,scc,scc_entries);

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

endmodule //atomic_bakery.v bakery



//#     if '0' is selected infinitely often, '1' is also selected
//#  infinitely often, then, once '0' is waiting, '0' will fininally be
//#  served .
//
//"!(G(F(p=1))*G(F(q=1))->G((r1=1)->F(s1=1)))"
//
//#  p= sel==0
//#  q= sel==1 && pause==0
//#  r1=  pc[0]==L4
//#  s1=  pc[0]==L9
//#  r0=  pc[0]==L6
//#  s0=  pc[0]==L5
//
//#
//#  If we now know it is true that, "once entering L6, that client can
//#  finally reach L5,
//#  We use this lemma to prove the above property
//
//"!(G(F(p=1))*G(F(q=1))*G((r0=1)->F(s0=1))->G((r1=1)->F(s1=1)))"
//
//The monitor for LTL formula
////
// Buechi automaton: 7s/ strong/ 2 fairsets/ 3s in fair SCC
// 
//            mem(M) Bdd_n(M)   time(sec)    EX    iterations
// passed!
// normal le   640   20         2041         1089  5 
// early  le   358   5.6        156          74    5
//
typedef enum {Init,n2,n6,n8,n9,n12,n15,n16,n17,n20,n21,n23,n24,n25,n27,n29,n31,n32,n35,Trap} states;

module Buechi(clock,r0,q,s1,p,s0,r1,fair0,fair1,fair2,scc, scc_entries);
  input clock,r0,q,s1,p,s0,r1;
  output fair0,fair1,fair2,scc, scc_entries;
  states reg state;
  states wire ND_n6_n9;
  states wire ND_n27_n6;
  states wire ND_n15_n31_n6_n9;
  states wire ND_n17_n20_n6_n9;
  states wire ND_n29_n32;
  states wire ND_n12_n16_n21_n23_n29_n32;
  states wire ND_n2_n27_n6_n9;
  states wire ND_n15_n24_n27_n6;
  states wire ND_n17_n2_n20_n27_n6_n9;
  states wire ND_n15_n20_n24_n27_n6_n8;
  states wire ND_n16_n23_n29_n32;
  states wire ND_n20_n27_n6;
  states wire ND_n12_n21;
  states wire ND_n12_n29_n32;
  states wire ND_n15_n17_n20_n25_n31_n6_n8_n9;
  states wire ND_n15_n2_n24_n27_n31_n35_n6_n9;
  states wire ND_n12_n32;
  states wire ND_n20_n6;
  states wire ND_n12_n16_n21_n32;
  states wire ND_n16_n32;
  states wire ND_n15_n6;
  states wire ND_n15_n20_n6_n8;
  states wire ND_n15_n17_n2_n20_n24_n25_n27_n31_n35_n6_n8_n9;
  assign ND_n6_n9 = $ND(n6,n9);
  assign ND_n27_n6 = $ND(n27,n6);
  assign ND_n15_n31_n6_n9 = $ND(n15,n31,n6,n9);
  assign ND_n17_n20_n6_n9 = $ND(n17,n20,n6,n9);
  assign ND_n29_n32 = $ND(n29,n32);
  assign ND_n12_n16_n21_n23_n29_n32 = $ND(n12,n16,n21,n23,n29,n32);
  assign ND_n2_n27_n6_n9 = $ND(n2,n27,n6,n9);
  assign ND_n15_n24_n27_n6 = $ND(n15,n24,n27,n6);
  assign ND_n17_n2_n20_n27_n6_n9 = $ND(n17,n2,n20,n27,n6,n9);
  assign ND_n15_n20_n24_n27_n6_n8 = $ND(n15,n20,n24,n27,n6,n8);
  assign ND_n16_n23_n29_n32 = $ND(n16,n23,n29,n32);
  assign ND_n20_n27_n6 = $ND(n20,n27,n6);
  assign ND_n12_n21 = $ND(n12,n21);
  assign ND_n12_n29_n32 = $ND(n12,n29,n32);
  assign ND_n15_n17_n20_n25_n31_n6_n8_n9 = $ND(n15,n17,n20,n25,n31,n6,n8,n9);
  assign ND_n15_n2_n24_n27_n31_n35_n6_n9 = $ND(n15,n2,n24,n27,n31,n35,n6,n9);
  assign ND_n12_n32 = $ND(n12,n32);
  assign ND_n20_n6 = $ND(n20,n6);
  assign ND_n12_n16_n21_n32 = $ND(n12,n16,n21,n32);
  assign ND_n16_n32 = $ND(n16,n32);
  assign ND_n15_n6 = $ND(n15,n6);
  assign ND_n15_n20_n6_n8 = $ND(n15,n20,n6,n8);
  assign ND_n15_n17_n2_n20_n24_n25_n27_n31_n35_n6_n8_n9 = $ND(n15,n17,n2,n20,n24,n25,n27,n31,n35,n6,n8,n9);
    
  assign fair0 = (state == n27) || (state == n2) || (state == n8) || (state == n17) || (state == n20) || (state == n35) || (state == n25) || (state == n24);
  assign fair1 = (state == n2) || (state == n17) || (state == n9) || (state == n35) || (state == n31) || (state == n25);
  assign fair2 = (state == n15) || (state == n8) || (state == n35) || (state == n31) || (state == n25) || (state == n24);

  assign scc = (state == n20) || (state==n2) || (state==n31) || (state==n24) || (state==n6) || (state==n15) || (state==n25) || (state==n35) || (state==n8) || (state==n17) || (state==n9) || (state==n27);
  assign scc_entries = state == n27|| state == n20|| state == n6;
   
  initial state = Init;
  always @ (posedge clock) begin
    case (state)
      Init:
	case ({r0,r1,s0,s1})
	4'b000?: state = ND_n12_n32;
	4'b001?: state = ND_n12_n29_n32;
	4'b0100: state = ND_n12_n16_n21_n32;
	4'b0101: state = ND_n12_n32;
	4'b0110: state = ND_n12_n16_n21_n23_n29_n32;
	4'b0111: state = ND_n12_n29_n32;
	4'b100?: state = n32;
	4'b101?: state = ND_n29_n32;
	4'b1100: state = ND_n16_n32;
	4'b1101: state = n32;
	4'b1110: state = ND_n16_n23_n29_n32;
	4'b1111: state = ND_n29_n32;
	endcase
      n21,n23:
	case ({r0,s1})
	2'b00: state = n27;
	2'b01: state = Trap;
	2'b1?: state = Trap;
	endcase
      Trap:
	state = Trap;
      n32:
	case ({r1,s0,s1})
	3'b00?: state = n32;
	3'b01?: state = ND_n29_n32;
	3'b100: state = ND_n16_n32;
	3'b101: state = n32;
	3'b110: state = ND_n16_n23_n29_n32;
	3'b111: state = ND_n29_n32;
	endcase
      n12,n29:
	case ({r0,r1,s1})
	3'b00?: state = n12;
	3'b010: state = ND_n12_n21;
	3'b011: state = n12;
	3'b1??: state = Trap;
	endcase
      n16:
	case ({s0,s1})
	2'b00: state = n6;
	2'b?1: state = Trap;
	2'b10: state = ND_n20_n6;
	endcase
      n2,n8,n17,n20,n24,n25,n27,n35:
	case ({p,q,r0,s0,s1})
	5'b00000: state = ND_n27_n6;
	5'b????1: state = Trap;
	5'b00010: state = ND_n20_n27_n6;
	5'b00100: state = n6;
	5'b00110: state = ND_n20_n6;
	5'b01000: state = ND_n2_n27_n6_n9;
	5'b01010: state = ND_n17_n2_n20_n27_n6_n9;
	5'b01100: state = ND_n6_n9;
	5'b01110: state = ND_n17_n20_n6_n9;
	5'b10000: state = ND_n15_n24_n27_n6;
	5'b10010: state = ND_n15_n20_n24_n27_n6_n8;
	5'b10100: state = ND_n15_n6;
	5'b10110: state = ND_n15_n20_n6_n8;
	5'b11000: state = ND_n15_n2_n24_n27_n31_n35_n6_n9;
	5'b11010: state = ND_n15_n17_n2_n20_n24_n25_n27_n31_n35_n6_n8_n9;
	5'b11100: state = ND_n15_n31_n6_n9;
	5'b11110: state = ND_n15_n17_n20_n25_n31_n6_n8_n9;
	endcase
      n6,n9,n15,n31:
	case ({p,q,s0,s1})
	4'b0000: state = n6;
	4'b???1: state = Trap;
	4'b0010: state = ND_n20_n6;
	4'b0100: state = ND_n6_n9;
	4'b0110: state = ND_n17_n20_n6_n9;
	4'b1000: state = ND_n15_n6;
	4'b1010: state = ND_n15_n20_n6_n8;
	4'b1100: state = ND_n15_n31_n6_n9;
	4'b1110: state = ND_n15_n17_n20_n25_n31_n6_n8_n9;
	endcase
    endcase
  end
endmodule
