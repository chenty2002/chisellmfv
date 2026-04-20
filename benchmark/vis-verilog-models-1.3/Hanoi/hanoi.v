// Model of the "towers of Hanoi" puzzle.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {A, B, C} Peg;
module hanoi(clock,from,to,done);
    input clock;
    input from, to;
    output done;

    Peg wire from, to;

    parameter N = 5; //number of discs
    parameter MSB = 2;

    reg [MSB:0] pegA [0:N-1];
    reg [MSB:0] pegB [0:N-1];
    reg [MSB:0] pegC [0:N-1];

    reg [MSB:0] nA, nB, nC;

    initial begin
	for (i = 0; i < N; i = i + 1) begin
	    pegA[i] = N - i;
	    pegB[i] = 0;
	    pegC[i] = 0;
	end
	nA = N;
	nB = 0;
	nC = 0;
    end

    always @ (posedge clock) begin
	case (from)
	  A: if (nA > 0) begin
	      case (to)
		B: if (nB == 0 || pegA[nA-1] < pegB[nB-1]) begin
		    pegB[nB] = pegA[nA-1];
		    nB = nB + 1;
		    nA = nA - 1;
		end
		C: if (nC == 0 || pegA[nA-1] < pegC[nC-1]) begin
		    pegC[nC] = pegA[nA-1];
		    nC = nC + 1;
		    nA = nA - 1;
		end
	      endcase
	  end
	  B: if (nB > 0) begin
	      case (to)
		A: if (nA == 0 || pegB[nB-1] < pegA[nA-1]) begin
		    pegA[nA] = pegB[nB-1];
		    nA = nA + 1;
		    nB = nB - 1;
		end
		C: if (nC == 0 || pegB[nB-1] < pegC[nC-1]) begin
		    pegC[nC] = pegB[nB-1];
		    nC = nC + 1;
		    nB = nB - 1;
		end
	      endcase
	  end
	  C: if (nC > 0) begin
	      case (to)
		A: if (nA == 0 || pegC[nC-1] < pegA[nA-1]) begin
		    pegA[nA] = pegC[nC-1];
		    nA = nA + 1;
		    nC = nC - 1;
		end
		B: if (nB == 0 || pegC[nC-1] < pegB[nB-1]) begin
		    pegB[nB] = pegC[nC-1];
		    nB = nB + 1;
		    nC = nC - 1;
		end
	      endcase
	  end
	endcase
    end

    assign done = nB == N;

endmodule // hanoi
