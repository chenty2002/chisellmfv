// Verilog model of the Needham-Schroeder protocol with Lowe's fix.
//
// Adapted from the STeP model written by Calogero Zarba
// (http://www-step.stanford.edu/case-studies/security/).
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

// --------------------------------------------------------------
//
//  1. A->B: {Na,A}Kb
//  2. B->A: {Na,Nb,B}Ka       -- A assumes it is talking to B
//  3. A->B: {Nb}Kb            -- B assumes it is talking to A
//
//  A: initiator, B: responder
//
// --------------------------------------------------------------

// States for initiators and responders
typedef enum {SLEEPING, WAITING, COMMITTED} States;

typedef enum {			// different types of messages
    M_NoMessage,		// no message
    M_NonceAddress,		// {Na, A}Kb:   nonce and address
    M_NonceNonceAddress,	// {Na,Nb,B}Ka: two nonces and address
    M_Nonce			// {Nb}Kb:      one nonce
} messageType;

module ns2(clock, selectS, selectO, intercept, knowledge, message, n1, n2,
	   agent, coin);
    input         clock;
    input [MSB:0] selectS, selectO;
    input 	  intercept;
    input [KMB:0] knowledge;
    input 	  message;
    input [MSB:0] n1;
    input [MSB:0] n2;
    input [MSB:0] agent;
    input 	  coin;

    messageType wire message;

    parameter 	  numInitiators = 1;   // number of initiators
    parameter 	  numResponders = 1;   // number of responders
    parameter 	  numIntruders  = 1;   // number of intruders
    parameter 	  numAgents     = numInitiators + numResponders + numIntruders;
    parameter 	  maxKnowledge  = 3;   // maximum number of messages
                                       // intruder can remember - 1
    parameter 	  MSB           = 1;
    parameter 	  KMB           = 1;
    parameter 	  minInitiator  = 0;
    parameter 	  maxInitiator  = numInitiators - 1;
    parameter 	  minResponder  = maxInitiator + 1;
    parameter 	  maxResponder  = maxInitiator + numResponders;
    parameter 	  minIntruder   = maxResponder + 1;
    parameter 	  maxIntruder   = maxResponder + numIntruders;
    // maxCmsgIndex = (maxKnowledge + 1) * numIntruders - 1
    parameter 	  maxCmsgIndex  = 3;
    // maxCnncIndex = numAgents * numIntruders - 1
    parameter 	  maxCnncIndex  = 2;

    function isInitiator;
	input [MSB:0] i;
	begin: _isInitiator
	    isInitiator = (i >= minInitiator) && (i <= maxInitiator);
	end // block: _isInitiator
    endfunction // isInitiator

    function isResponder;
	input [MSB:0] i;
	begin: _isResponder
	    isResponder = (i >= minResponder) && (i <= maxResponder);
	end // block: _isResponder
    endfunction // isResponder

    function isIntruder;
	input [MSB:0] i;
	begin: _isIntruder
	    isIntruder = (i >= minIntruder) && (i <= maxIntruder);
	end // block: _isIntruder
    endfunction // isIntruder

    function [MSB+MSB+1:0] nncIndex;
	input [MSB:0] row;
	input [MSB:0] col;
	reg [MSB:0] tmp;
	begin: _nncIndex
	    tmp = row - minIntruder;
	    nncIndex = {tmp,col};
	end // block: _nncIndex
    endfunction // nncIndex

    function [MSB+KMB+1:0] msgIndex;
	input [MSB:0] row;
	input [KMB:0] col;
	reg [MSB:0] tmp;
	begin: _msgIndex
	    tmp = row - minIntruder;
	    msgIndex = {tmp,col};
	end // block: _msgIndex
    endfunction // msgIndex

    // Net variables.
    reg [MSB:0] source;		// source of message
    reg [MSB:0] dest;		// intended destination of message
    reg [MSB:0] key;		// key used for encryption
    messageType reg mType;	// type of message
    reg [MSB:0] nonce1;		// nonce1
    reg [MSB:0] nonce2;		// nonce2 OR sender identifier OR empty
    reg [MSB:0] address;	// sender identifier
    wire 	empty;

    assign 	empty = mType == M_NoMessage;

    // Initiator variables.
    States reg  Astate [minInitiator:maxInitiator];
    reg [MSB:0] Apartner [minInitiator:maxInitiator];

    // Responder variables.
    States reg  Bstate [minResponder:maxResponder];
    reg [MSB:0] Bpartner [minResponder:maxResponder];

    // Intruder variables.
    reg 	Cnonces          [0:maxCnncIndex];
    reg [MSB:0] CmessageKey      [0:maxCmsgIndex];
    messageType reg CmessageType [0:maxCmsgIndex];
    reg [MSB:0] CmessageNonce1   [0:maxCmsgIndex];
    reg [MSB:0] CmessageNonce2   [0:maxCmsgIndex];
    reg [MSB:0] CmessageAddress  [0:maxCmsgIndex];
    reg [KMB:0] Cpointer         [minIntruder:maxIntruder];

    reg [MSB:0] self, other;

    integer 	i;

    initial begin
	source = 0;
	dest = 0;
	key = 0;
	mType = M_NoMessage;
	nonce1 = 0;
	nonce2 = 0;
	address = 0;
	for (i = minInitiator; i <= maxInitiator; i = i + 1) begin
	    Astate[i] = SLEEPING;
	    Apartner[i] = 0;
	end
	for (i = minResponder; i <= maxResponder; i = i + 1) begin
	    Bstate[i] = SLEEPING;
	    Bpartner[i] = 0;
	end
	for (i = 0; i <= maxCnncIndex; i = i + 1) begin
	    Cnonces[i] = 0;
	end
	for (i = minIntruder; i <= maxCnncIndex; i = i + numAgents + 1) begin
	    Cnonces[i] = 1;
	end
	for (i = 0; i <= maxCmsgIndex; i = i + 1) begin
	    CmessageKey[i] = 0;
	    CmessageType[i] = M_NoMessage;
	    CmessageNonce1[i] = 0;
	    CmessageNonce2[i] = 0;
	    CmessageAddress[i] = 0;
	end
	for (i = minIntruder; i <= maxIntruder; i = i + 1) begin
	    Cpointer[i] = 0;
	end
	self = 0;
	other = 0;
    end

    always @ (posedge clock) begin
	self = selectS;
	other = selectO;
	if (isInitiator(self)) begin
	    case (Astate[self])
	      SLEEPING: begin
		  if (empty && (isResponder(other) || isIntruder(other))) begin
		      // initiator starts protocol with responder or intruder
		      source = self;
		      dest = other;
		      key = other;
		      mType = M_NonceAddress;
		      nonce1 = self;
		      nonce2 = self;
		      address = self;
		      Astate[self] = WAITING;
		      Apartner[self] = other;
		  end
	      end
	      WAITING: begin
		  if (!empty && dest == self) begin
		      if (key == self && mType == M_NonceNonceAddress &&
			  nonce1 == self && address == Apartner[self]) begin
			  // initiator reacts to nonce received
			  source = self;
			  dest = Apartner[self];
			  key = Apartner[self];
			  mType  = M_Nonce;
			  nonce1 = nonce2;
			  // nonce2 = nonce2;
			  // address = nonce2;
			  Astate[self] = COMMITTED;
		      end else begin
			  mType = M_NoMessage;
			  source = 0;
			  dest = 0;
			  key = 0;
			  nonce1 = 0;
			  nonce2 = 0;
			  address = 0;
		      end
		  end
	      end
	    endcase
	end else if (isResponder(self)) begin
	    case (Bstate[self])
	      SLEEPING: begin
		  if (!empty && dest == self) begin
		      if (key == self && mType == M_NonceAddress) begin
			  // responder reacts to initiator's nonce
			  Bpartner[self] = nonce2;
			  source = self;
			  dest = nonce2;
			  key = nonce2;
			  mType = M_NonceNonceAddress;
			  // nonce1 = nonce1;
			  nonce2 = self;
			  address = self;
			  Bstate[self] = WAITING;
		      end else begin
			  mType = M_NoMessage;
			  source = 0;
			  dest = 0;
			  key = 0;
			  nonce1 = 0;
			  nonce2 = 0;
			  address = 0;
		      end
		  end
	      end
	      WAITING: begin
		  if (!empty && dest == self) begin
		      if (key == self && mType == M_Nonce &&
			  nonce1 == self) begin
			  // responder reacts to own nonce
			  Bstate[self] = COMMITTED;
		      end
		      mType = M_NoMessage;
		      source = 0;
		      dest = 0;
		      key = 0;
		      nonce1 = 0;
		      nonce2 = 0;
		      address = 0;
		  end
	      end
	    endcase
	end else if (isIntruder(self)) begin
	    if (!empty && !isIntruder(source)) begin
		if (key == self) begin
		    Cnonces[nncIndex(self,nonce1)] = 1;
		    if(mType == M_NonceNonceAddress) begin
			// intruder learns two nonces
			Cnonces[nncIndex(self,nonce2)] = 1;
		    end
		end else begin
		    // intruder learns message
		    CmessageKey[msgIndex(self,Cpointer[self])] = key;
		    CmessageType[msgIndex(self,Cpointer[self])] = mType;
		    CmessageNonce1[msgIndex(self,Cpointer[self])] = nonce1;
		    CmessageNonce2[msgIndex(self,Cpointer[self])] = nonce2;
		    CmessageAddress[msgIndex(self,Cpointer[self])] = address;
		    if (Cpointer[self] == maxKnowledge) begin
			Cpointer[self] = 0;
		    end else begin
			Cpointer[self] = Cpointer[self] + 1;
		    end
		end
		if (intercept) begin
		    mType = M_NoMessage;
		    source = 0;
		    dest = 0;
		    key = 0;
		    nonce1 = 0;
		    nonce2 = 0;
		    address = 0;
		end
	    end else if (empty && (isInitiator(other) ||
				   isResponder(other))) begin
		if (coin && knowledge <= maxKnowledge) begin
		    if (CmessageType[msgIndex(self,knowledge)]
			!= M_NoMessage) begin
			// intruder sends recorded message
			source = self;
			dest = other;
			key = CmessageKey[msgIndex(self,knowledge)];
			mType = CmessageType[msgIndex(self,knowledge)];
			nonce1 = CmessageNonce1[msgIndex(self,knowledge)];
			nonce2 = CmessageNonce2[msgIndex(self,knowledge)];
			address = CmessageAddress[msgIndex(self,knowledge)];
		    end
		end
		if (!coin && (n1 < numAgents) && (n2 < numAgents) &&
		    (agent < numAgents) && (message != M_NoMessage)) begin
		    if (Cnonces[nncIndex(self,n1)] &&
			Cnonces[nncIndex(self,n2)]) begin
			// intruder generates message with known nonces
			source =  self;
			dest = other;
			key = other;
			mType = message;
			nonce1 = n1;
			nonce2 = message == M_NonceNonceAddress ? n2 : agent;
			address = agent;
		    end
		end
	    end
	end
    end

endmodule // ns2
