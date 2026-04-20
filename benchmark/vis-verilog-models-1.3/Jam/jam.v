// This model plays "traffic jam."  Two groups of players, the right-facing
// players, an the left-facing players, have to swap places.  There are 2n
// players and 2n+1 slots.  The a player can move to the empty slot if it is
// adjacent to his/her current slot, or if the only intervening slot is
// occupied by a player facing in the opposite direction.  The latter kind
// of move is a "jump."  A player can only jump forward.  (E.g., a
// right-facing player can only jump to the right.)

// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {EMPTY, LEFT, RIGHT} Cell;

module jam(clock, move, done);
    input       clock;
    input [2:0] move;
    output 	done;

    Cell reg    slots[6:0];
    wire [2:0]  empty, mp1, mp2, mm1, mm2;
    wire 	valid;

    initial begin
	slots[0] = RIGHT;
	slots[1] = RIGHT;
	slots[2] = RIGHT;
	slots[3] = EMPTY;
	slots[4] = LEFT;
	slots[5] = LEFT;
	slots[6] = LEFT;
    end

    always @ (posedge clock) begin
	if (valid) begin
	    slots[empty] = slots[move];
	    slots[move] = EMPTY;
	end
    end

    assign done = (slots[0] == LEFT)  && (slots[1] == LEFT)  &&
		  (slots[2] == LEFT)  && (slots[3] == EMPTY) &&
		  (slots[4] == RIGHT) && (slots[5] == RIGHT) &&
		  (slots[6] == RIGHT);

    assign empty = (slots[0] == EMPTY) ? 0 :
		   (slots[1] == EMPTY) ? 1 :
		   (slots[2] == EMPTY) ? 2 :
		   (slots[3] == EMPTY) ? 3 :
		   (slots[4] == EMPTY) ? 4 :
		   (slots[5] == EMPTY) ? 5 :
		   (slots[6] == EMPTY) ? 6 :
		   7;

    assign valid = ((move < 6) && (mp1 == empty))     // slide right
      || ((move < 7) && (move > 0) && (mm1 == empty)) // slide left
      || ((move < 5) && (slots[move] == RIGHT) &&
	  (slots[mp1] == LEFT) && (mp2 == empty))     // jump right
      || ((move < 7) && (move > 1) && (slots[move] == LEFT) &&
	  (slots[mm1] == RIGHT) && (mm2 == empty));   // jump left

    assign mp1 = move+1;
    assign mp2 = mp1+1;
    assign mm1 = move-1;
    assign mm2 = mm1-1;

endmodule // jam
