// This model of a tic-tac-toe player accepts moves on input "imove," and,
// if they are legal, executes them.  A move is legal if it specifies an empty
// cell of the game board.  There are two players, X and O.  Initially, X
// moves.Every time a legal move is executed, the turn passes to the other
// player.  Once the game is finished, either with a win ora tie, no further
// moves are allowed.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {EMPTY, X, O} content;

module ticTacToe(clock,imove,winX,winO,finished);
    input       clock;
    input [3:0] imove;
    output 	winX, winO, finished;

    //                  0 | 1 | 2
    //                 ---+---+---
    // The game board:  3 | 4 | 5
    //                 ---+---+---
    //                  6 | 7 | 8

    content reg b[0:8];
    content reg turn;
    reg [3:0]	move;
    integer 	i;

    initial begin
	for (i = 0; i < 9; i = i + 1)
	  b[i] = EMPTY;
	turn = X;
	move = 0;
    end

    always @ (posedge clock) begin
	move = (imove < 9) ? imove : 0;
	if (!finished && b[move] == EMPTY) begin
	    b[move] = turn;
	    turn = (turn == X) ? O : X;
	end
    end

    assign winX = b[0]==X & (b[1]==X & b[2]==X | b[3]==X & b[6]==X) |
	   b[8]==X & (b[7]==X & b[6]==X | b[5]==X & b[2]==X) |
	   b[4]==X & (b[0]==X & b[8]==X | b[2]==X & b[6]==X |
		      b[1]==X & b[7]==X | b[3]==X & b[5]==X);

    assign winO = b[0]==O & (b[1]==O & b[2]==O | b[3]==O & b[6]==O) |
	   b[8]==O & (b[7]==O & b[6]==O | b[5]==O & b[2]==O) |
	   b[4]==O & (b[0]==O & b[8]==O | b[2]==O & b[6]==O |
		      b[1]==O & b[7]==O | b[3]==O & b[5]==O);

    assign finished = winX | winO |
	   b[0]!=EMPTY & b[1]!=EMPTY & b[2]!=EMPTY &
	   b[3]!=EMPTY & b[4]!=EMPTY & b[5]!=EMPTY &
	   b[6]!=EMPTY & b[7]!=EMPTY & b[8]!=EMPTY;

endmodule // ticTacToe
