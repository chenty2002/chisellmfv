module twoQ(clock, inaddr0, inaddr1, validin, readin, select);
    parameter		WIDTH = 2; // width of the addresses
    input 		clock, select;
    input [1:0] 	validin, readin;
    input [WIDTH-1:0] 	inaddr0, inaddr1;

    wire [1:0] 		bus_req, validout, outisread;
    wire [WIDTH-1:0] 	outaddr0, outaddr1, readheadentry0, readheadentry1;

    reg [1:0] 		bus_gnt;

    // compositional LTL mc
    wire 		 p0match, p1match,busgnt0;
    wire 	p0readheadNEQp0storeaddr, p0writefifo_p0writetailEQp0readfifo_p0readhead;
    wire 	p1readheadNEQp1storeaddr, p1writefifo_p1writetailEQp1readfifo_p1readhead;
    
    assign 		 busgnt0= bus_gnt[0]==1;
    
    Buechi Buechi(clock,p0match,p0readheadNEQp0storeaddr,p0writefifo_p0writetailEQp0readfifo_p0readhead,busgnt0,fair,scc);
    
    sampleq #(WIDTH) q0 (inaddr0, validin[0], readin[0], clock,
			 bus_gnt[0], bus_req[0], outaddr0, validout[0],
			 outisread[0], readheadentry0, 
			 p0match, 
			 p0readheadNEQp0storeaddr, 
			 p0writefifo_p0writetailEQp0readfifo_p0readhead);
    
    sampleq #(WIDTH) q1 (inaddr1, validin[1], readin[1], clock,
			 bus_gnt[1], bus_req[1], outaddr1, validout[1],
			 outisread[1], readheadentry1,
			 p1match,
			 p1readheadNEQp1storeaddr, 
			 p1writefifo_p1writetailEQp1readfifo_p1readhead); 


    initial bus_gnt = 0;

    always @ (posedge clock) begin
	if (select && bus_req[1])
	  bus_gnt = 2'b10;
	else if (!select && bus_req[0])
	  bus_gnt = 2'b01;
	else
	  bus_gnt = 2'b00;
    end

endmodule // twoQ

typedef enum {n5,n6,n7,n8,n10,Trap} states;

module Buechi(clock,p0match,p0readheadNEQp0storeaddr,p0writefifo_p0writetailEQp0readfifo_p0readhead,busgnt0,fair,scc);
  input clock,p0match,p0readheadNEQp0storeaddr,p0writefifo_p0writetailEQp0readfifo_p0readhead,busgnt0;
  output fair,scc;
  states reg state;
  states wire ND_n7_n8;
  states wire ND_n5_n6;
  states wire ND_n10_n8;
  assign ND_n7_n8 = $ND(n7,n8);
  assign ND_n5_n6 = $ND(n5,n6);
  assign ND_n10_n8 = $ND(n10,n8);
  assign fair = (state == n8);
    assign scc = state==n7 || state==n8;
  initial state = n6;
  always @ (posedge clock) begin
    case (state)
      Trap:
	state = Trap;
      n6:
	case ({p0match,p0readheadNEQp0storeaddr})
	2'b0?: state = n6;
	2'b10: state = ND_n5_n6;
	2'b11: state = n6;
	endcase
      n10:
	case ({busgnt0,p0readheadNEQp0storeaddr,p0writefifo_p0writetailEQp0readfifo_p0readhead})
	3'b00?: state = n10;
	3'b01?: state = Trap;
	3'b100: state = ND_n10_n8;
	3'b101: state = n10;
	3'b11?: state = Trap;
	endcase
      n7,n8:
	case ({busgnt0,p0readheadNEQp0storeaddr,p0writefifo_p0writetailEQp0readfifo_p0readhead})
	3'b000: state = n7;
	3'b?01: state = Trap;
	3'b?1?: state = Trap;
	3'b100: state = ND_n7_n8;
	endcase
      n5:
	case (p0readheadNEQp0storeaddr)
	1'b0: state = n10;
	1'b1: state = Trap;
	endcase
    endcase
  end
endmodule

