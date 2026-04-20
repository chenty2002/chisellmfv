// Improved model for the Tower of Hanoi puzzle.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {A, B, C} Peg;
module hanoi(clock,from,to,done);
    input clock;
    input from, to;
    output done;

    Peg wire from, to;

    Peg reg disc[0:19];

    wire    legal;
    // These should have enough bits to represent the number of discs
    wire [4:0] sizeFrom, sizeTo;

    integer 	  i;

    initial
      for (i = 0; i < 20; i = i + 1)
	disc[i] = A;

    // If sizeFrom equals the number of discs, then the peg has no discs
    assign sizeFrom =
	   disc[0]==from ? 0 :
	   disc[1]==from ? 1 :
	   disc[2]==from ? 2 :
	   disc[3]==from ? 3 :
	   disc[4]==from ? 4 :
	   disc[5]==from ? 5 :
	   disc[6]==from ? 6 :
	   disc[7]==from ? 7 :
	   disc[8]==from ? 8 :
	   disc[9]==from ? 9 :
	   disc[10]==from ? 10 :
	   disc[11]==from ? 11 :
	   disc[12]==from ? 12 :
	   disc[13]==from ? 13 :
	   disc[14]==from ? 14 :
	   disc[15]==from ? 15 :
	   disc[16]==from ? 16 :
	   disc[17]==from ? 17 :
	   disc[18]==from ? 18 :
	   disc[19]==from ? 19 :
	   20;

    assign sizeTo =
	   disc[0]==to ? 0 :
	   disc[1]==to ? 1 :
	   disc[2]==to ? 2 :
	   disc[3]==to ? 3 :
	   disc[4]==to ? 4 :
	   disc[5]==to ? 5 :
	   disc[6]==to ? 6 :
	   disc[7]==to ? 7 :
	   disc[8]==to ? 8 :
	   disc[9]==to ? 9 :
	   disc[10]==to ? 10 :
	   disc[11]==to ? 11 :
	   disc[12]==to ? 12 :
	   disc[13]==to ? 13 :
	   disc[14]==to ? 14 :
	   disc[15]==to ? 15 :
	   disc[16]==to ? 16 :
	   disc[17]==to ? 17 :
	   disc[18]==to ? 18 :
	   disc[19]==to ? 19 :
	   20;
    
    assign legal = (sizeFrom < 20) && (sizeFrom < sizeTo);

    always @ (posedge clock) begin
	if (legal)
	  disc[sizeFrom] = to;
    end

    assign done =
		 disc[0]==B &
		 disc[1]==B &
		 disc[2]==B &
		 disc[3]==B &
		 disc[4]==B &
		 disc[5]==B &
		 disc[6]==B &
		 disc[7]==B &
		 disc[8]==B &
		 disc[9]==B &
		 disc[10]==B &
		 disc[11]==B &
		 disc[12]==B &
		 disc[13]==B &
		 disc[14]==B &
		 disc[15]==B &
		 disc[16]==B &
		 disc[17]==B &
		 disc[18]==B &
		 disc[19]==B;
    
endmodule // hanoi

	