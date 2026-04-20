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
//  Title: 	TLB Control Module
//  Created:	Fri Apr  1 23:11:11 1994
//  Author: 	Chian-Min Richard Ho
//		<rho@chroma>
//
//
//  tlbControl.v,v 7.28 1995/08/03 22:29:10 ricardog Exp
//
//  TORCH Research Group.
//  Stanford University.
//	1993.
//
//	Description: This modul has two large sections. The first
//		deals with generating control signals for the tlb
//		datapath and the TLB itself. The second section is
//		responsible for initiating requests from the external
//		interface to service cache misses. These fall into two
//		categories, cacheable/non-cacheable. What is done for
//		each type is very different.
//		Normally the L/S address is used for translation,
//		unless there is an I-$ miss in which case the TLB
//		will do the translation and then this modul will take
//		over and initiate an external memory request.
//
//	Hierarchy: 
//
//  Revision History:
//	Modified: Thu Dec  1 21:27:43 1994	<ricardog@chroma.Stanford.EDU>
//	* Added explicit latch enables.
//	Modified: Mon Jun 27 15:15:53 1994	<ricardog@chroma.Stanford.EDU>
//	* Qualified state machine with MemStall to prevent double requests.
//	Modified:	Sun May 22 16:13:10 1994	<ricardog@chroma>
//	* Removed MemStall_s2 for Stall_s1.
//	Modified:	Thu Apr  7 11:51:56 1994	<ricardog@chroma>
//	* Fixed verilint errors.
//	Modified:	Wed Apr  6 19:06:35 1994	<ricardog@chroma>
//	* Added handling of TLB op on I$ miss.
//
`include "torch.h"

module tlbControl(
    Phi1,
//    Phi2,
    MemAddr_s1m,
    instrAddr_s1e, 
    IndexSel_s1m,
    RandomSel_s1m,
    EntryHiSel_s1m,
    EntryLoSel_s1m, 
    IndexSel_s2m,
    RandomSel_s2m,
    EntryHiSel_s2m,
    EntryLoSel_s2m, 
    ICacheMiss_v2r,
    ICacheMiss_s1e,
    drvSharedMemAddr_s1,
    ICMiss_s1, 
    selMemAddr_s1m,
    Except_s1w,
    TLBProbe_s1m,
    TLBRead_s1m, 
    TLBWriteI_s1m,
    TLBWriteR_s1m,
    TLBWrite_s1m,
    TLBWriteOrProbe_s1m, 
    TLBProbe_s1w,
    TLBRead_s1w,
    MvToCop0_s1m,
    MvFromCop0_s1m, 
    InstrIsStore_s1m,
    InstrIsLoad_s1m,
    NonCacheable_v2m,
    Reset_s1, 
    TLBRefill_v2m,
    TLBInvalid_v2m,
    TLBModified_v2m,
    ItlbMiss_v2e, 
    ReqLength_s1,
    MipsMode_s2e,
    ExtDataValid_s2,
    L2Miss_s2,
    IStall_s1,
    MemStall_s1,
    Stall_s1, 
    NonCacheable_s1,
    ExtRead_s1,
    ExtRequest_s1, 
    EntryHi_s2w,
    pid_v2m,
    statusBits_v2m, 
    enabIndexLatch_s1w,
    enabEntryHiLatch_s1w,
    enabEntryLoLatch_s1w,
    TLBTranslation_s1m,
    enabPOLatch_s2m,
    TLBHit_v2m, 
    unCacheOrMap_s2e,
    selSaveInstr_s1,
    enabSaveInstrLatch_s1,
    tlbDrive_v2m,
    randomEqual8_v1,
    resetRandom_v1
    );

//---------------------------------------------------------------------------
// Defines : Bit Assignments for Status Word
//---------------------------------------------------------------------------
`define NonCache	3
`define Dirty		2
`define Valid		1
`define Global		0

//---------------------------------------------------------------------------
// Defines : States for cache miss state machine
//---------------------------------------------------------------------------
`define stage1e_s1      4'd0		  // IDLE state
`define extReq_s1       4'd2		  // send req to ext interface
`define tlbMiss_s1      4'd4		  // Missed in tlb gen except
`define stallRel_s1     4'd6		  // Ifetch released istall
`define L2Miss_s1       4'd8		  // Miss on 2nd level cache
`define tagOp_s1	4'd10		  // tlb operation on miss
`define stage2e_s2      4'd1		  // translation for I$ miss
`define dataVal_s2      4'd3		  // data on it way
`define IStall_s2       4'd5		  // Istall still high on tlb miss
`define stallRel_s2     4'd7		  // IStall is now low
`define tlbMissExcp_s2  4'd9		  // Generate exception now
`define L2Refill_s2     4'd11		  // L2 is now happy
`define idle_s2         4'd13		  // IDLE
`define tlbOp_s2	4'd15		  // second phase of tlb op

//---------------------------------------------------------------------------
// Clocks
//---------------------------------------------------------------------------
input		Phi1;
wire		Phi2;
input		MemStall_s1;
input		Stall_s1;		  // Global stall
input		Reset_s1;
reg		Reset_s2;

//---------------------------------------------------------------------------
// Busses : Address from PC Unit, and to Load/Store Unit
//---------------------------------------------------------------------------
input 	[31:29] MemAddr_s1m;
input	[31:29]	instrAddr_s1e;
reg		restoreAddr_s1, restoreAddr_s2;
output		enabSaveInstrLatch_s1;
wire		enabSaveInstrLatch_s1;

//---------------------------------------------------------------------------
// TLB Register Select Signals
//---------------------------------------------------------------------------
input 		IndexSel_s1m, RandomSel_s1m, EntryHiSel_s1m, EntryLoSel_s1m;
output	 	IndexSel_s2m, RandomSel_s2m, EntryHiSel_s2m, EntryLoSel_s2m;
reg	 	IndexSel_s2m, RandomSel_s2m, EntryHiSel_s2m, EntryLoSel_s2m;
reg 		IndexSel_s1w, EntryHiSel_s1w, EntryLoSel_s1w;

//---------------------------------------------------------------------------
// ICache Miss Signals (from IFetcher, to External Interface)
//---------------------------------------------------------------------------
input		IStall_s1;
input		ICacheMiss_v2r;
output		ICacheMiss_s1e;
output		ICMiss_s1;
reg		ICacheMiss_s1e, ICacheMiss_s1, ICacheMiss_s2;
output		selMemAddr_s1m;
output	[5:0]	ReqLength_s1;
reg		MipsMode_s1m;
output		NonCacheable_s1;
reg		nonCacheable_s1, NonCacheable_s2;
output		ExtRead_s1;
output		ExtRequest_s1;
reg		ExtRequest_s1;
input		ExtDataValid_s2;
input		L2Miss_s2;
input		MipsMode_s2e;

//---------------------------------------------------------------------------
// DCache Miss Request
//---------------------------------------------------------------------------

//---------------------------------------------------------------------------
// TLB Miss on ICache Miss 
//---------------------------------------------------------------------------
output		ItlbMiss_v2e;
reg	[3:0]	tlbState_s1;
reg	[3:0]	tlbState_s2;

//---------------------------------------------------------------------------
// Decoded Op Codes - TLB Instructions
//---------------------------------------------------------------------------
input 		MvToCop0_s1m, MvFromCop0_s1m;
reg		MvToCop0_s2m, MvFromCop0_s2m, MvToCop0_s1w;

input		TLBWriteI_s1m;	      	 // TLB ops decoded in the instr
input		TLBWriteR_s1m;		 // decoder. These are 
output		TLBWrite_s1m;		 // NON-translation operations
output		TLBWriteOrProbe_s1m;	 // To datapath

input 		TLBProbe_s1m;
reg 		TLBProbe_s2m;
output 		TLBProbe_s1w;
reg 		TLBProbe_s1w;

input		TLBRead_s1m;
reg		TLBRead_s2m;
output		TLBRead_s1w;
reg		TLBRead_s1w;

input 		InstrIsLoad_s1m;
reg		InstrIsLoad_s2m;
input		InstrIsStore_s1m;
reg		InstrIsStore_s2m;

//---------------------------------------------------------------------------
// TLB Outputs - Physical Tag, Not_Cacheable bit of entry
//---------------------------------------------------------------------------
output 		NonCacheable_v2m;

//---------------------------------------------------------------------------
// TLB Exception signals
//---------------------------------------------------------------------------
output 		TLBRefill_v2m, TLBInvalid_v2m, TLBModified_v2m;
reg 		TLBRefill_v2m, TLBInvalid_v2m, TLBModified_v2m;
input		Except_s1w;
reg		Except_s2w, Except_s1i;		// _s1i in this case

//---------------------------------------------------------------------------
// CP0 Internal Registers/Signals
//---------------------------------------------------------------------------
input	[11:6]	EntryHi_s2w;
input	[11:6]	pid_v2m;			// PID read from tlb
input	[3:0]	statusBits_v2m;			// Status Bits from tlb
output 		tlbDrive_v2m;			// output enabled
input 		TLBHit_v2m;			// Flag to indicate a hit
wire		tlbValidHit_v2m;
reg	[5:0]	bytesleft_s1,bytesleft_s2;
output		selSaveInstr_s1;
reg	[4:0]	cycles_s1, cycles_s2;
reg		unCacheMap_s2e;			// Instr in uncache space
reg		unMapped_s2e;			// Instr in unmapped space
output		unCacheOrMap_s2e;		// uncached or unmapped space
output		drvSharedMemAddr_s1;		// drv global address bus to EI
input		randomEqual8_v1;
output		resetRandom_v1;

//--------------------------------------------------------------------------
// Wires added for partitioning
//--------------------------------------------------------------------------
wire		enabIndexMvToCop0_s1w;
output		enabIndexLatch_s1w;
wire		enabIndexLatch_s1w;
wire		enabEntryHiMvToCop0_s1w; 
output		enabEntryHiLatch_s1w;
wire		enabEntryHiLatch_s1w;
wire		enabEntryLoMvToCop0_s1w; 
output		enabEntryLoLatch_s1w;
wire		enabEntryLoLatch_s1w;
output		TLBTranslation_s1m;
reg		TLBTranslation_s2m;
wire		enabPhysicalOffset_s2m;
wire		enabPhysicalOffsetPlus2_s2;
output		enabPOLatch_s2m;
wire		enabPOLatch_s2m;

initial begin
    Reset_s2 = 0;
    restoreAddr_s1 = 0;
    restoreAddr_s2 = 0;
    IndexSel_s2m = 0;
    RandomSel_s2m = 0;
    EntryHiSel_s2m = 0;
    EntryLoSel_s2m = 0;
    IndexSel_s1w = 0;
    EntryHiSel_s1w = 0;
    EntryLoSel_s1w = 0;
    ICacheMiss_s1e = 0;
    ICacheMiss_s1 = 0;
    ICacheMiss_s2 = 0;
    MipsMode_s1m = 0;
    nonCacheable_s1 = 0;
    NonCacheable_s2 = 0;
    ExtRequest_s1 = 0;
    tlbState_s1 = 0;
    tlbState_s2 = 0;
    MvToCop0_s2m = 0;
    MvFromCop0_s2m = 0;
    MvToCop0_s1w = 0;
    TLBProbe_s2m = 0;
    TLBProbe_s1w = 0;
    TLBRead_s2m = 0;
    TLBRead_s1w = 0;
    InstrIsLoad_s2m = 0;
    InstrIsStore_s2m = 0;
    TLBRefill_v2m = 0;
    TLBInvalid_v2m = 0;
    TLBModified_v2m = 0;
    Except_s2w = 0;
    Except_s1i = 0;
    bytesleft_s1 = 0;
    bytesleft_s2 = 0;
    cycles_s1 = 0;
    cycles_s2 = 0;
    unCacheMap_s2e = 0;
    unMapped_s2e = 0;
    TLBTranslation_s2m = 0;
end

assign Phi2 = ~Phi1;

//---------------------------------------------------------------------------
// 
//---------------------------------------------------------------------------
assign ItlbMiss_v2e     = (tlbState_s2 == `IStall_s2) |
			     (~(tlbValidHit_v2m | unMapped_s2e) &
				(tlbState_s2 == `stage2e_s2));
assign NonCacheable_v2m = unCacheMap_s2e |
			    (TLBHit_v2m & statusBits_v2m[`NonCache] &
				TLBTranslation_s2m);
