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

module main (clk);
    input clk;

    phase wire r0_0, r0_1; wire a0_0, a0_1;
    arbitCell C0_0 (clk, 0, r0_0, r0_1, a0_0, a0_1, r1_0, a1_0);
    phase wire r0_2, r0_3; wire a0_2, a0_3;
    arbitCell C0_1 (clk, 0, r0_2, r0_3, a0_2, a0_3, r1_1, a1_1);
    phase wire r0_4, r0_5; wire a0_4, a0_5;
    arbitCell C0_2 (clk, 0, r0_4, r0_5, a0_4, a0_5, r1_2, a1_2);
    phase wire r0_6, r0_7; wire a0_6, a0_7;
    arbitCell C0_3 (clk, 0, r0_6, r0_7, a0_6, a0_7, r1_3, a1_3);
    phase wire r0_8, r0_9; wire a0_8, a0_9;
    arbitCell C0_4 (clk, 0, r0_8, r0_9, a0_8, a0_9, r1_4, a1_4);
    phase wire r0_10, r0_11; wire a0_10, a0_11;
    arbitCell C0_5 (clk, 0, r0_10, r0_11, a0_10, a0_11, r1_5, a1_5);

    phase wire r1_0, r1_1; wire a1_0, a1_1;
    arbitCell C1_0 (clk, 0, r1_0, r1_1, a1_0, a1_1, r2_0, a2_0);
    phase wire r1_2, r1_3; wire a1_2, a1_3;
    arbitCell C1_1 (clk, 0, r1_2, r1_3, a1_2, a1_3, r2_1, a2_1);
    phase wire r1_4, r1_5; wire a1_4, a1_5;
    arbitCell C1_2 (clk, 0, r1_4, r1_5, a1_4, a1_5, r2_2, a2_2);

    phase wire r2_0, r2_1; wire a2_0, a2_1;
    arbitCell C2_0 (clk, 0, r2_0, r2_1, a2_0, a2_1, r3_0, a3_0);
    phase wire r2_2, r2_3; wire a2_2, a2_3;
    arbitCell C2_1 (clk, 0, r2_2, r2_3, a2_2, a2_3, r3_1, a3_1);
    assign r2_3 = idle;

    phase wire r3_0, r3_1; wire a3_0, a3_1;
    arbitCell C3_0 (clk, 1, r3_0, r3_1, a3_0, a3_1, r4_0, a4_0);

    phase wire r4_0; wire a4_0;
    assign a4_0 = 0;

    proc P0 (clk, a0_0, r0_0);
    proc P1 (clk, a0_1, r0_1);
    proc P2 (clk, a0_2, r0_2);
    proc P3 (clk, a0_3, r0_3);
    proc P4 (clk, a0_4, r0_4);
    proc P5 (clk, a0_5, r0_5);
    proc P6 (clk, a0_6, r0_6);
    proc P7 (clk, a0_7, r0_7);
    proc P8 (clk, a0_8, r0_8);
    proc P9 (clk, a0_9, r0_9);
    proc P10 (clk, a0_10, r0_10);
    proc P11 (clk, a0_11, r0_11);

endmodule // main


/*
 * A process loops through four states: idle, request, lock, and release.
 * The transitions from idle to request, and from lock to release are
 * nondeterministic.
 */
module proc (clk, ack, req);
    input  clk;
    input  ack;
    output req;

    wire   ack;
    phase reg req;
    wire   choice;

    assign choice = $ND(0,1);

    initial req = idle;

    always @(posedge clk) begin
	if (req == idle  && choice == 1) begin
	    req = request;
        end else if (req == request && ack == 1) begin
	    req = lock;
        end else if (req == lock && choice == 1) begin
            req = release;
        end else if (req == release) begin
            req = idle;
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
