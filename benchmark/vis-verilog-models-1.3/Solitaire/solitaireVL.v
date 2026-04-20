/*
 This model finds a solution to the following puzzle.
 
 Let us consider a portion of a chess board, limited to
 the 11 squares drawn below.  The squares are numbered.

     ---
    | 0 |
     ---------------
    | 1 | 4 | 7 | 10|
     ---------------
    | 2 | 5 | 8 |
     -----------
    | 3 | 6 | 9 |
     -----------

 Only the two black knights (represented by a cross)
 and the two white knights (represented by a circle)
 are on the board.  Their initial position is:

     ---
    | o |
     ---------------
    |   |   |   | x |
     ---------------
    | o | x |   |
     -----------
    |   |   |   |
     -----------

 What is the sequence of moves (as in chess, but limited
 to the above 11 squares) to arrive to the final
 position such that white and black knights are exchanged?

 Final position :

     ---
    | x |
     ---------------
    |   |   |   | o |
     ---------------
    | x | o |   |
     -----------
    |   |   |   |
     -----------
 
 The model lists all possible transitions, that is, all legal moves.
 For instance, a knight in S0 can go to either S7 or S5.  Hence, there
 are transitions for these two cases.  A transition is enabled if there
 is a knight in the starting square, and the ending square is empty.
 The transition preserves the color of the knight.  There is a total of
 24 such moves.

 At each turn (clock cycle) one of them is selected nondeterministically by
 the input "randomize."  If the transition is not enabled, the state of the
 model does not change.
 
 The solution to the puzzle is posed as a model checking problem.

 Author:      Dominique Borrione
 Modified by: Fabio Somenzi
*/

typedef enum {white, black, empty} TYPEcase;

module solitaire1 (clk, randomize, 
                   S0, S1, S2, S3, S4, S5, S6, S7, S8, S9, S10 );
  input clk;
  input [4:0] randomize;
  output S0, S1, S2, S3, S4, S5, S6, S7, S8, S9, S10;

  TYPEcase reg S0, S1, S2, S3, S4, S5, S6, S7, S8, S9, S10;

initial begin 
  S0 = white;
  S1 = empty;
  S2 = white;
  S3 = empty;
  S4 = empty;
  S5 = black;
  S6 = empty;
  S7 = empty;
  S8 = empty;
  S9 = empty;
  S10 = black;
end

