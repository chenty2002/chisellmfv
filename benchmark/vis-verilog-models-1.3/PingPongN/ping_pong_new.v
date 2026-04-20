/*
 * This code implements a game of ping pong (table tennis) between 2 players, with
 * a slight twist: each player puts a ball into play at the same time.  There can
 * be an arbitrary, but finite, delay from the time one player hits a ball until 
 * the time that the other player returns it.  If both balls are in play, then 
 * both players always hit each ball at the same instant.
 *
 * While both balls are in play, exactly one player may choose not to return his 
 * ball.  This leaves one ball in play.  If just one ball is in play, the ball 
 * should remain in play forever.
 *
 */

typedef enum {HIT, WAIT_GOING, WAIT_COMING} player_status;
typedef enum {HIT, IDLE} action_type;
typedef enum {TO_A, TO_B, OUT_OF_PLAY} ball_status;

module ping_pong(clk); 
input clk; 

/* signal declarations */
action_type wire action_A, action_B;
player_status wire state_A, state_B; 
ball_status wire state_ball_1, state_ball_2;

/* the ping pong players */
player player_A(clk, action_B, action_A, state_A);
player player_B(clk, action_A, action_B, state_B);

/* the balls */
ball ball_1(clk, action_A, action_B, state_ball_1, TO_A);
ball ball_2(clk, action_A, action_B, state_ball_2, TO_B);

endmodule 
 
/*
 * Three state process.  If the player is waiting while the ball is going away from
 * him (state WAIT_GOING) and the opponent hits the ball, then go to state WAIT_COMING.  
 * The player can remain in state WAIT_COMING an arbitrary, but finite, amount of time.
 * The player non-deterministically moves from state WAIT_COMING to state HIT.  From 
 * state HIT, the player immediately hits the ball.  Depending on the action of the
 * opponent, the player either moves to state WAIT_GOING or to state WAIT_COMING.
 *
 * Note that in state WAIT_GOING, there is only one ball still in play.  Also, in state
 * WAIT_COMING, it's possible that there is a ball going away, as well as coming towards
 * the player.  However, in state WAIT_GOING, there cannot be a ball coming towards
 * the player.
 */
module player(clk, opponent, out, state); 
input clk; 
input opponent; 
output out;
output state;

action_type wire opponent, out;
player_status reg state;
player_status wire r_state;

assign out = (state==HIT) ? HIT : IDLE;
assign r_state = $ND(WAIT_COMING, HIT);

initial state = HIT;

always @(posedge clk) begin
    case(state)
        HIT:
            begin
	    if (opponent == IDLE)
	        state = WAIT_GOING;
            else if (opponent == HIT)
	        state = WAIT_COMING;
	    end

        WAIT_GOING:
            begin
	    if (opponent == HIT) 
	        state = WAIT_COMING;
	    end
     
        WAIT_COMING:
            begin
            state = r_state;
	    end
     
        default:;
        endcase;
end
endmodule


/*
 * Three state process.  The ball is either going towards player A
 * (state TO_A) or towards B.  If it's ever the case that the ball
 * is going towards A, and A is IDLE and B HITS another ball, then
 * this ball goes OUT_OF_PLAY, since two balls are now going 
 * towards A, and the assumption is that A cannot return both balls.
 * The symmetic case holds when in TO_B.
 *
 * Note that this module does not produce any outputs (except for its
 * state, which is read only by the property).  Thus, if the
 * property being checked just deals directly with the players, then
 * the balls can be safely ignored.
 */
module ball(clk, action_A, action_B, state, init); 
input clk; 
input action_A, action_B; 
input init;
output state;

action_type wire action_A, action_B;
ball_status reg state;
ball_status wire init;

initial state = init;

always @(posedge clk) begin
    case(state)
        TO_A:
            begin
	    if (action_A == HIT)
	        state = TO_B;
            else if ((action_A == IDLE) && (action_B == HIT))
	        state = OUT_OF_PLAY;
	    end

        TO_B:
            begin
	    if (action_B == HIT)
	        state = TO_A;
            else if ((action_B == IDLE) && (action_A == HIT))
	        state = OUT_OF_PLAY;
	    end

        OUT_OF_PLAY:
            begin
            state = OUT_OF_PLAY;
	    end
     
        default:;
        endcase;
end
endmodule

