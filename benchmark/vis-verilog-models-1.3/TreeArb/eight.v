/*
 * Tree arbiter derived from the one of Adnan Aziz, which in turn is based
 * on the one in David Dill's thesis.  The aribter of Aziz tries to improve
 * efficiency by not returning the token every time to the root of the tree.
 * However, it has bugs that cause starvation.  This version fixes those bugs.
 *
 * Author: Fabio Somenzi <Fabio@Colorado.EDU>
 */

typedef enum {idle, request, lock, release} phase;

/*
 * The inteconnections between the processors and the cells.
 *
 */
module main (clk, choice);
    input clk;
    input [7:0] choice;

    wire  a3_0;
    wire  a2_0, a2_1;
    wire  a1_0, a1_1, a1_2, a1_3;
    wire  a0_0, a0_1, a0_2, a0_3, a0_4, a0_5, a0_6, a0_7;
    phase wire r3_0;
    phase wire r2_0, r2_1;
    phase wire r1_0, r1_1, r1_2, r1_3;
    phase wire r0_0, r0_1, r0_2, r0_3, r0_4, r0_5, r0_6, r0_7;

    assign a3_0 = 0;

    arbitCell C2_0 (clk, 1, r2_0, r2_1, a2_0, a2_1, r3_0, a3_0);

    arbitCell C1_0 (clk, 0, r1_0, r1_1, a1_0, a1_1, r2_0, a2_0);
    arbitCell C1_1 (clk, 0, r1_2, r1_3, a1_2, a1_3, r2_1, a2_1);

    arbitCell C0_0 (clk, 0, r0_0, r0_1, a0_0, a0_1, r1_0, a1_0);
    arbitCell C0_1 (clk, 0, r0_2, r0_3, a0_2, a0_3, r1_1, a1_1);
    arbitCell C0_2 (clk, 0, r0_4, r0_5, a0_4, a0_5, r1_2, a1_2);
    arbitCell C0_3 (clk, 0, r0_6, r0_7, a0_6, a0_7, r1_3, a1_3);

    proc P0 (clk, a0_0, r0_0, choice[0]);
    proc P1 (clk, a0_1, r0_1, choice[1]);
    proc P2 (clk, a0_2, r0_2, choice[2]);
    proc P3 (clk, a0_3, r0_3, choice[3]);
    proc P4 (clk, a0_4, r0_4, choice[4]);
    proc P5 (clk, a0_5, r0_5, choice[5]);
    proc P6 (clk, a0_6, r0_6, choice[6]);
    proc P7 (clk, a0_7, r0_7, choice[7]);

endmodule // main


/*
 * A process loops through four states: idle, request, lock, and release.
 * The transitions from idle to request, and from lock to release are
 * nondeterministic.
 */
module proc (clk, ack, req, choice);
    input  clk;
    input  ack;
    input  choice;
    output req;

    wire   ack;
    phase wire req;

    assign req = procState;

    phase reg procState;

    initial procState = idle;

    always @(posedge clk) begin
	if (procState == idle  && choice == 1) begin
	    procState = request;
        end else if (procState == request && ack == 1) begin
	    procState = lock;
        end else if (procState == lock && choice == 1) begin
            procState = release;
        end else if (procState == release) begin
            procState = idle;
        end
    end

endmodule // proc


/*
 * The arbiter cell has two inputs from children and two outputs to chidren.
 * One input from parent, and one output to parent. The latch holdToken
 * corresponds to whether the cell holds the token. The latch prevLeft
 * is used to keep track of which way the token went last,
 * to impart fairness in the scheduling of the children.
 */
module arbitCell (clk, topCell, urLeft, urRight, uaLeft, uaRight, xr, xa);
    input  clk;
    input  topCell, urLeft, urRight, xa;
    output uaLeft, uaRight, xr;

    wire   topCell, uaLeft, uaRight, xa;
    phase wire urLeft, urRight, xr;

    wire   uaLeft, uaRight;

    reg    prevLeft;
    initial prevLeft = 0;

    reg    processedLeft, processedRight;
    initial processedLeft = 0;
    initial processedRight = 0;

    wire   mustGiveParent; /* essentially a macro for checking
			     if must release the token to parent */
    assign mustGiveParent = (processedLeft || urLeft != request) &&
	   (processedRight || urRight != request) && !topCell;

    reg    holdToken;
    initial holdToken = topCell;

    wire   childOwns; /* essentially a macro for checking
		        if a descendant owns the token */
    assign childOwns = urLeft == lock || urRight == lock;

    wire   giveChild; /* essentially a macro for checking
		        if a child is being given the token */
    assign giveChild = uaLeft || uaRight;

    /*
     * Condition under which the token is given to the left child
     * Must own token, have request from left, and either no request from
     * right or if there is a request from the right it should be left's
     * turn (since right went the last time )
     */
    assign uaLeft = !mustGiveParent && holdToken && urLeft == request
	   && (urRight != request || !prevLeft);

    /*
     * same as above for right
     */
    assign uaRight = !mustGiveParent && holdToken && urRight == request
	   && (urLeft != request || prevLeft);

    wire   requesting;
    assign requesting = urLeft == request || urRight == request;
    /*
     * signal to parent:
     *
     *   1. request if don't own the token,
     *   2. lock if descendant has locked the token
     *   3. release if child has released token
     *   4. idle otherwise
     */
    assign xr = !holdToken && requesting ? request
	   : childOwns ? lock
	   : holdToken && !topCell && (mustGiveParent || !requesting) ? release
	   : idle;

    always @(posedge clk) begin
	/*
	 * keep track of whether we hold the token or not
	 */
	if (xa) begin
  	    holdToken = 1;
	    processedLeft = 0;
            processedRight = 0;
        end else if (giveChild) begin
	    holdToken = 0;
        end else if (urLeft == release || urRight == release) begin
	    holdToken = 1;
        end else if (xr == release) begin
	    holdToken = 0;
        end

	/*
	 * keep track of which child got the token last
	 */
	if (uaLeft) begin
            prevLeft  = 1;
	end else if (uaRight) begin
            prevLeft  = 0;
	end

	/*
	 * child has finished processing the token
	 */
	if (urLeft == release) begin
	    processedLeft = 1;
	end else if (urRight == release) begin
	    processedRight = 1;
	end
    end

endmodule // arbitCell
