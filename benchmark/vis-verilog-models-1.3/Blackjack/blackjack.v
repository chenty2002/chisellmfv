// Model of blackjack game.
//
// One player and the dealer draw cards with the objective of approaching a
// total score of 21 from below.  This model implements a simplified version
// of the game.  The betting aspect is not considered.  "Splitting pairs" is
// not considered either.  The dealer must stay on "soft" 17.
// There may well be other simplifications or inaccuracies I'm not aware of,
// since I've never played blackjack...
//
// The model is designed to support variable-sized card decks.
// Besides changing the initial section, one has to adjust the MSB of the
// words of deck.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {PLAYER_INIT, DEALER_INIT, PLAYER_HIT,
	      DEALER_HIT, ENDGAME, DONE} State;

module blackjack(clock,pick,stay,win,lose,push);
    input	clock;
    input [3:0] pick;
    input 	stay;
    output 	win, lose, push;

    State reg   state;
    // Deck stores how many cards with a given values are left in the deck.
    // A 52-card deck contains 16 cards whose value is 10.  Since we need
    // to count from 16 down to 0, we need 5 bits.
    reg [4:0] 	deck[0:15];
    // Scores for player and dealer.  Both scores are kept assuming that
    // an ace's value is 1.  That is, the score recorded is the minimum
    // possible with the cards in hand.  After all cards have been dealt,
    // the score is corrected by adding 10 if an ace is present and the
    // addition does not cause the score to exceed 21.
    reg [4:0] 	pScore, dScore;
    // Number of cards in the player's and dealer's hands.  The important
    // piece of information is whether these numbers are 0, 1, 2, or more
    // than 2.
    reg [3:0] 	pCards, dCards;
    // Flags indicating whether the hands contain any aces.  Since at most
    // one ace can be made to count for 11, it is not necessary to keep
    // track of how many aces have been drawn.
    reg 	pAces, dAces;
    // Valid says whether there are any cards left with the value specified
    // by pick.  It is a reg so that we can use it in fairness conditions.
    reg 	valid;
    // The player or the dealer has a blackjack.
    wire 	pBJ, dBJ;
    integer 	i;

    assign pBJ = pScore == 21 && pCards == 2;
    assign dBJ = dScore == 21 && dCards == 2;
    assign lose = state == DONE && dScore < 22 &&
		  (pScore > 21 || pScore < dScore || dBJ && !pBJ);
    assign win = state == DONE && pScore < 22 &&
	          (dScore > 21 || dScore < pScore || pBJ && !dBJ);
    assign push = state == DONE && !lose && !win;

    initial begin
	deck[0]  = 0;
	for (i = 1; i < 10; i = i + 1)
	  deck[i]  = 4;
	deck[10] = 16;
	for (i = 11; i < 16; i = i + 1)
	  deck[i] = 0;
	state = PLAYER_INIT;
	pScore = 0; dScore = 0;
	pCards = 0; dCards = 0;
	pAces = 0; dAces = 0;
	valid = 1;
    end

    always @ (posedge clock) begin
	valid = deck[pick] > 0;
	if (valid) begin
	    case (state)
	      PLAYER_INIT: begin
		  deck[pick] = deck[pick] - 1;
		  pScore = pScore + {1'b0,pick};
		  pCards = pCards + 1;
		  if (pick == 1)
		    pAces = 1;
		  if (pCards == 2)
		    state = DEALER_INIT;
	      end
	      DEALER_INIT: begin
		  deck[pick] = deck[pick] - 1;
		  dScore = dScore + {1'b0,pick};
		  dCards = dCards + 1;
		  if (pick == 1)
		    dAces = 1;
		  if (dCards == 2)
		    state = PLAYER_HIT;
	      end
	      PLAYER_HIT: begin
		  if (stay || pScore > 20) begin
		      state = DEALER_HIT;
		  end else begin
		      deck[pick] = deck[pick] - 1;
		      pScore = pScore + {1'b0,pick};
		      pCards = pCards + 1;
		      if (pick == 1)
			pAces = 1;
		      if (pScore > 20)
			state = DEALER_HIT;
		  end
	      end
	      DEALER_HIT: begin
		  if (dScore > 16 || (dAces && dScore > 6)) begin
		      state = ENDGAME;
		  end else begin
		      deck[pick] = deck[pick] - 1;
		      dScore = dScore + {1'b0,pick};
		      dCards = dCards + 1;
		      if (pick == 1)
			dAces = 1;
		      if (dScore > 20)
			state = ENDGAME;
		  end
	      end
	      ENDGAME: begin
		  if (pScore < 11 && pAces)
		    pScore = pScore + 10;
		  if (dScore < 11 && dAces)
		    dScore = dScore + 10;
		  state = DONE;
	      end
	      DONE: ;
	    endcase
	end
    end

endmodule // blackjack