module sampleq(inaddr, validin, readin, clkin, bus_gnt, bus_req,
	       outaddr, validout, outisaread, readheadentry,
	       match, p0readheadNEQp0storeaddr,
	       p0writefifo_p0writetailEQp0readfifo_p0readhead);

    parameter		WIDTH = 2; // width of the addresses
    parameter		LENGTH = 4; // length of the read/write fifos
    parameter		LOGLENGTH = 2; //no. of bits required to encode
                                       // head/tail pointers
    input		validin, readin, clkin, bus_gnt;
    input [WIDTH-1:0]	inaddr;

    output		bus_req, validout, outisaread;
    output [WIDTH-1:0]	outaddr, readheadentry;
    
    output 		match, p0readheadNEQp0storeaddr,
			p0writefifo_p0writetailEQp0readfifo_p0readhead;
    
    reg [WIDTH-1:0]	readfifo [LENGTH-1:0];  // fifo to store read requests
    reg [WIDTH-1:0]	writefifo [LENGTH-1:0]; // fifo to store write requests
    reg [LOGLENGTH-1:0]	readtail; // points to the next incoming read address
    reg [LOGLENGTH-1:0]	readhead; // points to the next outgoing read address
    reg [LOGLENGTH-1:0]	writehead; // points to the next incoming write address
    reg [LOGLENGTH-1:0]	writetail; // points to the next outgoing write address

    reg			match; // indicates address match between the
                               // next outgoing read entry and a write entry.
    reg 		inputmatch;
    reg [WIDTH-1:0] 	outaddr; // output addres
    reg  		outisaread; // indicates output is a read
    reg			validout; // flags that the output address is valid
    wire		readfull, writefull, readempty, writeempty;
    wire [WIDTH-1:0]	readheadentry;
    integer		i;
    reg [LOGLENGTH-1:0] storeaddr; // output addres

    wire p0writefifo_p0writetailEQp0readfifo_p0readhead;
    wire p0readheadNEQp0storeaddr;
    assign p0writefifo_p0writetailEQp0readfifo_p0readhead = writefifo[writetail]==readfifo[readhead];
    assign p0readheadNEQp0storeaddr= readhead[1:0]!=storeaddr[1:0];
    
    initial begin
	storeaddr = 0;
	inputmatch = 0;
       	readtail = 0;
	writetail = 0;
	readhead = 0;
  	writehead = 0;
	validout = 0;
	outisaread = 0;
	outaddr = 0;
	match = 0;
	for (i = 0; i <= LENGTH-1; i = i + 1) begin
	    readfifo[i] = 0;
	    writefifo[i] = 0;
	end
    end

    always @(posedge clkin) begin
	// input: requests are queued in their respective FIFOs.
	if (validin && readin && !readfull) begin
	    readfifo[readtail] = inaddr;
	    readtail = readtail + 1;
	end else if (validin && !readin && !writefull) begin
	    writefifo[writetail] = inaddr;
	    writetail = writetail + 1;
	end // if (validin && !outisaread && !writefull)
        if (bus_gnt) begin
	    // checking for match between the next read queue entry and any
	    // entry in the write queue
	    match = 0;
	    for (i = 0; i <= LENGTH-1; i = i + 1) begin
		if (((writehead < writetail) && (i >= writehead) &&
		     (i < writetail)) ||
	            ((writehead > writetail) && ((i >= writehead) ||
						 (i < writetail)))) begin
		    if ((readempty == 0) &&
			(readfifo[readhead] == writefifo[i])) begin
			match = 1;
 			storeaddr = readhead;
		    end
		end
	    end // for (i = 0; i < LENGTH-1; i = i + 1)
	    // output: requests are queued out in the fifo order. Read
	    // requests are queued out before write requests unless the read
	    // address is present in the write queue. In case of a match,
	    // the read request is withheld till the write requests with the
	    // match are sent out.
	    if (!readempty && !match) begin
		outaddr = readfifo[readhead];
		readhead = readhead + 1;
		outisaread = 1;
		validout = 1;
	    end else if (!writeempty) begin
		outaddr = writefifo[writehead];
		writehead = writehead + 1;
		outisaread = 0;
		validout = 1;
	    end else begin
		validout = 0;
	    end
        end
    end // always

    assign readempty = (readtail == readhead); // read queue is empty
    assign writeempty = (writetail == writehead); // write queue is empty
    // read queue is full
    assign readfull = (((readtail +1)&{LOGLENGTH{1'b1}}) == readhead);
    // write queue is full
    assign writefull = (((writetail + 1)&{LOGLENGTH{1'b1}}) == writehead);

    assign readheadentry = readfifo[readhead];

    assign bus_req = !(readempty && writeempty);

endmodule // sampleq
