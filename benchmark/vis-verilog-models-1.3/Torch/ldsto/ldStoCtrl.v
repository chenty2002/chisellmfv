// Adapted to vl2mv/vis by Fabio Somenzi <Fabio@Colorado.EDU>
//
// The changes are:
//
// 1. Redefined TICK to the empty string.
// 2. Added initial block.
// 3. Made Phi2 the complement of Phi1.
// 4. Turned "convenience" registers into wires.  This entails
//    rewriting the next state logic of the three FSMs as functions.

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
//  Title: 	Load Store Unit Control Module
//  Created:	Thu May 19 22:10:40 1994
//  Author: 	Ricardo E. Gonzalez
//		<ricardog@chroma>
//
//
//  ldStoCtrl.v,v 1.48 1995/08/12 00:08:21 ricardog Exp
//
//  TORCH Research Group.
//  Stanford University.
//	1994.
//
//	Description: 
//
//	Hierarchy: 
//
//  Revision History:
//	Modified: Mon Jun 27 17:02:59 1994	<ricardog@chroma.Stanford.EDU>
//	* Runs some tests without icache init
//	Modified: Mon Jun 27 15:18:41 1994	<ricardog@chroma.Stanford.EDU>
//	* Qualified dTagRead_s1 with IStall_s1
//	Modified: Mon Jun  6 12:54:57 1994	<ricardog@chroma.Stanford.EDU>
//	* Cache miss on a fast store working.
//	Modified:	Thu Jun  2 13:48:34 1994	<ricardog@chroma>
//	* Generates excpetion on boosted conflict.
//
`include "torch.h"

//
// States for cache conflict state machine
//
`define CONF_Idle		2'b11
`define CONF_Store		2'b10
`define CONF_Commit		2'b01
`define CONF_Retry		2'b00

//
// States for ext requests state machine
//
`define EXT_Idle		4'b0000
`define EXT_Store		4'b0001
`define EXT_Probe		4'b0010
`define EXT_DrvSpillAddr	4'b0011
`define EXT_WaitXfer		4'b0100
`define EXT_DrvData		4'b0101
`define EXT_Wait1		4'b0110
`define EXT_DrvFillAddr		4'b0111
`define EXT_XferData		4'b1000
`define EXT_Wait2		4'b1001
`define EXT_Retry		4'b1010
`define EXT_NonCacheR		4'b1011
`define EXT_NonCacheW		4'b1100
`define EXT_NonCacheWait	4'b1101
`define EXT_NonCacheDrv		4'b1110
`define EXT_NonCachePause	4'b1111

//
// Pending store
//
`define STO_Idle		3'b000
`define STO_BProbe		3'b001
`define STO_FProbe		3'b010
`define STO_Pending		3'b011
`define STO_FMissed		3'b100
`define STO_BMissed		3'b101

module ldStoCtrl (
// Clocks
    Phi1,
		  // Phi2 is derived from Phi1 by inversion
//  Phi2,
// Inputs
    MemOp_s1m,
    byteOffset_s1m,
    InstrIsLoad_s1m,
    InstrIsStore_s1m,
    BoostedInstr_s1m,
    HLNotReady_s2e,
    NonCacheable_v2m,
    IStall_s1,
    IFetchStall_s1,
    valid_v2m,
    match_v2m,
    cacheConflict_v1m,
    dirty_v2m,
    cacheBusSignBits_v2m,
    stoBufferEmpty_s1,
    stoBufferStall_s1m,
    SeqConflict_v1m,
    lineOffset_s1w,
    missOp_s1w,
    ExtDataValid_s2,
    L2Miss_s2,
    Except_s1w,
    Reset_s1,
// Outputs
    Stall_s1,
    Stall_s2,
    MemStall_s1,
    valid_s1m,
    dTagRead_s1m,
    dTagWrite_s1m,
    latchStore_s1w,
    dTagIsLoad_s1m,
    dTagIsLoad_s2m,
    dCacheRead_s1m,
    dCacheWrite_s1m,
    dirty_s1,
    latchExtData_s2,
    dCacheIsLoad_s1m,
    dCacheIsStore_s1,
    selFastStore_s1m,
    latchCacheData_s1,
    drvSharedMemData_q2,
    doBufferStore_s1m,
    popStoreBuffer_s1,
    dCacheFill_s1,
    MemOp_s2m,
    byteOffset_s2m,
    lineOffset_s1,
    missOp_s1,
    latchMissAddr_s2,
    drvSharedMemAddr_q1,
    pendStore_s2,
    selStoreAddr_s1,
    selMissAddr_s1,
    selProbeAddr_s1,
    selSpillAddr_s1,
    selBuffMissAddr_s1,
    selByte1Pass_s2m,
    selByte1One_v2m,
    selByte1Zero_v2m,
    selByte23Pass_s2m,
    selByte23One_v2m,
    selByte23Zero_v2m,
    drvMemBusCD_q2m,
    drvMemBusSMD_q2m,
    drvNonCache_q2m,
    DExtRequest_s1,
    DExtRead_s1,
    DNonCacheable_s1,
    ReqLength_s1
    );

//
// Clocks & Stall & Reset
//
input		Phi1;
wire		Phi2;
input		IStall_s1;
input		IFetchStall_s1;
input		Reset_s1;
input		Except_s1w;
output		Stall_s1;
output		Stall_s2;
output		MemStall_s1;

//
// Control Inputs (from instr decoder)
//
input	[2:0]	MemOp_s1m;
input	[1:0]	byteOffset_s1m;
input		InstrIsLoad_s1m;
input		InstrIsStore_s1m;
input		BoostedInstr_s1m;

//
// Interface with dTagDatapath
//
input		valid_v2m;
input		match_v2m;
input		cacheConflict_v1m;
output		valid_s1m;
output		dTagRead_s1m;
output		dTagWrite_s1m;
output		latchStore_s1w;
output		dTagIsLoad_s1m;
output		dTagIsLoad_s2m;

//
// Interface to dCacheDatapath
//
input		dirty_v2m;
input	[1:0]	cacheBusSignBits_v2m;
output		dCacheRead_s1m;
output		dCacheWrite_s1m;
output		dirty_s1;
output		latchExtData_s2;
output		dCacheIsLoad_s1m;
output		dCacheIsStore_s1;
output		selFastStore_s1m;	 // 
output		latchCacheData_s1;
output		drvSharedMemData_q2;
output		pendStore_s2;		 // Pending store so clock latches

//
// Interface to store buffer
//
input		stoBufferEmpty_s1;
input		stoBufferStall_s1m;	 // commit and store buffer not empty
input		SeqConflict_v1m;
output		doBufferStore_s1m;       // put store in buffer
output		popStoreBuffer_s1;       // take a store from buffer and do it
output		dCacheFill_s1;		 // Do 64b write to dCache
output	[2:0]	MemOp_s2m;

//
// Interface to addrDatapath
//
input	[4:0]	lineOffset_s1w;
input	[2:0]	missOp_s1w;
output	[1:0]	byteOffset_s2m;
output	[4:0]	lineOffset_s1;
output	[2:0]	missOp_s1;
output		latchMissAddr_s2;
output		drvSharedMemAddr_q1;
output		selStoreAddr_s1;
output		selMissAddr_s1;
output		selProbeAddr_s1;
output		selSpillAddr_s1;
output		selBuffMissAddr_s1;

//
// Interface to datapath
//
output		selByte1Pass_s2m;
output		selByte1One_v2m;
output		selByte1Zero_v2m;
output		selByte23Pass_s2m;
output		selByte23One_v2m;
output		selByte23Zero_v2m;
output		drvMemBusCD_q2m;
output		drvMemBusSMD_q2m;
output		drvNonCache_q2m;

//
// Interface to external world
//
input		ExtDataValid_s2;
input		L2Miss_s2;
output		DExtRequest_s1;
output		DExtRead_s1;
output		DNonCacheable_s1;
output	[5:0]	ReqLength_s1;

//
// Hi/Lo Stall
//
input		HLNotReady_s2e;

//
// Exceptions
//
input		NonCacheable_v2m;

//
// State machines registers
//
reg	[3:0]	extState_s1;
reg	[3:0]	extState_s2;
reg	[1:0]	confState_s1;
reg	[1:0]	confState_s2;
reg	[2:0]	storeState_s1;
reg	[2:0]	storeState_s2;

//
// Registers for counters
//
reg	[3:0]	offset_s1;
reg	[3:0]	offset_s2;
reg	[3:0]	saveOffset_s1;

//
// Delayed signals
//
reg		dTagIsLoad_s2m;
reg		dTagRead_s2m;
reg		ExtDataValid_s1;
reg		InstrIsLoad_s2m;
reg		InstrIsStore_s2;
reg	[2:0]	MemOp_s2m;
reg	[1:0]	byteOffset_s2m;
reg		Stall_s2;
reg		nonCacheable_s1;
reg		earlyDrvSMD_s1;
reg		earlyDrvSMD_s2;
//reg		selPhysAddr_s2;		 // delayed version of popStoreBuffer

//
// Convenience signals
//
wire		dCacheMiss_v2m;		  // qualified with I/O stuff
wire		doStore_s1m;
reg		doStore_s2m;
wire		doCacheStore_s1m;
wire		doBufferStore_s1m;
wire	[3:0]	extState_v2;
wire	[1:0]	confState_v1;
wire	[2:0]	storeState_v1;
wire	[2:0]	storeState_v2;
wire		xferDone_s2;
wire		QualInstrIsStore_s1m;	 // Not valid if magical I/O store
wire		QualInstrIsLoad_s1m;	 // Not valid if store buffer stall
reg		QualInstrIsLoad_s2m;	 // Not valid if store buffer stall or
					 //     it is magical load
initial begin
    extState_s1 = 0;
    extState_s2 = 0;
    confState_s1 = 0;
    confState_s2 = 0;
    storeState_s1 = 0;
    storeState_s2 = 0;
    offset_s1 = 0;
    offset_s2 = 0;
    saveOffset_s1 = 0;
    dTagIsLoad_s2m = 0;
    dTagRead_s2m = 0;
    ExtDataValid_s1 = 0;
    InstrIsLoad_s2m = 0;
    InstrIsStore_s2 = 0;
    MemOp_s2m = 0;
    byteOffset_s2m = 0;
    Stall_s2 = 0;
    nonCacheable_s1 = 0;
    earlyDrvSMD_s1 = 0;
    earlyDrvSMD_s2 = 0;
    doStore_s2m = 0;
    QualInstrIsLoad_s2m = 0;
end

    assign Phi2 = ~Phi1;

//---------------------------------------------------------------------------
//			 --- Stall Signals ---
//---------------------------------------------------------------------------
assign Stall_s1 = IStall_s1 | MemStall_s1;
assign MemStall_s1 = (extState_s1 != `EXT_Idle |
			    confState_s1 != `CONF_Idle |
			    stoBufferStall_s1m) &
			 ~Reset_s1;

//---------------------------------------------------------------------------
//			      --- TBI ---
//---------------------------------------------------------------------------

//---------------------------------------------------------------------------
//			 --- dTag Interface ---
//---------------------------------------------------------------------------
assign dTagRead_s1m = ((QualInstrIsLoad_s1m | doStore_s1m) &
			    ~dTagWrite_s1m & ~IStall_s1) |
			(storeState_s1 == `STO_BProbe) |
			(storeState_s1 == `STO_FProbe) |
			(extState_s1 == `EXT_Probe & ~Reset_s1);
