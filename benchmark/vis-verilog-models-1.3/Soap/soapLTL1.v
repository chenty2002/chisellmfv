// Model for the soap mutual exclusion algorithm with monitor.
// See the monitor model atthe bottom for a description of the property
// it checks.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>
//
// The name soap comes from the following informal description of the
// problem: A hotel has low water pressure. Only one guest can shower at
// one time. To insure mutual exclusion, there is only one bar of soap.
// The guests can be seen as the nodes of an undirected graph, with edges
// connecting neighbors. A fixed undirected spannig tree of the graph is
// given, and the soap can move only along the arcs of this spanning tree.
// A direction is dynamically associated with each edge of the fixed
// spanning tree. If (x,y) is the directed edge (arc), then x is closer to
// the soap than y. Guest x is called the predecessor of guest y.
// 
// When a guest wants to shower, he requests the soap from his
// predecessor.  If the predecessor has the soap and is not showering, she
// will pass it to the requester. If she's showering, she will first finish
// and then pass the soap. Finally, if she doesn't have the soap, she will
// request it of her predecessor and, once she gets it, will pass it
// to the original requester.
//
// We have mentioned that the arcs connecting each guest to his
// predecessor form a directed spanning tree of the graph rooted at the
// guest who has the soap.  As the soap is passed around, the spanning tree
// is updated by reversing the direction of the arc from x to y when the
// soap is handed by x to y.