assign NonCacheable_s1  = ICacheMiss_s1 & nonCacheable_s1;
assign ExtRead_s1       = ICacheMiss_s1;
assign ReqLength_s1     = (ICacheMiss_s1) ?
                ((NonCacheable_s1) ? 6'd8 : (MipsMode_s1m) ? 6'd32 : 6'd40) :
                6'bz;
assign unCacheOrMap_s2e = unMapped_s2e | unCacheMap_s2e;

//---------------------------------------------------------------------------
// The Exception Signal to Kill the next 2 Move To Cop0
//---------------------------------------------------------------------------
always @(Phi1 or Except_s1w) begin
    if (Phi1) Except_s2w = Except_s1w;
end

always @(Phi2 or Except_s2w) begin
    if (Phi2) Except_s1i = Except_s2w;
end

//---------------------------------------------------------------------------
// Random Register
//---------------------------------------------------------------------------
assign resetRandom_v1 = randomEqual8_v1 | Reset_s1;

//---------------------------------------------------------------------------
// Index Register
//---------------------------------------------------------------------------
assign enabIndexMvToCop0_s1w = MvToCop0_s1w & ~Except_s1w &
	~Except_s1i & IndexSel_s1w;
assign enabIndexLatch_s1w = enabIndexMvToCop0_s1w | TLBProbe_s1w;
//assign useIndex_s1m =  (TLBWriteI_s1m | TLBRead_s1m) &
//		(tlbState_s1 == `stage1e_s1);

//---------------------------------------------------------------------------
// EntryHi Register
//---------------------------------------------------------------------------
assign enabEntryHiMvToCop0_s1w = (MvToCop0_s1w & ~Except_s1w &
	~Except_s1i & EntryHiSel_s1w) | Reset_s1;
assign enabEntryHiLatch_s1w = enabEntryHiMvToCop0_s1w | TLBRead_s1w;

//---------------------------------------------------------------------------
// EntryLo Register
//---------------------------------------------------------------------------
assign enabEntryLoMvToCop0_s1w = MvToCop0_s1w & ~Except_s1w &
	~Except_s1i & EntryLoSel_s1w;
assign enabEntryLoLatch_s1w = enabEntryLoMvToCop0_s1w | TLBRead_s1w;

//---------------------------------------------------------------------------
// Register Move To/From Instructions (state is committed on 1w)
//---------------------------------------------------------------------------
assign tlbDrive_v2m = MvFromCop0_s2m & (IndexSel_s2m | EntryHiSel_s2m |
	EntryLoSel_s2m | RandomSel_s2m);

//---------------------------------------------------------------------------
// Latch Control Signals for register operations
//---------------------------------------------------------------------------
always @(Phi1 or MvToCop0_s1m or MvFromCop0_s1m or 
	IndexSel_s1m or RandomSel_s1m or EntryHiSel_s1m or
	EntryLoSel_s1m) begin
    if (Phi1) begin
	IndexSel_s2m   = IndexSel_s1m;
	RandomSel_s2m  = RandomSel_s1m;
	EntryHiSel_s2m = EntryHiSel_s1m;
	EntryLoSel_s2m = EntryLoSel_s1m;
	MvToCop0_s2m   = MvToCop0_s1m;
	MvFromCop0_s2m = MvFromCop0_s1m;
    end
end

always @(Phi2 or MvToCop0_s2m or
	IndexSel_s2m or EntryHiSel_s2m or
	EntryLoSel_s2m) begin
    if (Phi2) begin
	IndexSel_s1w   = IndexSel_s2m;
	EntryHiSel_s1w = EntryHiSel_s2m;
	EntryLoSel_s1w = EntryLoSel_s2m;
	MvToCop0_s1w   = MvToCop0_s2m;
    end
end

//---------------------------------------------------------------------------
// TLB Read, Write Index, Write Random, and Probe instructions	
//---------------------------------------------------------------------------
assign TLBWrite_s1m = (TLBWriteI_s1m | TLBWriteR_s1m) &
		(tlbState_s1 == `stage1e_s1);
assign TLBWriteOrProbe_s1m = (TLBWriteI_s1m | TLBWriteR_s1m | TLBProbe_s1m) &
		(tlbState_s1 == `stage1e_s1);

always @(Phi1 or Stall_s1 or TLBRead_s1m or
	TLBProbe_s1m) begin
    if (Phi1 & ~Stall_s1) begin
	TLBRead_s2m   = TLBRead_s1m;
	TLBProbe_s2m  = TLBProbe_s1m;
    end
end

//
// Keep info to know whether to drive the bus or not
//
always @(Phi2 or TLBRead_s2m or TLBProbe_s2m) begin
    if (Phi2) begin
	TLBRead_s1w  = TLBRead_s2m;
	TLBProbe_s1w = TLBProbe_s2m;
    end
end

//---------------------------------------------------------------------------
// Setting up the address for translation
//---------------------------------------------------------------------------
assign enabSaveInstrLatch_s1 = Except_s1w | (tlbState_s1 == 4'b0);
assign selSaveInstr_s1 = restoreAddr_s1;
assign ICMiss_s1 = (ICacheMiss_s1e | ICacheMiss_s1) & ~TLBWriteOrProbe_s1m;
assign selMemAddr_s1m = ~TLBWriteOrProbe_s1m && ~ICMiss_s1;

always @(Phi1 or MemAddr_s1m or ICacheMiss_s1 or 
	 ICacheMiss_s1e or instrAddr_s1e) begin
    if (Phi1) begin
	unCacheMap_s2e = (ICacheMiss_s1e | ICacheMiss_s1) ?
		(instrAddr_s1e[31:29] == 3'b101) :
		(MemAddr_s1m[31:29] == 3'b101);
	unMapped_s2e = (ICacheMiss_s1e | ICacheMiss_s1) ?
		(instrAddr_s1e[31:30] == 2'b10) :
		(MemAddr_s1m[31:30] == 2'b10);
    end
end

//---------------------------------------------------------------------------
// TLB Translation
//---------------------------------------------------------------------------
// do I need to qualify this signal?
assign TLBTranslation_s1m =  (tlbState_s1 == `stage1e_s1) &
			(InstrIsLoad_s1m | InstrIsStore_s1m | ICacheMiss_s1e);
assign tlbValidHit_v2m = TLBHit_v2m & statusBits_v2m[`Valid];

always @(Phi1 or InstrIsStore_s1m or InstrIsLoad_s1m or
	TLBTranslation_s1m) begin
    if (Phi1) begin
	InstrIsStore_s2m = InstrIsStore_s1m;
	InstrIsLoad_s2m  = InstrIsLoad_s1m;
	TLBTranslation_s2m = TLBTranslation_s1m;
    end
end

//---------------------------------------------------------------------------
// Priority Encoder to determine if we should flag an exception.
//---------------------------------------------------------------------------
always @(Phi2 or TLBProbe_s2m or unMapped_s2e or TLBHit_v2m or
	InstrIsStore_s2m or InstrIsLoad_s2m or ICacheMiss_v2r or
	statusBits_v2m or EntryHi_s2w or tlbState_s2 or Except_s2w or
	pid_v2m or tlbState_s2 or TLBTranslation_s2m) begin
//    TLBRefill_v2m = 1'b0;		  // Prevent latch inference
//    TLBModified_v2m = 1'b0;
//    TLBInvalid_v2m = 1'b0;
    if (Except_s2w) begin
	TLBRefill_v2m   = 1'b0;
	TLBModified_v2m = 1'b0;
	TLBInvalid_v2m  = 1'b0;
    end
    else if (tlbState_s2 == `tlbMissExcp_s2) begin
	TLBRefill_v2m   = 1'b1;
	TLBModified_v2m = 1'b0;
	TLBInvalid_v2m  = 1'b0;
    end
    else if (TLBTranslation_s2m) begin
	// Check for an exception
	if (~unMapped_s2e) begin
	    if (~TLBHit_v2m & (InstrIsStore_s2m | InstrIsLoad_s2m)) begin
		// TLB Missed on D Translation
		TLBRefill_v2m   = 1'b1;
		TLBModified_v2m = 1'b0;
		TLBInvalid_v2m  = 1'b0;
	    end
	    else if (TLBHit_v2m &
		    (~statusBits_v2m[`Global]) &
		    (EntryHi_s2w[11:6] != pid_v2m) &
		    (InstrIsStore_s2m | InstrIsLoad_s2m |
			tlbState_s2 == `stage2e_s2)) begin
		// Wrong PID and not Global page
		TLBRefill_v2m   = 1'b1;
		TLBModified_v2m = 1'b0;
		TLBInvalid_v2m  = 1'b0;
	    end
	    else if (TLBHit_v2m &
		    (~statusBits_v2m[`Valid]) &
		    (InstrIsStore_s2m | InstrIsLoad_s2m |
			tlbState_s2 == `stage2e_s2)) begin
		// Page is Invalid
		TLBInvalid_v2m  = 1'b1;
		TLBRefill_v2m   = 1'b0;
		TLBModified_v2m = 1'b0;
	     end
	    else if (TLBHit_v2m &
		    (~statusBits_v2m[`Dirty]) &
		    (InstrIsStore_s2m  & tlbState_s2 == `stage2e_s2)) begin
		// Page is clean and is being written to, Modified bit
		// needs to be changed.
		TLBModified_v2m = 1'b1;
		TLBRefill_v2m   = 1'b0;
		TLBInvalid_v2m  = 1'b0;
	    end
	    else begin
		TLBModified_v2m = 1'b0;
		TLBRefill_v2m   = 1'b0;
		TLBInvalid_v2m  = 1'b0;
	    end
	end
	else begin
	    TLBModified_v2m = 1'b0;
	    TLBRefill_v2m   = 1'b0;
	    TLBInvalid_v2m  = 1'b0;
	end
    end
    else begin
	TLBModified_v2m = 1'b0;
	TLBRefill_v2m   = 1'b0;
	TLBInvalid_v2m  = 1'b0;
    end
end

//---------------------------------------------------------------------------
// ICache Miss Request. Sections to the end of the file are related to 
//                      handling this.
//---------------------------------------------------------------------------
assign enabPhysicalOffset_s2m = (tlbState_s2==4'b1) | Reset_s2;
assign enabPhysicalOffsetPlus2_s2 =
	(tlbState_s2 == 4'd3) & (bytesleft_s2 != 6'b0) & 
	(((cycles_s2 == 5'b0) & (~L2Miss_s2 & ~NonCacheable_s2)) |
	    (NonCacheable_s2 & ExtDataValid_s2));
assign enabPOLatch_s2m = enabPhysicalOffset_s2m | enabPhysicalOffsetPlus2_s2;

//---------------------------------------------------------------------------
// Phi1 state transitions
//---------------------------------------------------------------------------
// If there is a TLB op instruction in the MEM stage when and I$ miss
// happens need to service the TLB op first and then do the translation.
// This corresponds to the tlbOp_s2 state. Cannot go back to stage1e_s1
// because the machine would not make forward progress.
// When and ITLB miss happens the instruction fetch logic will de-assert
// stall so that the machine can reset properly. Two cycles later this
// modul has to rise an exception by asserting TLBRefill_v2m.
// If an exception happens while servicing a request the FSM will
// abort the request and return to the IDLE stage.
// -- NOTE --Is this the right thing to do?
// If the 2-nd level cache misses then the request is aborted. When the
// miss has beenserviced this modul is repsponsible for restarting
// the request from the begining (since some of the data received may
// be bogus).
always @(Phi1 or ICacheMiss_s1e or ICacheMiss_s1 or tlbState_s1 or IStall_s1 or
	TLBWriteOrProbe_s1m or Except_s1w or MemStall_s1 or Reset_s1) begin
    if (Phi1 & ~MemStall_s1) begin
	if (Reset_s1)
	    tlbState_s2 = `idle_s2;	  // Return to idle state
	else begin
	    case (tlbState_s1)		  // synopsis parallel_case full_case
	      `stage1e_s1:		  // 1e stage
		   tlbState_s2 = (TLBWriteOrProbe_s1m) ? `tlbOp_s2 : 
				(ICacheMiss_s1e) ? `stage2e_s2 : `idle_s2;
	      `tagOp_s1:
		   tlbState_s2 = (ICacheMiss_s1) ? `stage2e_s2 : `idle_s2;
	      `extReq_s1:		  // External Req made
	           tlbState_s2 = (ICacheMiss_s1) ? `dataVal_s2 : `idle_s2;
	      `tlbMiss_s1:		  // TLB Miss
		   tlbState_s2 = (IStall_s1) ? `IStall_s2 : `stallRel_s2;
	      `stallRel_s1:		  // IStall Released
  		   tlbState_s2 = `tlbMissExcp_s2;
              `L2Miss_s1:		  // L2 Miss
		   tlbState_s2 = `L2Refill_s2;
	      default:			  // Otherwise
		   tlbState_s2 = `idle_s2;
          endcase
	end
     end
end

//---------------------------------------------------------------------------
// Phi2 state transitions
//---------------------------------------------------------------------------
always @(Phi2 or tlbState_s2 or L2Miss_s2 or unMapped_s2e or
	 tlbValidHit_v2m) begin
    if (Phi2) begin
	case (tlbState_s2)		  // synopsis parallel_case full_case
	  `stage2e_s2:			  // State 2e
		tlbState_s1 = (tlbValidHit_v2m | unMapped_s2e) ?
					`extReq_s1 :
					`tlbMiss_s1;
          `dataVal_s2:			  // Data Valid
		tlbState_s1 = (L2Miss_s2) ? `L2Miss_s1 : `extReq_s1;
          `IStall_s2:			  // Still in IStall
		tlbState_s1 = `tlbMiss_s1;
          `stallRel_s2:			  // IStall de-asserted
		tlbState_s1 = `stallRel_s1;
          `tlbMissExcp_s2:		  // TLB Miss Exception
		tlbState_s1 = `stage1e_s1;
          `L2Refill_s2:			  // L2 Refilled?
		tlbState_s1 = (L2Miss_s2) ? `L2Miss_s1 : `stage1e_s1;
	  `idle_s2:			  // Idle
		tlbState_s1 = `stage1e_s1;
	  `tlbOp_s2:
		tlbState_s1 = `tagOp_s1;
	  default:
		tlbState_s1 = `stage1e_s1;
	endcase
    end
end

//---------------------------------------------------------------------------
// ExtRequest generation from state machine.
//    ExtRequest_s1 indicates to the external interface that we need data
//---------------------------------------------------------------------------
// If the request is non-cacheable this modul has to initiate one 
// request per double word of data. This means that in TORCH mode it
// has to make 5 double word requests correspond to an instruction block.
// -- NOTE -- There are no non-cacheable requests in MIPS mode
// If the request is cacheable then only make on request for 40 bytes
// worth of data. 
//
always @(Phi2 or tlbState_s2 or L2Miss_s2 or tlbValidHit_v2m or
	unMapped_s2e or cycles_s2 or NonCacheable_s2 or bytesleft_s2 or
	ExtDataValid_s2) begin
    if (Phi2) begin
	case (tlbState_s2)		// synopsis parallel_case full_case
	    `L2Refill_s2:
		ExtRequest_s1 = ~L2Miss_s2;
	    `idle_s2:
		ExtRequest_s1 = 1'b0;
	    `stage2e_s2:
		ExtRequest_s1 = (tlbValidHit_v2m | unMapped_s2e);
	    `dataVal_s2: begin
		    if (((cycles_s2 == 5'b0) &
			    ~L2Miss_s2 == 1'b0 & ~NonCacheable_s2) |
			(NonCacheable_s2 & ExtDataValid_s2)) begin
			ExtRequest_s1 =
			    (bytesleft_s2 == 6'b0) ? 1'b0 : NonCacheable_s2;
		    end
		    else if (~L2Miss_s2) ExtRequest_s1 = 1'b0;
		    else begin
			// Added by REG 4/1/94
			// What goes here?????
			ExtRequest_s1 = 1'b0;
		    end
		end
	    default:
		ExtRequest_s1 = 1'b0;
	 endcase
    end
end
assign drvSharedMemAddr_s1 = ExtRequest_s1;

//---------------------------------------------------------------------------
// ICacheMiss_s1 signal latch logic
//---------------------------------------------------------------------------
// Need to keep track of the fact that we are servicing an I$ miss.
// In particular the IF will de-assert ICacheMiss_v2r sometime while
// the request is beeing serviced.
//
always @(Phi2 or tlbState_s2 or tlbValidHit_v2m or unMapped_s2e or
         bytesleft_s2 or ICacheMiss_s2) begin
    if (Phi2) begin
	case (tlbState_s2) 		  // synopsis parallel_case full_case
	    `stage2e_s2:
		ICacheMiss_s1 = (tlbValidHit_v2m | unMapped_s2e);
	    `dataVal_s2:
		ICacheMiss_s1 = ~(bytesleft_s2 == 6'b0);
	    default:
		ICacheMiss_s1 = ICacheMiss_s2;
	endcase
    end
end

//---------------------------------------------------------------------------
// Counter to watch bytes returning from external interface
//---------------------------------------------------------------------------
// In TORCH mode request will always be a total of 40 bytes. In MIPS mode
// request will always be 32 bytes.
// -- NOTE -- There are no non-cacheable request in MIPS mode.
//
always @(Phi2 or tlbState_s2 or tlbValidHit_v2m or unMapped_s2e or
	 MipsMode_s2e or bytesleft_s2 or ExtDataValid_s2) begin
    if (Phi2) begin
	if ((tlbState_s2 == `stage2e_s2) & (tlbValidHit_v2m | unMapped_s2e)) begin
	    bytesleft_s1 = (MipsMode_s2e) ? 6'd32 : 6'd40;
	end
	else if (bytesleft_s2 != 6'd0 & ExtDataValid_s2) begin
	    bytesleft_s1 = bytesleft_s2 - 6'd8;
	end
	else if (bytesleft_s2 != 6'd0) begin
	    bytesleft_s1 = bytesleft_s2;
	end
	else begin
	    bytesleft_s1 = 6'd0;
	end
    end
end

//---------------------------------------------------------------------------
// Counter for cycles between address translation for ICache miss.
//    Special case to watch out for is the L2 Miss handling.
//---------------------------------------------------------------------------
// This modul has to make only one request, but it has to provide the
// byte address of all the double-words it wants. For a regular TORCH
// miss that means 5 byte addresses. Need to count number of cycles
// between addresses. The addresses can cross a page boundary so need to
// be careful of that corner case
// 
//
always @(Phi2 or tlbState_s2 or tlbValidHit_v2m or unMapped_s2e or
         L2Miss_s2 or NonCacheable_s2 or ExtDataValid_s2 or
	 bytesleft_s2 or cycles_s2) begin
    if (Phi2) begin
	case (tlbState_s2)		  //  synopsis parallel_case full_case
	    `stage2e_s2:
		cycles_s1 = (tlbValidHit_v2m | unMapped_s2e) ? `RATE - 5'd1 :
			cycles_s2;
	    `dataVal_s2: begin
		    if ((cycles_s2 == 5'b0 & ~L2Miss_s2 & ~NonCacheable_s2) |
			(NonCacheable_s2 & ExtDataValid_s2)) begin
			cycles_s1 = (bytesleft_s2 == 6'd0) ?
				    cycles_s2 : `RATE - 5'd1;
		    end
		    else if (~L2Miss_s2) begin
			cycles_s1 = (cycles_s2 != 5'd0) ?
				    cycles_s2 - 5'd1 : 5'd0;
		    end
		    else
			cycles_s1 = cycles_s2;
		end
	    default:
		cycles_s1 = cycles_s2;
	endcase
    end

end

//---------------------------------------------------------------------------
// On an L2 Miss, we need to restore the address for translation that we
//  stashed away earlier. This signal brings it back for translation.
//---------------------------------------------------------------------------
always @(Phi2 or tlbState_s2 or L2Miss_s2 or
	restoreAddr_s2) begin
    if (Phi2) begin
	if ((tlbState_s2 == `L2Refill_s2) & ~L2Miss_s2) begin
	    restoreAddr_s1 = 1'b1;
	end
	else if ((tlbState_s2 == `idle_s2) |
		    (tlbState_s2 == `stage2e_s2)) begin
	    restoreAddr_s1 = 1'b0;
	end
	else begin
	    restoreAddr_s1 = restoreAddr_s2;
	end
    end
end

//---------------------------------------------------------------------------
// Some latches.
//---------------------------------------------------------------------------
always @(Phi1 or nonCacheable_s1 or bytesleft_s1 or restoreAddr_s1 or
	 ICacheMiss_s1 or cycles_s1 or Except_s1w or Reset_s1) begin
    if (Phi1) begin
	if (Except_s1w) begin
	    ICacheMiss_s2   = 1'b0;
	    restoreAddr_s2  = 1'b0;
	    NonCacheable_s2 = 1'b0;
	end
	else begin
	    ICacheMiss_s2   = ICacheMiss_s1;
	    restoreAddr_s2  = restoreAddr_s1;
	    NonCacheable_s2 = nonCacheable_s1;
	end
	bytesleft_s2    = bytesleft_s1;
	cycles_s2       = cycles_s1;
	Reset_s2	= Reset_s1;
    end
end

always @(Phi2 or NonCacheable_v2m or TLBTranslation_s2m or MipsMode_s2e or
	 ICacheMiss_v2r or NonCacheable_s2) begin
    if (Phi2) begin
	nonCacheable_s1	= (NonCacheable_v2m & TLBTranslation_s2m) |
			(NonCacheable_s2 & ~TLBTranslation_s2m);
	MipsMode_s1m	= MipsMode_s2e;
	ICacheMiss_s1e	= ICacheMiss_v2r;
    end
end

endmodule				  // tlbControl
