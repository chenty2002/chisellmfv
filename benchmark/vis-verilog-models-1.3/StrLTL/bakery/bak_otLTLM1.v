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


    wire 		p,q,r1,s1;

    assign p = select == 0;
    assign q = select == 1 && pause == 0;
    assign r1 = pc[0]==L4 && pc[1]==L9;
    assign s1 = pc[0]==L9;
    
    Buechi Buechi(clock,s1,q,p,r1,fair0,fair1,scc,scc_entries);

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


//The monitor for LTL formula
//
// GF(sel=0)^GF(sel=1 ^ pause =0)->G((pc[0])=L4->F(pc[0]=L9))
//
// Buechi automaton: 5s/ strong/ 2 fairsets/ 3s in fair SCC
//
//             mem   livenodes  time(sec)    EX    iterations
// normal le   421M  11.8M      368.5        2316  15
// early  le   213M  4.7M       122.7        117   15
// early scc   146M  0.02M       23.6        117   15
//
typedef enum {n2,n3,n7,n9,n10,Trap} states;

module Buechi(clock,s1,q,p,r1,fair0,fair1,scc,scc_entries);
  input clock,s1,q,p,r1;
  output fair0,fair1,scc,scc_entries;
  states reg state;
  states wire ND_n2_n9;
  states wire ND_n10_n3;
  states wire ND_n7_n9;
  states wire ND_n2_n7_n9;
  assign ND_n2_n9 = $ND(n2,n9);
  assign ND_n10_n3 = $ND(n10,n3);
  assign ND_n7_n9 = $ND(n7,n9);
  assign ND_n2_n7_n9 = $ND(n2,n7,n9);
  assign fair0 = (state == n2);
  assign fair1 = (state == n7);
    assign scc = state == n2 || state == n7 || state == n9;
assign scc_entries = state== n9;

  initial state = n3;
  always @ (posedge clock) begin
    case (state)
      n3:
	case ({r1,s1})
	2'b0?: state = n3;
	2'b10: state = ND_n10_n3;
	2'b11: state = n3;
	endcase
      n10:
	case (s1)
	1'b0: state = n9;
	1'b1: state = Trap;
	endcase
      Trap:
	state = Trap;
      n2,n7,n9:
	case ({p,q,s1})
	3'b000: state = n9;
	3'b??1: state = Trap;
	3'b010: state = ND_n2_n9;
	3'b100: state = ND_n7_n9;
	3'b110: state = ND_n2_n7_n9;
	endcase
    endcase
  end
endmodule