assign dTagWrite_s1m = extState_s1 == `EXT_XferData & ExtDataValid_s1;
assign dTagIsLoad_s1m = (QualInstrIsLoad_s1m | selFastStore_s1m) &
			(extState_s1 == `EXT_Idle);
assign valid_s1m = extState_s1 == `EXT_XferData;

//
// Magical I/O stuff is now done one level high in loadStore.v
//
assign dCacheMiss_v2m = dTagRead_s2m & ~(valid_v2m & match_v2m);

always @(Phi1 or dTagIsLoad_s1m) begin
    if (Phi1) `TICK dTagIsLoad_s2m = dTagIsLoad_s1m;
end

always @(Phi1 or dTagRead_s1m) begin
    if (Phi1) `TICK dTagRead_s2m = dTagRead_s1m;
end

//---------------------------------------------------------------------------
//			--- dCache Interface ---
//---------------------------------------------------------------------------
assign dCacheRead_s1m = (QualInstrIsLoad_s1m & (confState_s1 != `CONF_Store &
			    extState_s1 == `EXT_Idle) |
			    (confState_s1 == `CONF_Retry)) |
			(extState_s1 == `EXT_Probe) |
			(extState_s1 == `EXT_DrvSpillAddr) |
			(extState_s1 == `EXT_WaitXfer) |
			(extState_s1 == `EXT_DrvData) |
			((extState_s1 == `EXT_Retry) &
			    (storeState_s1 != `STO_FMissed) &
			    (storeState_s1 != `STO_BMissed));
assign dCacheWrite_s1m = (storeState_s1 == `STO_Pending & ~QualInstrIsLoad_s1m &
			    extState_s1 == `EXT_Idle) |
			(storeState_s1 == `STO_Pending &
			    confState_s1 == `CONF_Store &
			    extState_s1 == `EXT_Idle) |
			(extState_s1 == `EXT_Store &
			    storeState_s1 == `STO_Pending) |
			(((extState_s1 == `EXT_XferData) |
			    (extState_s1 == `EXT_Wait2)) & ExtDataValid_s1) |
			((extState_s1 == `EXT_Retry) &
			    (storeState_s1 == `STO_FMissed |
			     storeState_s1 == `STO_BMissed));
assign dirty_s1 = ~(extState_s1 == `EXT_XferData);
assign latchStore_s1w = doStore_s1m;
assign latchExtData_s2 = ExtDataValid_s2;
assign dCacheIsLoad_s1m = QualInstrIsLoad_s1m & (confState_s1 == `CONF_Idle &
			    extState_s1 == `EXT_Idle);
assign dCacheIsStore_s1 = (storeState_s1 == `STO_Pending & ~QualInstrIsLoad_s1m&
				(extState_s1 == `EXT_Idle |
				 extState_s1 == `EXT_Store)) |
			    (confState_s1 == `CONF_Store) |
			    ((extState_s1 == `EXT_Retry) &
				(storeState_s1 == `STO_FMissed |
				 storeState_s1 == `STO_BMissed));
assign selFastStore_s1m = QualInstrIsStore_s1m &
			    (stoBufferEmpty_s1 & ~BoostedInstr_s1m);
assign drvSharedMemData_q2 = (extState_s2 == `EXT_DrvSpillAddr |
			    earlyDrvSMD_s2) & Phi2;
assign pendStore_s2 = (storeState_s2 == `STO_FProbe ||
			    storeState_s2 == `STO_BProbe ||
			    storeState_s2 == `STO_Pending);
assign latchCacheData_s1 = earlyDrvSMD_s1 || extState_s1 == `EXT_DrvSpillAddr;

//---------------------------------------------------------------------------
//			 --- Addr Datapath ---
//---------------------------------------------------------------------------
assign drvSharedMemAddr_q1 = (extState_s1 == `EXT_DrvSpillAddr |
			    extState_s1 == `EXT_DrvFillAddr |
			    extState_s1 == `EXT_NonCacheW |
			    extState_s1 == `EXT_NonCacheR) & Phi1;
assign selStoreAddr_s1 = extState_s1 == `EXT_Idle &
			(confState_s1 == `CONF_Idle |
			    confState_s1 == `CONF_Store);
assign selMissAddr_s1 =  extState_s1 != `EXT_Idle |
			confState_s1 == `CONF_Retry;
assign selProbeAddr_s1 = 1'b0;		  // TBI
assign selSpillAddr_s1 = extState_s1 == `EXT_DrvSpillAddr;
assign selBuffMissAddr_s1 = storeState_s1 == `STO_BMissed;
// I will leave this signal as a rename of Stall_s2 in case I later realize
// that it is incorrect and the condition needs further qualification.
assign latchMissAddr_s2 = ~Stall_s2;

//---------------------------------------------------------------------------
//			  --- Sign Extend ---
//---------------------------------------------------------------------------
assign selByte1Pass_s2m = MemOp_s2m[1] | MemOp_s2m[0];
assign selByte1One_v2m = MemOp_s2m == 3'd0 & cacheBusSignBits_v2m[0];
assign selByte1Zero_v2m = MemOp_s2m == 3'd4 |
			(MemOp_s2m == 3'd0 & ~cacheBusSignBits_v2m[0]);
assign selByte23Pass_s2m = MemOp_s2m == 3'd3;
assign selByte23One_v2m = MemOp_s2m == 3'd0 & cacheBusSignBits_v2m[0] | 
				MemOp_s2m == 3'd1 & cacheBusSignBits_v2m[1];
assign selByte23Zero_v2m = MemOp_s2m == 3'd4 | MemOp_s2m == 3'd5 |
			(MemOp_s2m == 3'd0 & ~cacheBusSignBits_v2m[0]) |
			(MemOp_s2m == 3'd1 & ~cacheBusSignBits_v2m[1]);

//---------------------------------------------------------------------------
//			    --- Datapath ---
//---------------------------------------------------------------------------
assign drvMemBusCD_q2m = QualInstrIsLoad_s2m &
			extState_s2 != `EXT_NonCacheWait & Phi2;
assign drvMemBusSMD_q2m = (extState_s2 == `EXT_NonCacheWait) & Phi2;
assign drvNonCache_q2m = (extState_s2 == `EXT_NonCacheW) & Phi2;

//---------------------------------------------------------------------------
//			   --- Cache Miss ---
//---------------------------------------------------------------------------
assign DExtRequest_s1 = extState_s1 == `EXT_DrvSpillAddr |
			extState_s1 == `EXT_DrvFillAddr |
			extState_s1 == `EXT_NonCacheR |
			extState_s1 == `EXT_NonCacheW;
assign DExtRead_s1 = extState_s1 == `EXT_DrvFillAddr |
			extState_s1 == `EXT_NonCacheR;
assign DNonCacheable_s1 = nonCacheable_s1;	  // TBI
assign ReqLength_s1 = (extState_s1 == `EXT_DrvSpillAddr |
			    extState_s1 == `EXT_DrvFillAddr) ? 6'd32 :
			(extState_s1 == `EXT_NonCacheR |
			    extState_s1 == `EXT_NonCacheW) ? 6'd4 : 6'bz;

assign dCacheFill_s1 = extState_s1 == `EXT_XferData;

assign xferDone_s2 = offset_s2[2];	 // Count up to 4 for 32B lines
assign lineOffset_s1 = (extState_s1 == `EXT_Probe |
			    extState_s1 == `EXT_DrvSpillAddr |
			    extState_s1 == `EXT_XferData |
			    extState_s1 == `EXT_DrvData) ?
			{saveOffset_s1[1:0], 3'b0} : lineOffset_s1w;

assign missOp_s1 = (extState_s1 != `EXT_Idle && extState_s1 != `EXT_Retry) ?
			3'b010 : missOp_s1w;

always @(Phi2 or offset_s2) begin
    if (Phi2) `TICK saveOffset_s1 = offset_s2;
end

always @(Phi1 or Reset_s1 or offset_s1) begin
    if (Phi1) `TICK offset_s2 = (Reset_s1) ? 4'd0 : offset_s1;
end

always @(Phi2 or offset_s2 or extState_s2 or ExtDataValid_s2) begin
    if (Phi2) begin
	`TICK
	if (extState_s2 == `EXT_DrvSpillAddr)
	    offset_s1 = 4'd1;
	else if (extState_s2 == `EXT_DrvFillAddr)
	    offset_s1 = 4'd0;
	else 
	    offset_s1 = (ExtDataValid_s2) ? offset_s2 + 4'd1 : offset_s2;
    end
end

//---------------------------------------------------------------------------
//			  --- Store Buffer ---
//---------------------------------------------------------------------------
//
// Pop something off the store buffer when there is nothing using the
// cache (load, cache miss, non-cacheable op). Can pop something
// on the MEM stage of a store, since that store goes into the buffer,
// i.e. I assume I can push and pop something on the same cycle.
// 
assign popStoreBuffer_s1 = ~stoBufferEmpty_s1 &
			    (storeState_s1 != `STO_FMissed) &
			    (storeState_s1 != `STO_BMissed) &
			    ((~QualInstrIsLoad_s1m & ~IStall_s1 &
				    extState_s1 == `EXT_Idle) |
				confState_s1 == `CONF_Store |
				stoBufferStall_s1m);

assign QualInstrIsLoad_s1m = InstrIsLoad_s1m & ~stoBufferStall_s1m;

//---------------------------------------------------------------------------
//			 --- Pending Store ---
//---------------------------------------------------------------------------
// If store buffer is not empty then put the store in the buffer
// and retire something from the store buffer. Also need to keep 
// track of half-completed, i.e. pending store.
//
assign doCacheStore_s1m = (selFastStore_s1m | 
			(~InstrIsLoad_s1m & ~stoBufferEmpty_s1)) & ~Stall_s1;
assign doBufferStore_s1m = QualInstrIsStore_s1m & 
			(~stoBufferEmpty_s1 | BoostedInstr_s1m) & ~Stall_s1;
assign doStore_s1m = doCacheStore_s1m | popStoreBuffer_s1;

//
// A couple of random notes on stores.
//
// A pending store has gone through the tag check and was already
// sent to the data cache but is waiting for a cycle when the 
// cache is not busy. If there is a non-cacheable op then wait for
// that to finish before committing store. In fact only commit store
// when the main state machine is in the Idle or Store states.
// NOTE: This means the consistency is all screwed-up, but who cares.
// non-cacheable stuff is not coherent anyway.
// 
// A fast store never goes through the store buffer. This can only
// happen if the buffer is empty and the store is not boosted.
//
    assign storeState_v1 = storeNS1(doStore_s1m, storeState_s1, Reset_s1,
				    InstrIsLoad_s1m, selFastStore_s1m,
				    Stall_s1, popStoreBuffer_s1, Except_s1w,
				    QualInstrIsLoad_s1m, extState_s1);

    function [2:0] storeNS1;
	input       doStore_s1m;
	input [2:0] storeState_s1;
	input       Reset_s1;
	input       InstrIsLoad_s1m;
	input       selFastStore_s1m;
	input	    Stall_s1;
	input	    popStoreBuffer_s1;
	input	    Except_s1w;
	input	    QualInstrIsLoad_s1m;
	input	    extState_s1;
	begin: _storeNS1
	    if (Reset_s1) storeNS1 = `STO_Idle;
	    else begin
		case (storeState_s1)
		  `STO_Idle:
		    storeNS1 = (selFastStore_s1m & ~Stall_s1) ?
			       `STO_FProbe : (popStoreBuffer_s1) ?
			       `STO_BProbe : storeState_s1;
		  `STO_BProbe:
		    storeNS1 = storeState_s1;
		  `STO_FProbe:
		    storeNS1 = (Except_s1w) ? `STO_Idle : storeState_s1;
		  `STO_FMissed:
		    storeNS1 = storeState_s1;
		  `STO_BMissed:
		    storeNS1 = storeState_s1;
		  `STO_Pending:
		    storeNS1 = (QualInstrIsLoad_s1m) ? storeState_s1 : 
				    (extState_s1 != `EXT_Idle &&
				     extState_s1 != `EXT_Store) ?
				    storeState_s1 :
				    (selFastStore_s1m & ~Stall_s1) ?
				    `STO_FProbe : (popStoreBuffer_s1) ?
				    `STO_BProbe : `STO_Idle;
		  default:
		    storeNS1 = storeState_s1;
		endcase
	    end
	end
    endfunction // storeNS1
    

    always @(Phi1 or storeState_v1) begin
	if (Phi1) `TICK storeState_s2 = storeState_v1;
    end

    assign storeState_v2 = storeNS2(storeState_s2, dCacheMiss_v2m,
				    extState_s2, NonCacheable_v2m);

    function [2:0] storeNS2;
	input [2:0] storeState_s2;
	input	    dCacheMiss_v2m;
	input	    extState_s2;
	input	    NonCacheable_v2m;
	begin: _storeNS2
	    case(storeState_s2)
	      `STO_Idle:
		storeNS2 = storeState_s2;
	      `STO_BProbe:
		storeNS2 = (NonCacheable_v2m) ? `STO_Idle :
			   (dCacheMiss_v2m) ? `STO_BMissed : `STO_Pending;
	      `STO_FProbe:
		storeNS2 = (NonCacheable_v2m) ? `STO_Idle :
			   (dCacheMiss_v2m) ? `STO_FMissed : `STO_Pending;
	      `STO_FMissed:
		storeNS2 = (extState_s2 == `EXT_Retry) ? `STO_Idle :
			   storeState_s2;
	      `STO_BMissed:
		storeNS2 = (extState_s2 == `EXT_Retry) ? `STO_Idle :
			   storeState_s2;
	      `STO_Pending:
		storeNS2 = storeState_s2;
	      default:
		storeNS2 = storeState_s2;
	    endcase
	end
    endfunction // storeNS2

    always @(Phi2 or storeState_v2) begin
	if (Phi2) `TICK storeState_s1 = storeState_v2;
    end

//---------------------------------------------------------------------------
//			 --- Cache Conflict ---
//---------------------------------------------------------------------------
//
// Need to stall the machine when there is a conflict in the
// sequential store buffer (sequential load and sequential store)
// or a load conflicts with a pending store. We detect a conflict
// by looking at the line address of the op. This limits the
// comparator to 12-bits or so, at the expense of some inaccuracy.
// Need to deal with cache misses and stuff (at least for store
// buffer conflicts).
//
    assign confState_v1 = confNS1(confState_s1, Reset_s1, SeqConflict_v1m,
				  cacheConflict_v1m, stoBufferEmpty_s1,
				  storeState_s1, QualInstrIsLoad_s1m,
				  IStall_s1, InstrIsLoad_s1m,
				  stoBufferStall_s1m);

    function [1:0] confNS1;
	input [1:0] confState_s1;
	input	    Reset_s1;
	input	    SeqConflict_v1m;
	input	    cacheConflict_v1m;
	input	    stoBufferEmpty_s1;
	input [2:0] storeState_s1;
	input	    QualInstrIsLoad_s1m;
	input	    IStall_s1;
	input	    InstrIsLoad_s1m;
	input	    stoBufferStall_s1m;
	begin: _confNS1
	    if (Reset_s1) confNS1 = `CONF_Idle;
	    else begin
		case(confState_s1)
		  `CONF_Idle:
		    confNS1 = (QualInstrIsLoad_s1m & ~IStall_s1 &
			       (SeqConflict_v1m |
				(cacheConflict_v1m & storeState_s1 ==
				 `STO_Pending))) ? `CONF_Store : 
			      (InstrIsLoad_s1m & stoBufferStall_s1m) ?
			      `CONF_Commit : `CONF_Idle;
		  `CONF_Store:
		    confNS1 = (stoBufferEmpty_s1 & storeState_s1 !=
			       `STO_BMissed) ? `CONF_Retry : `CONF_Store;
		  `CONF_Retry:
		    confNS1 = `CONF_Idle;
		  `CONF_Commit:
		    confNS1 = (stoBufferStall_s1m) ? `CONF_Commit :
			      `CONF_Retry;
		  default:
		    confNS1 = confState_s1;
		endcase
	    end
	end
    endfunction // confNS1

    always @(Phi2 or confState_s2) begin
	if (Phi2) `TICK confState_s1 = confState_s2;
    end

    always @(Phi1 or confState_v1) begin
	if (Phi1) `TICK confState_s2 = confState_v1;
    end

//---------------------------------------------------------------------------
//			 --- State Machine ---
//---------------------------------------------------------------------------
//
// This is the main state machine it deal with cache misses and
// non-cacheable requests. 
// Currently it transitions on Phi2 based on dCacheMiss_v2m. This is
// probably too optimistic, i.e. it probably would not meet timing.
// Need to latch dCacheMiss_v2m and then transition on the following
// phase. Should not be too hard, just make sure all the signals needed
// are delayed.
// 
// On a miss first do any pending stores. Then probe the cache to see
// if the line is dirty. If it is then spill data out first. When that
// is done then re-fill the line. 

    assign extState_v2 = extNS2(extState_s2, dCacheMiss_v2m, dirty_v2m,
				InstrIsStore_s2, ExtDataValid_s2, xferDone_s2,
				InstrIsLoad_s2m, confState_s2, storeState_s2,
				NonCacheable_v2m, doStore_s2m);

    function [3:0] extNS2;
	input [3:0] extState_s2;
	input	    dCacheMiss_v2m;
	input	    dirty_v2m;
	input	    InstrIsStore_s2;
	input	    ExtDataValid_s2;
	input	    xferDone_s2;
	input	    InstrIsLoad_s2m;
	input [1:0] confState_s2;
	input [2:0] storeState_s2;
	input	    NonCacheable_v2m;
	input	    doStore_s2m;
	begin: _extNS2
	    case (extState_s2)
	      `EXT_Idle:
		extNS2 = 
			 (NonCacheable_v2m & InstrIsLoad_s2m) ? `EXT_NonCacheR :
			 (NonCacheable_v2m & doStore_s2m)     ? `EXT_NonCacheW :
			 (~dCacheMiss_v2m)		      ? `EXT_Idle    :
			 (storeState_s2 == `STO_Pending)      ? `EXT_Store   :
			 // (confState_s2 == `CONF_Store)     ? `EXT_Idle    :
			 `EXT_Probe;
	      `EXT_Store:
		extNS2 = `EXT_Probe;
	      `EXT_Probe:
		extNS2 = (dirty_v2m) ? `EXT_DrvSpillAddr : `EXT_DrvFillAddr;
	      `EXT_DrvSpillAddr:
		extNS2 = `EXT_WaitXfer;
	      `EXT_WaitXfer:
		extNS2 = (ExtDataValid_s2) ? `EXT_DrvData : `EXT_WaitXfer;
	      `EXT_DrvData:
		extNS2 = (xferDone_s2) ? `EXT_Wait1 :
			 (ExtDataValid_s2) ? `EXT_DrvData : `EXT_WaitXfer;
	      `EXT_Wait1:
		extNS2 = `EXT_DrvFillAddr;
	      `EXT_DrvFillAddr:
		extNS2 = `EXT_XferData;
	      `EXT_XferData:
		extNS2 = (xferDone_s2) ? `EXT_Retry : `EXT_XferData;
	      `EXT_Wait2:
		extNS2 = `EXT_Retry;
	      `EXT_Retry:
		extNS2 = `EXT_Idle;
	      `EXT_NonCacheW:
		extNS2 = `EXT_NonCachePause;
	      `EXT_NonCacheR:
		extNS2 = `EXT_NonCacheWait;
	      `EXT_NonCacheWait:
		extNS2 = (ExtDataValid_s2) ? `EXT_Idle : extState_s2;
	      `EXT_NonCachePause:
		extNS2 = `EXT_Idle;
	      default:
		extNS2 = extState_s2;
	    endcase
	end
    endfunction // extNS2


    always @(Phi2 or extState_v2) begin
	if (Phi2) `TICK extState_s1 = extState_v2;
    end

    always @(Phi1 or extState_s1 or Reset_s1) begin
	if (Phi1) `TICK extState_s2 = (Reset_s1) ? `EXT_Idle : extState_s1;
    end

//---------------------------------------------------------------------------
//			--- Delayed Signals ---
//---------------------------------------------------------------------------
always @(Phi2 or ExtDataValid_s2 or NonCacheable_v2m or extState_s2) begin
    if (Phi2) begin
	`TICK
	ExtDataValid_s1 = ExtDataValid_s2;
	nonCacheable_s1 = NonCacheable_v2m & extState_s2 == `EXT_Idle;
	earlyDrvSMD_s1 = extState_s2 == `EXT_DrvData;
    end
end

always @(Phi1 or Stall_s1 or QualInstrIsStore_s1m) begin
    if (Phi1 & ~Stall_s1) begin `TICK InstrIsStore_s2 = QualInstrIsStore_s1m;
    end
end

always @(Phi1 or Stall_s1 or MemOp_s1m or InstrIsLoad_s1m or
	byteOffset_s1m) begin
    if (Phi1 & ~Stall_s1) begin
	`TICK
	MemOp_s2m = MemOp_s1m;
	InstrIsLoad_s2m = InstrIsLoad_s1m;
	byteOffset_s2m = byteOffset_s1m;
    end
end

always @(Phi1 or MemStall_s1 or IFetchStall_s1 or earlyDrvSMD_s1
	or doStore_s1m) begin
    if (Phi1) begin
	`TICK
	Stall_s2 = IFetchStall_s1 | MemStall_s1;
	earlyDrvSMD_s2 = earlyDrvSMD_s1;
	doStore_s2m = doStore_s1m;
    end
end

//---------------------------------------------------------------------------
//			  --- Magical I/O ---
//---------------------------------------------------------------------------
always @(Phi1 or Stall_s1 or QualInstrIsLoad_s1m) begin
    if (Phi1 & ~Stall_s1) `TICK QualInstrIsLoad_s2m = QualInstrIsLoad_s1m ;
end

assign QualInstrIsStore_s1m = InstrIsStore_s1m;

endmodule				  // ldStoCtrl
