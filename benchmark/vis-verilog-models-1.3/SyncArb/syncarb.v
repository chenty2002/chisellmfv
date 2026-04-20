// Synchronous arbiter from McMillan's thesis.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>


module syncArb(clock,req,ack);
    input  clock;
    input  [15:0] req;
    output [15:0] ack;

    reg [15:0]    lreq;
    wire [16:0]     token;
    wire [16:0]     grant;
    wire [16:0]     override;
    wire [15:0]   tokenInit;

    initial lreq = 0;

    always @ (posedge clock) lreq = req;

    assign tokenInit = 16'b1;
    assign override[16] = 0;
    assign grant[0] = ~override[0];
    assign token[0] = token[16];

    cell c0 (clock,lreq[0],ack[0],token[0],token[1],tokenInit[0],
                grant[0],grant[1],override[1],override[0]);
    cell c1 (clock,lreq[1],ack[1],token[1],token[2],tokenInit[1],
                grant[1],grant[2],override[2],override[1]);
    cell c2 (clock,lreq[2],ack[2],token[2],token[3],tokenInit[2],
                grant[2],grant[3],override[3],override[2]);
    cell c3 (clock,lreq[3],ack[3],token[3],token[4],tokenInit[3],
                grant[3],grant[4],override[4],override[3]);
    cell c4 (clock,lreq[4],ack[4],token[4],token[5],tokenInit[4],
                grant[4],grant[5],override[5],override[4]);
    cell c5 (clock,lreq[5],ack[5],token[5],token[6],tokenInit[5],
                grant[5],grant[6],override[6],override[5]);
    cell c6 (clock,lreq[6],ack[6],token[6],token[7],tokenInit[6],
                grant[6],grant[7],override[7],override[6]);
    cell c7 (clock,lreq[7],ack[7],token[7],token[8],tokenInit[7],
                grant[7],grant[8],override[8],override[7]);
    cell c8 (clock,lreq[8],ack[8],token[8],token[9],tokenInit[8],
                grant[8],grant[9],override[9],override[8]);
    cell c9 (clock,lreq[9],ack[9],token[9],token[10],tokenInit[9],
                grant[9],grant[10],override[10],override[9]);
    cell c10 (clock,lreq[10],ack[10],token[10],token[11],tokenInit[10],
                grant[10],grant[11],override[11],override[10]);
    cell c11 (clock,lreq[11],ack[11],token[11],token[12],tokenInit[11],
                grant[11],grant[12],override[12],override[11]);
    cell c12 (clock,lreq[12],ack[12],token[12],token[13],tokenInit[12],
                grant[12],grant[13],override[13],override[12]);
    cell c13 (clock,lreq[13],ack[13],token[13],token[14],tokenInit[13],
                grant[13],grant[14],override[14],override[13]);
    cell c14 (clock,lreq[14],ack[14],token[14],token[15],tokenInit[14],
                grant[14],grant[15],override[15],override[14]);
    cell c15 (clock,lreq[15],ack[15],token[15],token[16],tokenInit[15],
                grant[15],grant[16],override[16],override[15]);

endmodule // syncArb


module cell(clock,req,ack,tokenIn,tokenOut,tokenInit,grantIn,grantOut,
	    overrideIn,overrideOut);
    input  clock;
    input  req;
    output ack;
    input  tokenIn;
    output tokenOut;
    input  tokenInit;
    input  grantIn;
    output grantOut;
    input  overrideIn;
    output overrideOut;

    reg    token;
    reg    waiting;
    wire   tw;

    initial begin
	token = tokenInit;
	waiting = 0;
    end // initial begin

    always @ (posedge clock) begin
	waiting = req & (waiting | token);
	token = tokenIn;
    end // always @ (posedge clock)

    assign tw = token & waiting;
    assign ack = req & (grantIn | tw);
    assign tokenOut = token;
    assign grantOut = grantIn & ~req;
    assign overrideOut = overrideIn | tw;

endmodule // cell
