// +----------------------------------------------------------------+
// |            Copyright (c) 1994 Stanford University.             |
// |                      All Rights Reserved.                      |
// |                                                                |
// |   This software is distributed with *ABSOLUTELY NO SUPPORT*    |
// |   and *NO WARRANTY*.   Use or reproduction of this code for    |
// |   commerical gains is strictly prohibited.   Otherwise, you    |
// |   are given permission to use or modify this code as long      |
// |   as you do not remove this notice.                            |
// +----------------------------------------------------------------+
//
//  Title: 	External Interface
//  Created:	Feb 29 1992
//  Author: 	Ricardo Gonzalez
//		<ricardog@caddy>
//
//
//  extIntControl.v,v 7.19 1995/02/08 01:30:34 ricardog Exp
//
//  TORCH Research Group.
//  Stanford University.
//	1992.
//
//	Description: 
// For a more detailed escription on how the external interface works see
// the file /chroma/c1/torch/extInterface.txt
//
//	Hierarchy:
//
//  Revision History:
//	Modified:	Sat Apr  9 16:34:54 1994	<ricardog@chroma>
//	* Fixed verilint errors.
//	Modified:	Fri Oct 16 15:38:07 1992	<ricardog@caddy>
//	* Cleaned it up and removed timing violations.
//	Modified:	Apr 2 1992	<ricardog@caddy>
//	* Made code more structural.
//
`include "torch.h"

//============================================================================
// These are the states of the main FSM. Each one corresponds to the
// different actions the EI has to take.
`define IDLE		3'b000
`define MISS		3'b001
`define NON_CACHE	3'b010
`define EXT_REQST	3'b011
`define MISSED_L2	3'b100
//============================================================================
`define READ		1'b1
`define WRITE		1'b0
//============================================================================
`define GRANTED		1'b1
`define NOT_GRANTED	1'b0
//============================================================================
// Simply to make the code clearer
`define TRUE		1'b1
`define FALSE		1'b0
//============================================================================


module extIntControl(
    ExtRead_s1,
    ExtRequest_s1,
    NonCacheable_s1,
    DExtRequest_s1,
    DExtRead_s1,
    DNonCacheable_s1,
    ReqLength_s1,
    L1Hit_s1,
    Phi1,
//    Phi2,
    Reset_s1,
    ReqNextBlock_s2,
    ExtDataValid_s2,
    L2Miss_s2,
    DrivePadAddr_s1,
    ReqICache_s1,
    ReqDCache_s1,
    ReqBus_s1,
    DriveSharedMemAddr_s1,
    DriveSharedMemData_s2,
    DrivePadData_s2,
    L2Valid_s2,
    BusError_s2,
    ExternRead_s1,
    NonCacheableOp_s1,
    Grant_s1,
    Reset_ww,
    ConfigIn,
    ConfigOut,
    loadNewAddr_s1,
    selShMemAddr_s1,
    drvShMemAddr_q1,
    ratio_s2,
    tagMatch_v2
    );


//
// Clocks
//
input		Phi1;
wire		Phi2;
output	    	loadNewAddr_s1;		  // Qual with latch_addr

//
// Signals to the datapath section
//
output [3:0]	ratio_s2;
output		selShMemAddr_s1;
output		drvShMemAddr_q1;

//
// Signals for tlb to start a request
//
input [5:0]	ReqLength_s1;		// Number of bytes in transaction
input		ExtRead_s1;	    	// 1 => op is load, 0 => op is write
input		ExtRequest_s1;	        // 1 => SharedMemAddr_s1 has valid addr
input		NonCacheable_s1;    	// 1 => request is nonCacheable
input		L1Hit_s1;		// 1=> hit on L1, 0=> line not on L1$
output		ExtDataValid_s2;	// High when SharedMemData_s2 has data
output		L2Miss_s2;		// L2 missed so restart refill
output		Reset_s1;		// external asynch reset
input		ReqNextBlock_s2;	// put next data on SharedMemData

//
// The LSunit's signals for starting a request
//
input		DExtRequest_s1;	// L/S has a cache refill request
input		DNonCacheable_s1;	// The type of L/S request
input		DExtRead_s1;		// 1 ==> L/S is doing a read

