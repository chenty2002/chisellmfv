// Model of the Real Time Ethernet protocol with "Sequential Order"
// policy adapted from the VPL description in Du, Smolka, and Cleaveland, 
// "Local Model Checking and Protocol Analysis."
// Note that nothing of the Carrier Sense Multiple Access/Collision Detection
// (CSMA/CD) aspect of the ethernet protocol is present in this model.
//
// Because of the lack of high-level communication primitives in Verilog,
// this model is more detailed than the original one.

// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {START, POLL, WAIT1, WAIT2} TokenState;

typedef enum {A, B} NodeState;

module retherRTF(clock,select);
    input clock;
    input [MSB:0] select;

    parameter N = 4;		// number of nodes
    parameter MSB = 1;		// enough for 0..N-1
    parameter MSBc = 2;		// enough for 0..N
    parameter Slots = 3;	// number of slots in a cycle
    parameter RTSlots = 2;	// must be < Slots
    integer   i;


    // Bandwidth allocation.  Ensures that the number of slots allocated
    // to real-time transmissions is between 1 and RTSlots.  Uses four-phase
    // handshaking with the nodes.

    reg [MSB:0] RT_count;
    reg 	 grant, noGrant;
    reg 	 ok, notOk;

    initial begin
	grant = 0;
	noGrant = 0;
	ok = 0;
	notOk = 0;
	RT_count = 1;
    end

    always @ (posedge clock) begin
	if (grant || noGrant) begin
	    if (!request) begin
		grant = 0;
		noGrant = 0;
	    end
	end else if (request) begin
	    if (RT_count < RTSlots) begin
		RT_count = RT_count + 1;
		grant = 1;
	    end else begin
		noGrant = 1;
	    end
	end else if (ok || notOk) begin
	    if (!rlease) begin
		ok = 0;
		notOk = 0;
	    end
	end else if (rlease) begin
	    if (RT_count > 1) begin
		RT_count = RT_count - 1;
		ok = 1;
	    end else begin
		notOk = 1;
	    end
	end
    end // always @ (posedge clock)


    // Token management.  In each cycle, polls the nodes in ID order until
    // all bandwidth has been consumed.

    TokenState reg tokenState;
    reg [MSB:0]    index;
    reg 	   token[0:N-1];

    wire 	   start, cycle;

    assign start = tokenState == START;
    assign cycle = tokenState == POLL && NRT_count == 0 && tok_RT_count == 0;

    initial begin
	for (i = 0; i < N; i = i + 1) begin
	    token[i] = 0;
	end
	index = 0;
	tokenState = START;
    end

    always @ (posedge clock) begin
	case (tokenState)
	  START: begin
	      index = 0;
	      tokenState = POLL;
	  end
	  POLL: begin
	      if (NRT_count == 0 && tok_RT_count == 0) begin
		  tokenState = START;
	      end else begin
		  token[index] = 1;
		  tokenState = WAIT1;
	      end
	  end
	  WAIT1: begin
	      if (nodeBusy) begin
		  token[index] = 0;
		  tokenState = WAIT2;
	      end
	  end
	  WAIT2: begin
	      if (!nodeBusy) begin
		  index = index + 1;
		  tokenState = POLL;
	      end
	  end
	endcase
    end


    // Node process.  Nodes are nondeterministically scheduled to execute.
    // If a node has the token when it is scheduled, it initiates one or two
    // transmissions.  The transmissions depend on the value of NRT_enabled
    // and on the residual number of available RT slots for this cycle
    // (tok_RT_count).  Once transmissions are initiated, they complete even
    // if the token is removed.

    NodeState reg nodeState[0:N-1];
    reg [MSB:0]   self;
    reg [MSB:0]   tok_RT_count;
    reg [MSB:0]   NRT_count;
    reg [MSB:0]   total_NRT;
    reg [MSB:0]   next;
    reg 	  request;
    reg 	  rlease;
    reg 	  nodeBusy;
    reg 	  node[0:N-1];
    reg 	  rt[0:N-1];
    reg 	  nrt[0:N-1];
    reg 	  res[0:N-1];
    wire 	  coin;
    wire 	  NRT_enabled;

    assign coin = $ND(0,1);
    assign NRT_enabled = NRT_count > 0 &&
	                 (next == index || 
			  (index < next && total_NRT > (N - next) + index));

    initial begin
	for (i = 0; i < N; i = i + 1) begin
	    node[i] = i == 0;
	    nodeState[i] = A;
	    rt[i] = 0;
	    nrt[i] = 0;
	    res[i] = 0;
	end
	tok_RT_count = 1;
	NRT_count = Slots - 1;
	total_NRT = NRT_count;
	request = 0;
	rlease = 0;
	nodeBusy = 0;
	next = 0;
	self = 0;
    end

    always @ (posedge clock) begin
	for (i = 0; i < N; i = i + 1) begin
	    rt[i] = 0;
	    nrt[i] = 0;
	    res[i] = 0;
	end
	if (start) begin
	    tok_RT_count = RT_count;
	    NRT_count = Slots - RT_count;
	    total_NRT = NRT_count;
	end
	self = select;
	case (nodeState[self])
	  A: begin
	      if (token[self]) begin
		  nodeBusy = 1;
		  if (node[self]) begin
		      if (tok_RT_count > 0) begin
			  tok_RT_count = tok_RT_count - 1;
			  rt[self] = 1;
		      end
		      if (coin) begin
			  rlease = 1;
		      end
		  end else begin
		      if (coin) begin
			  request = 1;
		      end
		  end
		  nodeState[self] = B;
	      end
	  end
	  B: begin
	      if (grant) begin
		  request = 0;
		  node[self] = 1;
		  res[self] = 1;
	      end else if (noGrant) begin
		  request = 0;
	      end else if (ok) begin
		  rlease = 0;
		  node[self] = 0;
	      end else if (notOk) begin
		  rlease = 0;
	      end
	      if (grant || noGrant || ok || notOk || !(rlease || request)) begin
		  if (NRT_enabled) begin
		      nrt[self] = 1;
		      NRT_count = NRT_count - 1;
		      if (index == next) begin
			  next = next + 1;
		      end
		  end
		  nodeBusy = 0;
		  nodeState[self] = A;
	      end
	  end
	endcase
    end

endmodule // retherRTF