always @(posedge clk) begin
case (randomize)

  0: if ((S0 == white) && (S7 == empty))
        begin 
           S0 = empty;
           S7 = white;
        end
     else if ((S0 == black) && (S7 == empty))
        begin 
           S0 = empty;
           S7 = black;
        end

  1: if ((S0 == white) && (S5 == empty))
        begin 
           S0 = empty;
           S5 = white;
        end
     else if ((S0 == black) && (S5 == empty))
        begin 
           S0 = empty;
           S5 = black;
        end

  2: if ((S1 == white) && (S8 == empty))
        begin 
           S1 = empty;
           S8 = white;
        end
     else if ((S1 == black) && (S8 == empty))
        begin 
           S1 = empty;
           S8 = black;
        end

  3: if ((S1 == white) && (S6 == empty))
        begin 
           S1 = empty;
           S6 = white;
        end
     else if ((S1 == black) && (S6 == empty))
        begin 
           S1 = empty;
           S6 = black;
        end

  4: if ((S2 == white) && (S9 == empty))
        begin 
           S2 = empty;
           S9 = white;
        end
     else if ((S2 == black) && (S9 == empty))
        begin 
           S2 = empty;
           S9 = black;
        end

  5: if ((S2 == white) && (S7 == empty))
        begin 
           S2 = empty;
           S7 = white;
        end
     else if ((S2 == black) && (S7 == empty))
        begin 
           S2 = empty;
           S7 = black;
        end

  6: if ((S3 == white) && (S8 == empty))
        begin 
           S3 = empty;
           S8 = white;
        end
     else if ((S3 == black) && (S8 == empty))
        begin 
           S3 = empty;
           S8 = black;
        end

  7: if ((S3 == white) && (S4 == empty))
        begin 
           S3 = empty;
           S4 = white;
        end
     else if ((S3 == black) && (S4 == empty))
        begin 
           S3 = empty;
           S4 = black;
        end

  8: if ((S4 == white) && (S3 == empty))
        begin 
           S4 = empty;
           S3 = white;
        end
     else if ((S4 == black) && (S3 == empty))
        begin 
           S4 = empty;
           S3 = black;
        end

  9: if ((S4 == white) && (S9 == empty))
        begin 
           S4 = empty;
           S9 = white;
        end
     else if ((S4 == black) && (S9 == empty))
        begin 
           S4 = empty;
           S9 = black;
        end

 10: if ((S5 == white) && (S10 == empty))
        begin 
           S5 = empty;
           S10 = white;
        end
     else if ((S5 == black) && (S10 == empty))
        begin 
           S5 = empty;
           S10 = black;
        end

 11: if ((S5 == white) && (S0 == empty))
        begin 
           S5 = empty;
           S0 = white;
        end
     else if ((S5 == black) && (S0 == empty))
        begin 
           S5 = empty;
           S0 = black;
        end

 12: if ((S6 == white) && (S1 == empty))
        begin 
           S6 = empty;
           S1 = white;
        end
     else if ((S6 == black) && (S1 == empty))
        begin 
           S6 = empty;
           S1 = black;
        end

 13: if ((S6 == white) && (S7 == empty))
        begin 
           S6 = empty;
           S7 = white;
        end
     else if ((S6 == black) && (S7 == empty))
        begin 
           S6 = empty;
           S7 = black;
        end

 14: if ((S7 == white) && (S6 == empty))
        begin 
           S7 = empty;
           S6 = white;
        end
     else if ((S7 == black) && (S6 == empty))
        begin 
           S7 = empty;
           S6 = black;
        end

 15: if ((S7 == white) && (S2 == empty))
        begin 
           S7 = empty;
           S2 = white;
        end
     else if ((S7 == black) && (S2 == empty))
        begin 
           S7 = empty;
           S2 = black;
        end

 16: if ((S7 == white) && (S0 == empty))
        begin 
           S7 = empty;
           S0 = white;
        end
     else if ((S7 == black) && (S0 == empty))
        begin 
           S7 = empty;
           S0 = black;
        end

 17: if ((S8 == white) && (S3 == empty))
        begin 
           S8 = empty;
           S3 = white;
        end
     else if ((S8 == black) && (S3 == empty))
        begin 
           S8 = empty;
           S3 = black;
        end

 18: if ((S8 == white) && (S1 == empty))
        begin 
           S8 = empty;
           S1 = white;
        end
     else if ((S8 == black) && (S1 == empty))
        begin 
           S8 = empty;
           S1 = black;
        end

 19: if ((S9 == white) && (S2 == empty))
        begin 
           S9 = empty;
           S2 = white;
        end
     else if ((S9 == black) && (S2 == empty))
        begin 
           S9 = empty;
           S2 = black;
        end

 20: if ((S9 == white) && (S4 == empty))
        begin 
           S9 = empty;
           S4 = white;
        end
     else if ((S9 == black) && (S4 == empty))
        begin 
           S9 = empty;
           S4 = black;
        end

 21: if ((S9 == white) && (S10 == empty))
        begin 
           S9 = empty;
           S10 = white;
        end
     else if ((S9 == black) && (S10 == empty))
        begin 
           S9 = empty;
           S10 = black;
        end

 22: if ((S10 == white) && (S9 == empty))
        begin 
           S10 = empty;
           S9 = white;
        end
     else if ((S10 == black) && (S9 == empty))
        begin 
           S10 = empty;
           S9 = black;
        end
 23: if ((S10 == white) && (S5 == empty))
        begin 
           S10 = empty;
           S5 = white;
        end
     else if ((S10 == black) && (S5 == empty))
        begin 
           S10 = empty;
           S5 = black;
        end
 default: ; 
endcase
end
endmodule
