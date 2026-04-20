// Model of the game of Nim.
//   
// There are NCOL piles of counters.  At each turn one player removes one
// or more counters from one pile.  The winner is the one that removes the
// last counter.  Familiarity with the Sprague-Grundy theory of impartial
// games will help in the understanding of this model.
//
// The maximum number of counters on each pile is controlled by LOGCNT.
// During the start-up phase, each pile is initialized with a number of
// counters between 0 and 2**LOGCNT - 1.
//
// The two players are 0 and 1.  Player 0 is the environment and goes first.
// The model plays the other side perfectly when starting from a winning
// position; otherwise, it removes just one counter from one pile hoping
// that the opponent will later make a mistake.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

module nim(clock,col,num,win,lose,winning);
    parameter        LOGCOL = 2;	// log_2 of NCOL
    parameter 	     LOGCNT = 4;	// number of bits for each pile
    parameter 	     NCOL = 1 << LOGCOL;// number of piles
    parameter 	     MSBCOL = LOGCOL-1;
    parameter 	     MSBCNT = LOGCNT-1;
    input 	     clock;		// each move takes one clock tick
    input [MSBCOL:0] col;		// pile from which to take counters
    input [MSBCNT:0] num;		// number of counters to remove
    output 	     win, lose;		// player 0 wins/loses
    output 	     winning;		// the current position is winning

    // Each pile is a register holding the number of counters.
    reg [MSBCNT:0]   pile[0:NCOL-1];
    // Down-counter for initialization.  When it reaches 0, the piles
    // have been initialized.
    reg [LOGCOL+1:0] load;
    // A valid move removes at least one counter from an existing pile
    // and no more counters than there are left in the pile.
    reg 	     valid;
    // 
    reg 	     win, lose;
    // The value of a position is the XOR of all the pile registers.
    // A value of 0 indicates a losing position.
    reg [MSBCNT:0]   value;
    // turn == 0 (1) means that the environment (system) moves.
    reg 	     turn;
    // Working registers.
    reg [MSBCNT:0]   temp;
    reg 	     found;
    integer 	     i;

    initial begin
	pile[col] = 0;
	load = NCOL;
	valid = 0;
	win = 0;
	lose = 0;
	value = 0;
	found = 0;
	turn = 0;
	temp = 0;
    end

    assign winning = load == 0 && value != 0;

    always @ (posedge clock) begin
	if (load > 0) begin
	    load = load - 1;
	    pile[load] = num;
	    value = value ^ num;
	end else if (turn == 0) begin
	    // It's the environment's turn to play.  First check for a loss.
	    lose = ~win;
	    for (i = 0; i < NCOL; i = i + 1)
	      lose = lose && pile[i] == 0;
	    // If the move is valid, effect it and pass turn.
	    valid = col <= NCOL-1 && num > 0 && num <= pile[col];
	    if (valid) begin
		pile[col] = pile[col] - num;
		turn = 1;
	    end
	end else begin
	    // It's the system's turn.  Check for a win (of the environment).
	    win = ~lose;
	    for (i = 0; i < NCOL; i = i + 1)
	      win = win && pile[i] == 0;
	    if (win == 0) begin
		found = 0;
		if (value == 0) begin
		    // Losing position: Remove one counter from the
		    // first non-empty pile.
		    for (i = 0; i < NCOL; i = i + 1) begin
			if (found == 0) begin
			    if (pile[i] > 0) begin
				found = 1;
				pile[i] = pile[i] - 1;
			    end
			end
		    end
		end else begin
		    // Winning position: remove counters from the first pile
		    // that has enough counters.
		    for (i = 0; i < NCOL; i = i + 1) begin
			if (found == 0) begin
			    temp = value ^ pile[i];
			    if (temp < pile[i]) begin
				found = 1;
				pile[i] = temp;
			    end
			end
		    end
		end
	    end
	    turn = 0;
	end
	// Update value.
	value = 0;
	for (i = 0; i < NCOL; i = i + 1)
	  value = value ^ pile[i];
    end

endmodule // nim
