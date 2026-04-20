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