//
// Signals from the pads, i.e. from outside world
//
input	        ReqICache_s1;		  // Request line from I-$ (testing)
input	        ReqDCache_s1;		  // Request line from D-$ (testing)
input	        ReqBus_s1;		  // GA request memory bus
input	        L2Valid_s2;		  // 1 => L2 data is valid
input	        BusError_s2;		  // Bus error on transaction (uh uh)
input	        Reset_ww;		  // Asynch reset
input	        ConfigIn;		  // To configure rate and latency
input	    	tagMatch_v2;		  // From datapath. 1=> tag matched

//
// Siganls to the pads (not outside world)
//
output	        DrivePadAddr_s1;	  // Drive pad addr
output	        DriveSharedMemAddr_s1;	  // Drive internal addr bus
output	        DriveSharedMemData_s2;	  // Drive internal data bus
output	        DrivePadData_s2;	  // Drive data on pads
output	        Grant_s1;		  // Grant memory bus to GA
output	        ConfigOut;		  // To read out rate & latency
output		ExternRead_s1;		  // These two signals are the or of
output		NonCacheableOp_s1;	  // the signals comming from TLB and LS

assign ConfigOut = 0;		// FS: this was not driven

//
// To simplify the logic
//
wire	    	extRequest_s1;		  // Any kind of request (I or D)
wire	    	extRead_s1;		  // What type of request
wire	    	nonCacheable_s1;	  // Is it non$able?

assign extRequest_s1	= ExtRequest_s1 || DExtRequest_s1;
assign extRead_s1	= ExtRead_s1 | DExtRead_s1;
assign nonCacheable_s1	= (NonCacheable_s1 & ExtRequest_s1) |
			(DNonCacheable_s1 & DExtRequest_s1);

//
// Wire declarations
//
wire		loadNewAddr_s1;		  // New addrs on the bus

//
// Registers for the state machines
//
reg	[2:0]	mainState_s1, mainState_s2;
reg		rwState_s1, rwState_s2;
reg		prevRwState_s1, prevRwState_s2;
reg		busState_s1, busState_s2;

//
// This is to keep the address, lenght, non/cacheable, and read/write  of
// the request until we know that there is a request
//
reg	[2:0]	reqLength_s1, reqLength_s2;
reg	[2:0]	numberAddrs_s1, numberAddrs_s2;

//
// Tag comparison is done in Phi2
//
reg		l2Miss_s1, l2Miss_s2;
wire		l2Miss_v2;


//
// Need a latch to hold these signals
//
reg		extDataReady_s2;
reg		reqBus_s2;

//
// Counters to know when to drive Addr and Data
//
reg	[4:0]	nextAddr_s1, nextAddr_s2;
reg	[2:0]	nextData_s1, nextData_s2;

//
// In future ConfigIn will drive the input to these latches
//
reg	[4:0]	rate_s1;
reg	[4:0]	latency_s1;
reg	[3:0]	ratio_s2;

//---------------------------------------------------------------------------
//			    ---- Error ----
//---------------------------------------------------------------------------
// Signal never gets set (but is used). Should indicate when an external
// request is done.
reg		done_s2;		// Finished handling a request

//
// Synch'ed Reset
//
reg		Reset_s1;

//
// Register to hold next-state functions of state machines
//
wire	[2:0] 	mainState_v1;
wire	[2:0]	mainState_v2;
wire	[2:0]	reqLength_v1;
wire	[2:0]	numberAddrs_v1;
wire	[4:0]	nextAddr_v1;
wire	[2:0]	nextData_v1;
wire     	rwState_v1;
wire     	busState_v1;
wire     	extDataReady_v1;

initial begin
    mainState_s1 = 0;
    mainState_s2 = 0;
    rwState_s1 = 0;
    rwState_s2 = 0;
    prevRwState_s1 = 0;
    prevRwState_s2 = 0;
    busState_s1 = 0;
    busState_s2 = 0;
    reqLength_s1 = 0;
    reqLength_s2 = 0;
    numberAddrs_s1 = 0;
    numberAddrs_s2 = 0;
    l2Miss_s1 = 0;
    l2Miss_s2 = 0;
    extDataReady_s2 = 0;
    reqBus_s2 = 0;
    nextAddr_s1 = 0;
    nextAddr_s2 = 0;
    nextData_s1 = 0;
    nextData_s2 = 0;
    rate_s1 = 0;
    latency_s1 = 0;
    ratio_s2 = 0;
    done_s2 = 0;
    Reset_s1 = 0;
end

assign Phi2 = ~Phi1;

//
// These are just to make my life easier.
//
wire		dataHere_s2;
assign dataHere_s2 = nextData_s2 == 3'b0;