// Module hotel defines the interconnection of 10 guests, named GA,...,GJ.
// Each guest has at most four neighbors.  We designate them as N, W, S, E.
module hotel(clk,rst,start,showering,fair);
    parameter	 N = 3'd0,
		 W = 3'd1,
		 S = 3'd2,
		 E = 3'd3,
		 SELF = 3'd4;
    input	 clk;
    input	 rst;
    input [9:0]	 start;
    output [9:0] showering;
    output 	 fair;

    // reqIn{A-J} are the request inputs to each guest. Requests
    // from non-existing neighbors are constant 0s.
    wire [3:0]	 reqInA, reqInB, reqInC, reqInD, reqInE, reqInF, reqInG,
		 reqInH, reqInI, reqInJ;
    // reqOut{A-J} are the request outputs of each guest. Requests
    // to non-existing neighbors are never asserted.
    wire [3:0]	 reqOutA, reqOutB, reqOutC, reqOutD, reqOutE, reqOutF,
		 reqOutG, reqOutH, reqOutI, reqOutJ;
    // granted{A-J} are the inputs to each guest signaling that the soap
    // is available.
    wire [3:0]	 grantedA, grantedB, grantedC, grantedD, grantedE, grantedF,
		 grantedG, grantedH, grantedI, grantedJ;
    // grant{A-J} are the outputs of each guest signaling that the soap
    // is available.
    wire [3:0]	 grantA, grantB, grantC, grantD, grantE, grantF, grantG,
		 grantH, grantI, grantJ;

    wire [1:0]   conditionA,conditionB,conditionC,conditionD,conditionE,
		 conditionF,conditionG,conditionH,conditionI,conditionJ;

    // Connection matrix:
    //
    //     A          N
    //     |          |
    //  C--B--I     W-+-E
    //  |  |  |       |
    //  J  D--H       S
    //  |  |  |
    //  F--E--G
    //                          E,         S,         W,         N
    assign grantedA = {      1'd0, grantB[N],      1'd0,      1'd0};
    assign reqInA   = {      1'd0,reqOutB[N],      1'd0,      1'd0};
    assign grantedB = { grantI[W], grantD[N], grantC[E], grantA[S]};
    assign reqInB   = {reqOutI[W],reqOutD[N],reqOutC[E],reqOutA[S]};
    assign grantedC = { grantB[W], grantJ[N],      1'd0,      1'd0};
    assign reqInC   = {reqOutB[W],reqOutJ[N],      1'd0,      1'd0};
    assign grantedD = { grantH[W], grantE[N],      1'd0, grantB[S]};
    assign reqInD   = {reqOutH[W],reqOutE[N],      1'd0,reqOutB[S]};
    assign grantedE = { grantG[W],      1'd0, grantF[E], grantD[S]};
    assign reqInE   = {reqOutG[W],      1'd0,reqOutF[E],reqOutD[S]};
    assign grantedF = { grantE[W],      1'd0,      1'd0, grantJ[S]};
    assign reqInF   = {reqOutE[W],      1'd0,      1'd0,reqOutJ[S]};
    assign grantedG = {      1'd0,      1'd0, grantE[E], grantH[S]};
    assign reqInG   = {      1'd0,      1'd0,reqOutE[E],reqOutH[S]};
    assign grantedH = {      1'd0, grantG[N], grantD[E], grantI[S]};
    assign reqInH   = {      1'd0,reqOutG[N],reqOutD[E],reqOutI[S]};
    assign grantedI = {      1'd0, grantH[N], grantB[E],      1'd0};
    assign reqInI   = {      1'd0,reqOutH[N],reqOutB[E],      1'd0};
    assign grantedJ = {      1'd0, grantF[N],      1'd0, grantC[S]};
    assign reqInJ   = {      1'd0,reqOutF[N],      1'd0,reqOutC[S]};

    // Spanning tree:
    //
    //     A          N
    //     |          |
    //  C--B--I     W-+-E
    //     |          |
    //  J  D  H       S
    //  |  |  |
    //  F--E--G
    //
    guest GA(clk,rst,start[0],reqInA,S,   grantedA,
	     showering[0],reqOutA,grantA,conditionA);
    guest GB(clk,rst,start[1],reqInB,S,   grantedB,
	     showering[1],reqOutB,grantB,conditionB);
    guest GC(clk,rst,start[2],reqInC,E,   grantedC,
	     showering[2],reqOutC,grantC,conditionC);
    guest GD(clk,rst,start[3],reqInD,SELF,grantedD,
	     showering[3],reqOutD,grantD,conditionD);
    guest GE(clk,rst,start[4],reqInE,N,   grantedE,
	     showering[4],reqOutE,grantE,conditionE);
    guest GF(clk,rst,start[5],reqInF,E,   grantedF,
	     showering[5],reqOutF,grantF,conditionF);
    guest GG(clk,rst,start[6],reqInG,W,   grantedG,
	     showering[6],reqOutG,grantG,conditionG);
    guest GH(clk,rst,start[7],reqInH,S,   grantedH,
	     showering[7],reqOutH,grantH,conditionH);
    guest GI(clk,rst,start[8],reqInI,W,   grantedI,
	     showering[8],reqOutI,grantI,conditionI);
    guest GJ(clk,rst,start[9],reqInJ,S,   grantedJ,
	     showering[9],reqOutJ,grantJ,conditionJ);

    monitor mtr(clk, conditionA, fair);

endmodule // hotel


module guest(clk, reset, start, reqIn, initpred, granted,
	     shower, reqOut, grant, condition);
    parameter	 N = 3'd0,
		 W = 3'd1,
		 S = 3'd2,
		 E = 3'd3,
		 SELF = 3'd4;
    input	 clk, 
		 reset,
		 start;
    input [3:0]	 reqIn;
    input [2:0]	 initpred;
    input [3:0]	 granted;
    output	 shower;
    output [3:0] reqOut;
    output [3:0] grant;
    output [1:0] condition;

    parameter	 clean = 2'd0,
		 dirty = 2'd1,
		 showering = 2'd2;
    reg [1:0]	 condition;

    parameter	 idle = 1'd0,
		 busy = 1'd1;
    reg		 activity;

    reg [2:0]	 predecessor;
    reg [2:0]	 serving;
    reg [4:0]	 requestReg;	// N, W, S, E, SELF

    wire	 soap;
    wire [2:0]	 toBeServed;
    wire	 requestPending;
    wire [3:0]	 mask, mbar;
    wire	 soapIsComing;

    function select;
	input [4:0] in;
	input [2:0] sel;
    begin: selt
	if (sel == 3'd0)
	    select = in[0];
	else if (sel == 3'd1)
	    select = in[1];
	else if (sel == 3'd2)
	    select = in[2];
	else if (sel == 3'd3)
	    select = in[3];
	else if (sel == 3'd4)
	    select = in[4];
	else
	    select = 1'd0;
    end // block: selt
    endfunction // select

    function [2:0] incMod5;
	input [2:0] op;
    begin: inc
	incMod5 = (op == 3'd4) ? 3'd0 : op + 1;
    end // block: inc
    endfunction // incMod5

    function [2:0] pickRequest;
	input [4:0] req;
	input [2:0] rrobin;
    begin: pick
	pickRequest = incMod5(rrobin);
	if (req != 5'd0) begin
	    if (select(req,pickRequest) == 0)
		pickRequest = incMod5(pickRequest);
	    if (select(req,pickRequest) == 0)
		pickRequest = incMod5(pickRequest);
	    if (select(req,pickRequest) == 0)
		pickRequest = incMod5(pickRequest);
	    if (select(req,pickRequest) == 0)
		pickRequest = incMod5(pickRequest);
	end
    end // block: pick
    endfunction // pickRequest

    initial begin
	condition = clean;
	activity = idle;
	predecessor = initpred;
	requestReg = 5'd0;
	serving = SELF;
    end

    // Next state assignments.
    always @ (posedge clk) begin
	if (reset) begin
	    condition = clean;
	    activity = idle;
	    predecessor = initpred;
	    requestReg = 5'd0;
	    serving = SELF;
	end else begin
	    // disregards further requests from the guest being served.
	    requestReg[3:0] = reqIn & mask;
	    case (condition)
	      clean:
		  if (start) begin
		      condition = dirty;
		      requestReg[4] = 1;
		  end
	      dirty:
		  if (soap && serving == SELF) begin
		      condition = showering;
		      requestReg[4] = 0;
		  end
	      showering:
		  condition = clean; // one cycle to shower
	      default:
		  condition = 2'bx; // should never happen
	    endcase // case (condition)
	    case (activity)
	      idle:
		  if (requestPending && condition != showering) begin
		      serving = toBeServed;
		      activity = busy;
		  end // if(requestPending)
	      busy:
		  if (soapIsComing) begin
		      predecessor = SELF;
		  end else if (soap) begin
		      predecessor = serving;
		      activity = idle;
		  end // if (soap)
	      default: activity = 1'bx;
	    endcase // case (activity)
	end // else: !if(reset)
    end // always @ (posedge clk)

    assign shower = condition == showering;
    assign toBeServed = pickRequest(requestReg,serving);
    assign soap = predecessor == SELF;
    assign requestPending = requestReg != 5'd0;
    assign mask = ~mbar;
    assign soapIsComing = select({1'd0,granted},predecessor);

    decoder d1(serving, soap, grant);
    decoder d2(serving, 1'd1, mbar);
    decoder d3(predecessor,(activity == busy), reqOut);

endmodule // guest

module decoder(in,en,dec);
    input [2:0]  in;
    input 	 en;
    output [3:0] dec;

    assign dec[0] = en && ~in[2] && ~in[1] && ~in[0];
    assign dec[1] = en && ~in[2] && ~in[1] &&  in[0];
    assign dec[2] = en && ~in[2] &&  in[1] && ~in[0];
    assign dec[3] = en && ~in[2] &&  in[1] &&  in[0];

endmodule // decoder


// This monitor model checks an LTL property.
// In this case the property is: G(GAdirty -> F GAclean).
// The Buechi automaton is for the complement of the property,
// which is F(GAdirty * G !GAclean).
// Noting that dirty and clean are mutually exclusive, the task looks
// like this:
//  
//        |
//        V
//  TRUE (O
//        | dirty /\ !clean
//        V
// !clean(@
//        | clean
//        V
//      T(O
//
//  An @ is a fair state, a state like this "O) label" is a state with a
//  self loop labeled label
module monitor(clk,condition,fair);
    input       clk;
    input [1:0] condition;
    output 	fair;

    reg [1:0] 	state;
    wire [1:0] 	zeroorone;

    assign fair = (state == 1);
    assign zeroorone[0] = $ND(0,1);
    assign zeroorone[1] = 0;

    initial state = 0;

    always @ (posedge clk) begin
	case (state)
	  0: state = condition == 2'b01 ? zeroorone: 0;
	  1: state = condition == 2'b00 ? 2 : 1;
	  2: state = 2;
	endcase // case(state)
    end // always @ (posedge clk)

endmodule // monitor
