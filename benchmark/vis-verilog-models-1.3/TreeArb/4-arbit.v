/**************************************************************************

  This is a simple tree arbiter, adapted from Dills' thesis p 89.
  There are four processors which
  share a single resource. A token defines which processor has the 
  resource. The arbiter cells have two children and one parent. 
  An arbiter can request the token, release it, etc.

  Adnan Aziz
  July 10, 1996
  UT Austin

***************************************************************************/ 

/*
 * Symbolic variables.
 *
 */
typedef enum {myTRUE, myFALSE} boolean;
typedef enum {idle, request, lock, release} handShakeType;

/*
 * The inteconnections between the processors and the cells.
 *
 */
module main( clk );
input clk;


boolean       wire ua1, ua2, ua3, ua4, xa, ya, sa;
handShakeType wire ur1, ur2, ur3, ur4, xr, yr, sr;


boolean wire constTRUE, constFALSE;

assign constTRUE  = myTRUE;
assign constFALSE = myFALSE;

assign sa = constFALSE;

arbitCell C0 ( clk, constTRUE,  xr,  yr,  xa,  ya,  sr, sa );
arbitCell C1 ( clk, constFALSE, ur1, ur2, ua1, ua2, xr, xa );
arbitCell C2 ( clk, constFALSE, ur3, ur4, ua3, ua4, yr, ya ); 

procModel P1( clk, ua1, ur1 ); 
procModel P2( clk, ua2, ur2 ); 
procModel P3( clk, ua3, ur3 ); 
procModel P4( clk, ua4, ur4 ); 

endmodule


/* 
 * The arbiter cell has two inputs from children and two outputs to chidren.
 * One input from parent, and one output to parent. The latch holdToken corresponds
 * to whether the cell holds the token. The latches prevLeft and prevRight are
 * used to keep track of which way the token went last, to impart fairness
 * in the scheduling of the children.
 *
 */

module arbitCell(clk, topCell, urLeft, urRight, uaLeft, uaRight, xr, xa);
input clk;
input topCell, urLeft, urRight, xa;
output uaLeft, uaRight, xr;

boolean       wire topCell, uaLeft, uaRight, xa; 
handShakeType wire urLeft, urRight, xr; 

boolean wire uaLeft, uaRight;

boolean reg prevLeft, prevRight;
initial prevLeft = myFALSE;
initial prevRight = myTRUE;

boolean reg processedLeft, processedRight;
initial processedLeft = myFALSE;
initial processedRight = myFALSE;

boolean wire mustGiveParent; /* essentially a macro for checking if must release the token to parent */
assign mustGiveParent = ( processedLeft == myTRUE ) && ( processedRight == myTRUE )  
                                              && ( !( topCell == myTRUE ) ) ? myTRUE : myFALSE;

boolean reg holdToken;
initial holdToken = topCell;

boolean wire childOwns; /* essentially a macro for checking if a descendant owns the token */
assign childOwns = ( urLeft == lock || urRight == lock ) ? myTRUE : myFALSE;

boolean wire giveChild; /* essentially a macro for checking if a child is being given the token */
assign giveChild = ( uaLeft == myTRUE  || uaRight == myTRUE ) ? myTRUE : myFALSE;

/*
 * Condition under which the token is given to the left child
 * Must own token, have request from left, and either no request from right or if there is
 * a request from the right it, should be lefts turn (since right went the last time 
 *
 */
assign uaLeft = ( !( mustGiveParent == myTRUE ) && ( holdToken == myTRUE && urLeft == request  
                    && ( ! ( urRight == request ) || prevRight == myTRUE ) ) ) ? myTRUE : myFALSE;

/*
 * same as above for right 
 *
 */
assign uaRight = ( !( mustGiveParent == myTRUE ) && ( holdToken == myTRUE && urRight == request  
                    && ( ! ( urLeft == request ) || prevLeft == myTRUE ) ) ) ? myTRUE : myFALSE;

/* 
 * signal to parent: 
 *
 *   1. request if dont own the token,
 *   2. lock if descendant has locked the token
 *   3. release if child has released token
 *   4. idle otherwise 
 *
 */
assign xr = ( holdToken == myFALSE  &&  ( urLeft == request || urRight == request ) )
	        ? request :
                    ( childOwns == myTRUE )
                     ? lock :
                      ( holdToken == myTRUE && ( ( ( mustGiveParent == myTRUE ) ||
                                                   ! ( ( urLeft == request || urRight == request ) ) )
                        && !( topCell == myTRUE ) ) )
                          ? release : idle;

always @(posedge clk) begin

/*
 * keep track of whether we hold the token or not
 *
 */

    if ( xa == myTRUE )
        begin
  	    holdToken = myTRUE;
        end
    else if ( giveChild == myTRUE )
        begin 
	    holdToken = myFALSE;
        end
    else if ( urLeft == release  || urRight == release )
        begin 
	    holdToken = myTRUE;
        end
   else if ( xr == release )
        begin 
	    holdToken = myFALSE;
        end

/* 
 * keep track of which child got the token last
 *
 */
   if ( uaLeft == myTRUE ) 
       begin
           prevLeft  = myTRUE;
           prevRight = myFALSE;
       end
    else if ( uaRight == myTRUE )
       begin
           prevLeft  = myFALSE;
           prevRight = myTRUE;
       end

/* 
 * child has finished processing the token
 *
 */

   if ( urLeft == release )
     begin
	processedLeft = myTRUE;
     end
   else if ( urRight == release )
      begin
	processedRight = myTRUE;
      end
/*
 * if we have given the token to both children, must now give it up
 *
 */
   else if ( ( processedLeft == myTRUE ) && ( processedRight == myTRUE ) )
	begin
	    processedLeft = myFALSE;
            processedRight = myFALSE;
        end
         
end

endmodule


/*
 *  Simple model for a processor - can be in four states,
 *  idle, req, lock, release. Non-det variable randChoice
 *  governs generation of requests, and how long hold on 
 *  to token.
 */
module procModel(clk, ack, req );
input clk;
input ack;
output req;

boolean wire ack;
handShakeType wire req;

assign req = procState;

wire[0:2] randChoice;
handShakeType reg procState;

initial procState = idle;

assign randChoice = $ND(0,1,2,3,4,5,6,7);

always @(posedge clk) begin
    
    if ( procState == idle  && (randChoice == 7) )
        begin 
	    procState = request;
        end
    else if ( procState == request && ack == myTRUE )
        begin 
	    procState = lock;
        end
    else if (  procState == lock && (randChoice > 3) ) 
        begin 
            procState = release;
        end
    else if ( procState == release )
        begin
            procState = idle;
        end
end

endmodule