wire		driveAddr_s1;
assign driveAddr_s1 = nextAddr_s1 == 5'd0;

//
// These signals go to the pads
//
assign ExternRead_s1	= (mainState_s1 == `IDLE & extRead_s1) |
			(mainState_s1 == `MISS & rwState_s1 == `READ);
assign NonCacheableOp_s1 = nonCacheable_s1;


// L2Miss is asserted when:
// 1	We check the tags and missed,
// 2	We check the tags and data not valid
// 3	We are waiting for L2 to finish the refill
assign L2Miss_s2 = l2Miss_s2;


// Signal to the chip that data is back
assign ExtDataValid_s2 = extDataReady_s2 |
			(nextAddr_s2 == 3'b1 & rwState_s2 == `WRITE &
			    mainState_s1 == `MISS);

// Grant the bus to the GA when missed on L2 cache
assign Grant_s1 = (mainState_s1 == `MISSED_L2);

// always drive bus when op is a READ
assign DriveSharedMemData_s2 = (rwState_s2 == `READ);

// Just a hack for now, until probes are ready
assign DriveSharedMemAddr_s1 = 1'b0;

// Always drive the addr bus except when missed on L2
// Also some more stuff needs to be added here.
assign DrivePadAddr_s1 = extRequest_s1 ||
			(numberAddrs_s1 != 3'd0 && driveAddr_s1 && 
			mainState_s1 == `MISS);

