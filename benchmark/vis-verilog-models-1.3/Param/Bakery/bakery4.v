// The bakery algorithm for mutual exclusion simulates a bakery in
// which customers (processes) take a numbered ticket when they enter the
// store, and then wait for their number to be called.
//
// This parameterized implementation emulates interleaving
// of the system process by a nondeterministic global selector.
// Ties among processes with the same ticket number are broken according
// to a fixed priority scheme:  a process with lower index has precedence
// over one with higher index.
//
// The tickets are finite; hence, mutual exclusion is not guaranteed by
// this implementation.

// Type of program counter locations.
typedef enum {L1, L2, L3, L4, L5, L6, L7, L8, L9, L10, L11} loc;

module bakery(clock,select,pause);
    // MSB of the tickets.
    parameter		TKMSB = 4;
    // Highest process index.  Indices start at 0.
    parameter 		HIPROC = 1;
    // MSB of the process index variables.  Enough bits should be given
    // to represent HIPROC+1
    parameter		SELMSB = 1;
    input 		clock;
    // Nondeterministic selection of enabled process.
    input [SELMSB:0] 	select;
    // Nondeterministic choice between pause and progress.
    input 		pause;

    // The ticket numbers of the processes.
    reg [TKMSB:0]	ticket[0:HIPROC];
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
    // The latched values of the process  variables.
    // These variables appear in the fairness constraints.
    reg [SELMSB:0] 	selReg;
    // Register used to hold j[sel].  It could be replaced by a wire,
    // but the BDDs would suffer.
    reg [SELMSB:0] 	k;
    integer 		i;

    task process;
	input [SELMSB:0] sel;
	begin: _process
	    case (pc[sel])
	      L1: begin choosing[sel] = 1; pc[sel] = L2; end
	      L2: begin
		  for (i = 0; i <= HIPROC; i = i + 1) begin
		      if (ticket[i] > ticket[sel]) ticket[sel] = ticket[i];
		  end
		  ticket[sel] = ticket[sel] + 1;
		  pc[sel] = L3;
	      end
	      L3: begin choosing[sel] = 0; pc[sel] = L4; end
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
	      // Wait while process j[sel] has a non-zero ticket with a lower
	      // number, or it has the same number and higher priority.
	      L7: begin
		  k = j[sel];
		  if ((ticket[k] != 0) &&
		      (ticket[k] < ticket[sel] ||
		       (ticket[k] == ticket[sel] && k < sel)))
		    pc[sel] = L7;
		  else
		    pc[sel] = L8;
	      end
	      L8: begin j[sel] = j[sel] + 1; pc[sel] = L5; end
	      // Enter critical section.
	      L9: begin if (pause) pc[sel] = L9; else pc[sel] = L10; end
	      // Leave critical section.
	      L10: begin ticket[sel] = 0; pc[sel] = L11; end
	      L11: begin if (pause) pc[sel] = L11; else pc[sel] = L1; end
	    endcase
	end
    endtask // process

    initial begin
	for (i = 0; i <= HIPROC; i = i + 1) begin
	    ticket[i] = 0;
	    choosing[i] = 0;
	    pc[i] = L1;
	    j[i] = 0;
	end
	k = 0;
	selReg = 0;
    end

    always @ (posedge clock) begin
	if (select > HIPROC)
	  selReg = 0;
	else
	  selReg = select;
	process(selReg);
    end

endmodule // bakery
