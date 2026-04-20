// Model of the Real Time Ethernet protocol with "Real Time First"
// policy adapted from the VPL description in Du, Smolka, and Cleaveland, 
// "Local Model Checking and Protocol Analysis."
// Note that nothing of the Carrier Sense Multiple Access/Collision Detection
// (CSMA/CD) aspect of the ethernet protocol is present in this model.
//
// Because of the lack of high-level communication primitives in Verilog,
// this model is more detailed than the original one.

// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {START_RT, RT, WAIT_RT, START_NRT, NRT, WAIT_NRT} TokenState;

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
    reg 	grant, noGrant;
    reg 	ok, notOk;

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


    // Token management.  In each cycle, first poll the nodes with real-time
    // allocations, and then use the remaining slots for a round-robin poll
    // of all nodes to deal with non-real-time traffic.

    TokenState reg tokenState;
    reg [MSB:0]    NRT_count;
    reg [MSBc:0]   index;
    reg [MSB:0]    next;
    reg 	   serving_rt;
    reg 	   token[0:N-1];

    wire 	 start, cycle;

    assign 	 start = tokenState == START_RT;
    assign 	 cycle = tokenState == NRT && NRT_count == 0;

    initial begin
	for (i = 0; i < N; i = i + 1) begin
	    token[i] = 0;
	end
	NRT_count = Slots - 1;
	serving_rt = 1;
	index = 0;
	next = 0;
	tokenState = START_RT;
    end

    always @ (posedge clock) begin
	case (tokenState)
	  START_RT: begin
	      serving_rt = 1;
	      index = 0;
	      tokenState = RT;
	      NRT_count = Slots - RT_count;
	  end
	  RT: begin
	      if (index == N) begin
		  tokenState = START_NRT;
	      end else if (node[index]) begin
		  token[index] = 1;
		  tokenState = WAIT_RT;
	      end else begin
		  index = index + 1;
	      end
	  end
	  WAIT_RT: begin
	      if (nodeState[index] == B) begin
		  token[index] = 0;
	      end
	      if (rt[index]) begin
		  index = index + 1;
		  tokenState = RT;
	      end
	  end
	  START_NRT: begin
	      serving_rt = 0;
	      tokenState = NRT;
	  end
	  NRT: begin
	      if (NRT_count == 0) begin
		  tokenState = START_RT;
	      end else begin
		  token[next] = 1;
		  tokenState = WAIT_NRT;
	      end
	  end
	  WAIT_NRT: begin
	      if (nodeState[next] == B) begin
		  token[next] = 0;
	      end
	      if (nrt[next]) begin
		  next = next + 1;  // mod N
		  NRT_count = NRT_count - 1;
		  tokenState = NRT;
	      end
	  end
	endcase
    end


    // Node process.  Nodes are nondeterministically scheduled to execute.
    // If a node has the token when it is scheduled, it initiates a
    // transmission.  The type of transmission depends on the value of
    // serving_rt.  Once the transmission is initiated, it completes even
    // if the token is removed.

    NodeState reg nodeState[0:N-1];
    reg [MSB:0]   self;
    reg 	  request;
    reg 	  rlease;
    reg 	  node[0:N-1];
    reg 	  rt[0:N-1];
    reg 	  nrt[0:N-1];
    reg 	  res[0:N-1];
    wire 	  coin;

    assign 	  coin = $ND(0,1);

    initial begin
	for (i = 0; i < N; i = i + 1) begin
	    node[i] = i == 0;
	    nodeState[i] = A;
	    rt[i] = 0;
	    nrt[i] = 0;
	    res[i] = 0;
	end
	request = 0;
	rlease = 0;
	self = 0;
    end

    always @ (posedge clock) begin
	for (i = 0; i < N; i = i + 1) begin
	    rt[i] = 0;
	    nrt[i] = 0;
	    res[i] = 0;
	end
	self = select;
	if (serving_rt) begin
	    case (nodeState[self])
	      A: begin
		  if (token[self]) begin
		      if (coin) begin
			rlease = 1;
		      end
		      nodeState[self] = B;
		  end
	      end
	      B: begin
		  if (ok) begin
		      rlease = 0;
		      node[self] = 0;
		      rt[self] = 1;
		      nodeState[self] = A;
		  end else if (notOk) begin
		      rlease = 0;
		      rt[self] = 1;
		      nodeState[self] = A;
		  end else if (!rlease) begin
		      rt[self] = 1;
		      nodeState[self] = A;
		  end
	      end
	    endcase
	end else begin // if (serving_rt)
	    case (nodeState[self])
	      A: begin
		  if (token[self]) begin
		      if (!node[self] && coin) begin
			  request = 1;
		      end
		      nodeState[self] = B;
		  end
	      end
	      B: begin
		  if (grant) begin
		      request = 0;
		      node[self] = 1;
		      res[self] = 1;
		      nrt[self] = 1;
		      nodeState[self] = A;
		  end else if (noGrant) begin
		      request = 0;
		      nrt[self] = 1;
		      nodeState[self] = A;
		  end else if (!request) begin
		      nrt[self] = 1;
		      nodeState[self] = A;
		  end
	      end
	    endcase
	end
    end

endmodule // retherRTF