// Always drive data bus, except when op is a READ
assign DrivePadData_s2 = ~(prevRwState_s2 == `READ);

//
// Only open the addr latches when a new addr on the bus
//
assign loadNewAddr_s1 = 
			(mainState_s1 == `MISS & driveAddr_s1) |
			(mainState_s1 == `IDLE & extRequest_s1);
assign selShMemAddr_s1 = mainState_s1 == `IDLE;
assign drvShMemAddr_q1 = (mainState_s1 == `MISS & driveAddr_s1) & Phi1;

//
// Determine if we hit on L2 cache or not
//
// Need to hold L2Miss_s1, so latch the information. Three conditions set 
// indicate a miss,
// 1	We check the tags and missed,
// 2	We check the tags and data not valid
// 3	We are waiting for L2 to finish the refill
assign l2Miss_v2 = ((dataHere_s2 && ~tagMatch_v2 && rwState_s2 == `READ) ||
		(dataHere_s2 && L2Valid_s2 == `FALSE && rwState_s2 == `READ) ||
		(mainState_s2 == `MISSED_L2 && 
		(busState_s2 == `NOT_GRANTED || reqBus_s2 == `TRUE)));

//
// Synch Reset
//
always @(Phi2 or Reset_ww) begin
    if (Phi2) `TICK Reset_s1 = Reset_ww;
end

//
// The following are only to get synopsys to do the right thing. The register
// needs to have a value assigned to it dynamically
//
always @(Phi1) begin
    if (Phi1) `TICK ratio_s2 = `RATIO;
end
always @(Phi1 or Phi2) begin
    if (Phi2) begin
	`TICK
	rate_s1 = `RATE;
	latency_s1 = `LATENCY;
    end
end

//
// 
//
always @(Phi1 or l2Miss_s1 or ReqBus_s1 or Reset_s1) begin
    if (Phi1) begin
	`TICK
	if (Reset_s1) begin
	    l2Miss_s2 = `FALSE;
	    reqBus_s2 = `FALSE;
	end
    	else begin
	    l2Miss_s2 = l2Miss_s1;
	    reqBus_s2 = ReqBus_s1;	    
	end
    end
end

//
// If starting a new request, then reset length field.
// After sent/received data decrement the length of request counter.
// Otherwise leave length field unchanged
//
assign reqLength_v1 = reqLength_NS(mainState_s1, extRequest_s1, ReqLength_s1,
				   reqLength_s1, nextData_s1, Reset_s1);
function [2:0] reqLength_NS;
    input [2:0] mainState_s1;
    input       extRequest_s1;
    input [5:0] ReqLength_s1;
    input [2:0] reqLength_s1;
    input [2:0] nextData_s1;
    input       Reset_s1;
begin: _reqLength_NS
    if (Reset_s1) begin
	reqLength_NS = 1'b0;
    end
    else if (mainState_s1 == `IDLE & extRequest_s1 == 1'b1) begin
	reqLength_NS = ReqLength_s1[5:3];
    end
    else if (nextData_s1 == 3'b1) begin
	reqLength_NS = reqLength_s1 - 3'b1;
    end
    else begin
	reqLength_NS = reqLength_s1;
    end
end
endfunction // reqLength_NS
	
//
// If starting a new request then reset number of addresses_to_send field
// After sent addr decrement the number of addresses counter.
// Otherwise leave number of addresses remaining to be sent unchanged.
//
assign numberAddrs_v1 = numberAddrs_NS(mainState_s1, extRequest_s1,
				       ReqLength_s1, nonCacheable_s1,
				       driveAddr_s1, numberAddrs_s1, Reset_s1);
function [2:0] numberAddrs_NS;
    input [2:0] mainState_s1;
    input       extRequest_s1;
    input [5:0] ReqLength_s1;
    input       nonCacheable_s1;
    input       driveAddr_s1;
    input [2:0] numberAddrs_s1;
    input       Reset_s1;
begin: _numberAddrs_NS
    if (Reset_s1) begin
	numberAddrs_NS = 3'b0;
    end
    else if (mainState_s1 == `IDLE && extRequest_s1 == 1'b1) begin
	numberAddrs_NS = (ReqLength_s1[5:3] - 3'b1) & {3{~nonCacheable_s1}};
    end
    else if (driveAddr_s1) begin
	numberAddrs_NS = numberAddrs_s1 - 3'b1;
    end
    else begin
	numberAddrs_NS = numberAddrs_s1;
    end
end
endfunction // numberAddrs_NS
	
//------------------------------------------------------------------------
//                      ---- State Machine ----
//------------------------------------------------------------------------
assign mainState_v1 = mainState_NS1(mainState_s1, ExtRequest_s1,
				   NonCacheable_s1, DExtRequest_s1,
				   DNonCacheable_s1, ReqICache_s1,
				   ReqDCache_s1, extRequest_s1, rwState_s1,
				   busState_s1, ReqBus_s1, Reset_s1);
function [2:0] mainState_NS1;
    input [2:0] mainState_s1;
    input       ExtRequest_s1;
    input       NonCacheable_s1;
    input       DExtRequest_s1;
    input       DNonCacheable_s1;
    input       ReqICache_s1;
    input       ReqDCache_s1;
    input       extRequest_s1;
    input       rwState_s1;
    input       busState_s1;
    input       ReqBus_s1;
    input       Reset_s1;
begin: _mainState_NS1
    if (Reset_s1) begin
	mainState_NS1 = `IDLE;
    end
    else begin
      case (mainState_s1)		// synopsys parallel_case full_case
	`IDLE: begin		// IDLE state
	    if ((ExtRequest_s1 == 1'b1 && NonCacheable_s1 == `TRUE) ||
	      (DExtRequest_s1 == 1'b1 && DNonCacheable_s1 == `TRUE)) begin
		mainState_NS1 = `NON_CACHE;
	    end
	    else if ((ExtRequest_s1 == 1'b1 && NonCacheable_s1 == `FALSE) ||
	    (DExtRequest_s1 == 1'b1 && DNonCacheable_s1 == `FALSE)) begin
		mainState_NS1 = `MISS;
	    end
	    else if ((ReqICache_s1 == 1'b1 || ReqDCache_s1 == 1'b1) &&
	      extRequest_s1 != 1'b1) begin
		mainState_NS1 = `EXT_REQST;
	    end
	    else begin
		mainState_NS1 = mainState_s1;
	    end
	end
	`MISS: begin		    	// MISS state
	    mainState_NS1 = mainState_s1;	// next state is still MISS
	end
	`NON_CACHE: begin		// NON_CACHE state
	    if (rwState_s1 == `READ && 
	      (busState_s1 == `NOT_GRANTED || ReqBus_s1 == `TRUE)) begin
		mainState_NS1 = mainState_s1;
	    end
	    else begin
		mainState_NS1 = `IDLE;
	    end
	end
	`EXT_REQST: begin		// EXT_REQST state
	    mainState_NS1 = mainState_s1;
	end
	`MISSED_L2: begin
	    if (ReqBus_s1 == `FALSE && busState_s1 == `GRANTED) begin
		mainState_NS1 = `IDLE;
	    end
	    else begin
		mainState_NS1 = mainState_s1;	
	    end
	end
	default:
	    mainState_NS1 = mainState_s1;
      endcase
    end
end
endfunction // mainState_NS1


//
// On a new request or when latching addr load addr counter with rate - 1.
// Otherwise decrement the old value.
//
assign nextAddr_v1 = nextAddr_NS1(driveAddr_s1, rate_s1, numberAddrs_s1,
				  nextAddr_s1, mainState_s1, extRequest_s1,
				  Reset_s1);
function [4:0] nextAddr_NS1;
    input       driveAddr_s1;
    input [4:0] rate_s1;
    input [2:0] numberAddrs_s1;
    input [4:0] nextAddr_s1;
    input [2:0] mainState_s1;
    input       extRequest_s1;
    input       Reset_s1;
begin: _nextAddr_NS1
    if (Reset_s1) begin
	nextAddr_NS1 = 5'd0;
    end
    else if ((mainState_s1 == `IDLE && extRequest_s1 == 1'b1)
	|| driveAddr_s1) begin
	nextAddr_NS1 = rate_s1 - 5'd1;		// load counter on new request
    end						// and after latching addr
    else if (numberAddrs_s1 != 3'b0) begin
	nextAddr_NS1 = nextAddr_s1 - 5'd1;	// decrement addr counters
    end
    else begin
	nextAddr_NS1 = nextAddr_s1;
    end
end
endfunction // nextAddr_NS1


//
// If a new request, then reset data counter to the latency value. If
// counter reached zero, then reload with rate. Otherwise keep the old
// value.
//
assign nextData_v1 = nextData_NS1(mainState_s1, extRequest_s1, latency_s1,
				  rate_s1, nextData_s1, Reset_s1);
function [2:0] nextData_NS1;
    input [2:0] mainState_s1;
    input       extRequest_s1;
    input [4:0] latency_s1;
    input [4:0] rate_s1;
    input [2:0] nextData_s1;
    input       Reset_s1;
begin: _nextData_NS1
    if (Reset_s1) begin
	nextData_NS1 = 3'b0;
    end
    else if (mainState_s1 == `IDLE && extRequest_s1 == 1'b1) begin
	nextData_NS1 = latency_s1;
    end
    else if (nextData_s1 == 3'b1) begin
	nextData_NS1 = rate_s1;
    end
    else begin
	nextData_NS1 = nextData_s1 - 3'b1;
    end
end
endfunction // nextData_NS1

assign rwState_v1 = rwState_NS1(mainState_s1, ExtRequest_s1, ExtRead_s1,
				DExtRequest_s1, DExtRead_s1, rwState_s1,
				Reset_s1);
function rwState_NS1;
    input [2:0] mainState_s1;
    input       ExtRequest_s1;
    input       ExtRead_s1;
    input       DExtRequest_s1;
    input       DExtRead_s1;
    input       rwState_s1;
    input       Reset_s1;
begin: _rwState_NS1
    if (Reset_s1) begin
	rwState_NS1 = `FALSE;
    end
    else if (mainState_s1 == `IDLE && ExtRequest_s1 == 1'b1) begin // FS: !!
	rwState_NS1 = ExtRead_s1;
    end
    else if (mainState_s1 == `IDLE && DExtRequest_s1 == 1'b1) begin
	rwState_NS1 = DExtRead_s1;
    end
    else if (mainState_s1 == `IDLE) begin
	rwState_NS1 = `FALSE;
    end
    else begin
	rwState_NS1 = rwState_s1;
    end
end
endfunction // rwState_NS1

//
// Keep the Read/Write state for one cycle, to drive pads
//
always @(Phi1 or rwState_s1) begin
    if (Phi1) `TICK prevRwState_s2 = rwState_s1;
end
always @(Phi2 or prevRwState_s2) begin
    if (Phi2) `TICK prevRwState_s1 = prevRwState_s2;
end

//
// Determine if need to grant bus or not
//
assign busState_v1 = busState_NS1(mainState_s1, ReqBus_s1);
function busState_NS1;
    input [2:0] mainState_s1;
    input       ReqBus_s1;
begin: _busState_NS1
    if (ReqBus_s1 == `TRUE && (mainState_s1 ==`IDLE ||
    mainState_s1 ==`NON_CACHE || mainState_s1 == `MISSED_L2)) begin
	busState_NS1 = `GRANTED;
    end
    else begin
	busState_NS1 = `NOT_GRANTED;
    end
end
endfunction // busState_NS1

//
// Determine if data is back from the outside world
//
assign extDataReady_v1 = extDataReady_NS1(mainState_s1, nextData_s1,
					  rwState_s1, busState_s1, ReqBus_s1);
function extDataReady_NS1;
    input [2:0] mainState_s1;
    input [2:0] nextData_s1;
    input       rwState_s1;
    input       busState_s1;
    input       ReqBus_s1;
begin: _extDataReady_NS1
    if ((mainState_s1 == `MISS && nextData_s1 == 3'b1 && 
	    rwState_s1 == `READ) ||
        (mainState_s1 == `NON_CACHE && rwState_s1 == `READ && busState_s1 ==
	    `GRANTED && ReqBus_s1 == `FALSE)) begin
	extDataReady_NS1 = `TRUE;
    end
    else begin
	extDataReady_NS1 = `FALSE;
    end
end
endfunction // extDataReady_NS1

//
// Latch all of the v1 signals, i.e. the next_state functions.
//
always @(Phi1 or reqLength_v1 or numberAddrs_v1 or mainState_v1 or
	nextAddr_v1 or nextData_v1 or rwState_v1 or busState_s1 or
	extDataReady_v1 or busState_v1) begin
    if (Phi1) begin
	`TICK
	reqLength_s2 = reqLength_v1;
	numberAddrs_s2 = numberAddrs_v1;
	mainState_s2 = mainState_v1;
	nextAddr_s2 = nextAddr_v1;
	nextData_s2 = nextData_v1;
	rwState_s2 = rwState_v1;
	busState_s2 = busState_v1;
	extDataReady_s2 = extDataReady_v1;
    end
end

//
// These signals have no logic on the second phase
//
always @(Phi2 or rwState_s2 or busState_s2 or numberAddrs_s2 or 
	l2Miss_v2 or nextData_s2 or nextAddr_s2 or reqLength_s2) begin
    if (Phi2) begin
	`TICK
	rwState_s1 = rwState_s2;
	busState_s1 = busState_s2;
	numberAddrs_s1 = numberAddrs_s2;
	l2Miss_s1 = l2Miss_v2;
	nextData_s1 = nextData_s2;
	nextAddr_s1 = nextAddr_s2;
	reqLength_s1 = reqLength_s2;
    end
end

//
// This is the main state machine. Generate the v2 signals
//
assign mainState_v2 = mainState_NS2(mainState_s2, nextData_s2, nextAddr_s2,
				    reqLength_s2, rwState_s2, numberAddrs_s2,
				    dataHere_s2, tagMatch_v2, L2Valid_s2,
				    done_s2);
function [2:0] mainState_NS2;
    input [2:0] mainState_s2;
    input [2:0] nextData_s2;
    input [4:0] nextAddr_s2;
    input [2:0] reqLength_s2;
    input       rwState_s2;
    input [2:0] numberAddrs_s2;
    input       dataHere_s2;
    input       tagMatch_v2;
    input       L2Valid_s2;
    input       done_s2;
begin: _mainState_NS2
    case (mainState_s2)			// synopsys parallel_case full_case
    	`IDLE: begin			// IDLE state
	    mainState_NS2 = mainState_s2;
    	end
    	`MISS: begin			// MISS state
	    if ((reqLength_s2 == 3'b000 && rwState_s2 == `READ) ||
		(numberAddrs_s2 == 3'b000 && rwState_s2 == `WRITE)) begin
		mainState_NS2 = `IDLE;
	    end
	    else if (dataHere_s2 && (~tagMatch_v2 || L2Valid_s2 == 1'b0) &&
		(rwState_s2 == `READ)) begin
		mainState_NS2 = `MISSED_L2;
	    end
    	    else begin
	    	mainState_NS2 = mainState_s2;
	    end
	end
    	`NON_CACHE: begin		// NON_CACHE state
	    mainState_NS2 = mainState_s2;
    	end
    	`EXT_REQST: begin		// EXT_REQST state
	    if(done_s2 == 1'b1) begin
		mainState_NS2 = `IDLE;
    	    end
    	    else begin
		mainState_NS2 = mainState_s2;
    	    end
    	end
    	`MISSED_L2: begin
	    mainState_NS2 = mainState_s2;
    	end
	default:
	    mainState_NS2 = mainState_s2;
    endcase
end
endfunction // mainState_NS2

//
// Now latch the value of the next state function
//
always @(Phi2 or mainState_v2) begin
    if (Phi2) `TICK mainState_s1 = mainState_v2;
end

endmodule				  // extIntControl
