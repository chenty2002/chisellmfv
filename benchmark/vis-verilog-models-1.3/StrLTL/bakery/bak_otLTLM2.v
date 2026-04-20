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
// clearBit.
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
    reg [HIPROC:0] 	defSel, defK;
    reg 		defSelK, defKSel;
    integer 		i;

    wire 		p,q,r1,s1, r0;

    assign p = select == 0;
    assign q = select == 1 && pause == 0;
    assign r1 = pc[0]==L4 && pc[1]==L9;
    assign s1 = pc[0]==L9;
    assign r0 = pc[0]==L6;
    
    Buechi Buechi(clock,r0,s1,r1,q,p,fair0,fair1,scc);


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
    // set to 0.
    // WARNING: change if HIPROC is modified.
    function [HIPROC:0] clearBit;
	input [HIPROC:0] in;
	input [SELMSB:0] index;
	begin: _clearBit
	    clearBit = in;
	    if (index == 0)
	      clearBit[0] = 0;
	    else if (index == 1)
	      clearBit[1] = 0;
	    else if (index == 2)
	      clearBit[2] = 0;
	end
    endfunction // clearBit


    task process;
	input [SELMSB:0] sel;
	begin: _process
	    case (pc[sel])
	      L1: begin choosing[sel] = 1; pc[sel] = L2a; end
	      L2a: begin j[sel] = 0; pc[sel] = L2b; end
	      L2b: if (j[sel] <= HIPROC) pc[sel] = L2c; else pc[sel] = L3;
	      L2c: begin
		  k = j[sel];
		  defSel = defer[sel];
		  defer[sel] = {ticket[k], defSel[HIPROC:1]};
		  j[sel] = k + 1;
		  pc[sel] = L2b;
	      end
	      L3: begin ticket[sel] = 1; choosing[sel] = 0; pc[sel] = L4; end
	      // Loop over all processes to check ticket.
	      L4: begin j[sel] = 0; pc[sel] = L5; end
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
		  defSel = defer[sel];
		  defK = defer[k];
		  defSelK = extract(defSel,k);
		  defKSel = extract(defK,sel);
		  if (ticket[k] && (defSelK || (!defKSel && k < sel)))
		    pc[sel] = L7;
		  else
		    pc[sel] = L8;
	      end
	      L8: begin j[sel] = j[sel] + 1; pc[sel] = L5; end
	      // Enter critical section.
	      L9: begin if (pause) pc[sel] = L9; else pc[sel] = L10a; end
	      // Leave critical section.
	      L10a: begin ticket[sel] = 0; j[sel] = 0; pc[sel] = L10b; end
	      L10b: if (j[sel] <= HIPROC) pc[sel] = L10c; else pc[sel] = L11;
	      L10c: begin
		  k = j[sel];
		  defK = defer[k];
		  defer[k] = clearBit(defK,sel);
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
	    choosing[i] = 0;
	    pc[i] = L1;
	    j[i] = 0;
	end
	k = 0;
	selReg = 0;
	defSel = 0;
	defK = 0;
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

endmodule // atomic_bug.v bakery


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
//
//#
//#  If we now know it is true that, "once entering L6, that client can
//#  finally reach L9,
//#  We use this lemma to prove the above property
//
//"!(G(F(p=1))*G(F(q=1))*G((r0=1)->F(s1=1))->G((r1=1)->F(s1=1)))"
//
//The monitor for LTL formula
////
// Buechi automaton: 7s/ strong/ 2 fairsets/ 3s in fair SCC
// lang is not empty
//            mem(M) Bdd_n(M)   time(sec)   EX  iterations
//
// normal le   442M   12.5M     295.8     334   3
// early  le   107M   2.9M       73.6       6   1
//
typedef enum {Init,n3,n5,n6,n13,n16,n19,n20,Trap} states;

module Buechi(clock,r0,s1,r1,q,p,fair0,fair1,scc);
  input clock,r0,s1,r1,q,p;
  output fair0,fair1,scc;
  states reg state;
  states wire ND_n19_n5;
  states wire ND_n3_n6;
  states wire ND_n16_n3;
  states wire ND_n13_n19_n20;
  states wire ND_n16_n3_n6;
  states wire ND_n13_n19;
  states wire ND_n13_n20;
  states wire ND_n13_n19_n5;
  assign ND_n19_n5 = $ND(n19,n5);
  assign ND_n3_n6 = $ND(n3,n6);
  assign ND_n16_n3 = $ND(n16,n3);
  assign ND_n13_n19_n20 = $ND(n13,n19,n20);
  assign ND_n16_n3_n6 = $ND(n16,n3,n6);
  assign ND_n13_n19 = $ND(n13,n19);
  assign ND_n13_n20 = $ND(n13,n20);
  assign ND_n13_n19_n5 = $ND(n13,n19,n5);
  assign fair0 = (state == n16);
  assign fair1 = (state == n6);
    assign scc = state == n6 || state == n16 || state == n3;
    
  initial state = Init;
  always @ (posedge clock) begin
    case (state)
      n19,n20:
	case ({r0,r1,s1})
	3'b00?: state = n19;
	3'b010: state = ND_n19_n5;
	3'b011: state = n19;
	3'b1??: state = Trap;
	endcase
      Trap:
	state = Trap;
      n5:
	case ({r0,s1})
	2'b00: state = n3;
	2'b01: state = Trap;
	2'b1?: state = Trap;
	endcase
      Init:
	case ({r0,r1,s1})
	3'b000: state = ND_n13_n19;
	3'b0?1: state = ND_n13_n19_n20;
	3'b010: state = ND_n13_n19_n5;
	3'b1?0: state = n13;
	3'b1?1: state = ND_n13_n20;
	endcase
      n13:
	case (s1)
	1'b0: state = n13;
	1'b1: state = ND_n13_n20;
	endcase
      n3,n6,n16:
	case ({p,q,r0,s1})
	4'b0000: state = n3;
	4'b??01: state = Trap;
	4'b??1?: state = Trap;
	4'b0100: state = ND_n3_n6;
	4'b1000: state = ND_n16_n3;
	4'b1100: state = ND_n16_n3_n6;
	endcase
    endcase
  end
endmodule
