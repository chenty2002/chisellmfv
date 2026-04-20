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
//  Title: 	Control Module for Store Buffer
//  Created:	Fri May 13 17:24:48 1994
//  Author: 	Ricardo E. Gonzalez
//		<ricardog@chroma>
//
//
//  storeBufferCtrl.v,v 1.23 1995/06/09 05:50:23 ricardog Exp
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
//	Modified: Mon Jun 27 17:03:47 1994	<ricardog@chroma.Stanford.EDU>
//	* Qualified the buffer clocks with Stall.
//
`include "torch.h"

module storeBufferCtrl (
    Phi1,
//    Phi2,
    Reset_s1,
    Stall_s1,
    Commit_s1e,
    Squash_s1e,
    Except_s1w,
    InstrIsLoad_s1m,
    BoostedInstr_s1m,
    doBufferStore_s1m,
    popStoreBuffer_s1,
    dCacheFill_s1,
    AMatch_v1m,
    BMatch_v1m,
    Alpha_q2,
    Beta_q2,
    SeqConflict_v1m,
    stoBufferEmpty_s1,
    stoBufferStall_s1m,
    MemExcept_s2m,
    AbufSel_s1w,
    BbufSel_s1w,
    selAstore_s1w
    );

//
// Clocks
//
input		Phi1;
wire		Phi2;
input		Reset_s1;
input		Stall_s1;

//
// Inputs
//
input		Commit_s1e;
input		Squash_s1e;
input		Except_s1w;		 // Global exception signal
input		InstrIsLoad_s1m;
input		BoostedInstr_s1m;	 // store is boosted or not
input		doBufferStore_s1m;	 // put store in buffer
input		popStoreBuffer_s1;	 // take a store from buffer and do it
input		dCacheFill_s1;		 // Fill of cache, so write 64b
input	[3:0]	AMatch_v1m;
input	[3:0]	BMatch_v1m;

//
// Outputs
//
output	[3:0]	Alpha_q2;		 // Gated clock for A buffer
output	[3:0]	Beta_q2;		 // Gated clock for B buffer
output		SeqConflict_v1m;	 // Conflict w/ non-boosted buffer
output	[3:0]	AbufSel_s1w;		 // Select an entry from A buffer
output	[3:0]	BbufSel_s1w;		 // Select an entry from B buffer
output		selAstore_s1w;		 // Select A buffer
output		stoBufferEmpty_s1;	 // sequential buffer is empty
output		stoBufferStall_s1m;	 // seq buffer not empty and commit
output		MemExcept_s2m;		 // boosted store buffer conflict

//
// Registers
//
reg	[3:0]	Avalid_s1;		 // Valid bits for A-buffer
reg	[3:0]	Avalid_s2;		 // Valid bits for A-buffer
reg	[3:0]	Bvalid_s1;		 // Valid bits for B-buffer
reg	[3:0]	Bvalid_s2;		 // Valid bits for B-buffer
reg	[3:0]	Ahead_s1;		 // Pointer to first empty entry of the
reg	[3:0]	Ahead_s2;		 // buffer
reg	[3:0]	Atail_s1;		 // Pointer to the last possibly full
reg	[3:0]   Atail_s2;		 // entry of the buffer
reg	[3:0]	Bhead_s1;
reg	[3:0]	Bhead_s2;
reg	[3:0]	Btail_s1;
reg	[3:0]   Btail_s2;
reg		Asequential_s1m;		 // 1 => A buffer is sequential
reg		Asequential_s2m;		 // 0 => B buffer is sequential

//
// Delayed signals (other then Commit & Squash)
//
reg		popStoreBuffer_s2;
reg		MemExcept_s2m;		 // Buffer full or conflict

//
// Delay pong until WB stage
//
reg		Commit_s2e;
reg		Commit_s1m;
reg		Commit_s2m;
reg		Commit_s1w;
reg		Squash_s2e;
reg		Squash_s1m;
reg		Squash_s2m;

//
// Convenience signals
//
reg		doBufferStore_s2m;
reg		BoostedInstr_s2m;
wire		BoostConflict_v1m;	 // Conflict with boosted buffer
wire		Astore_s2m;		 // store should be placed in A buffer
wire		Bstore_s2m;		 // store should be placed in B buffer
wire		clearA_s1;
wire		clearB_s1;
wire	[3:0]	popA_s1;
wire	[3:0]	popB_s1;
wire		Afull_s1;
wire		Bfull_s1;
wire		seqntFull_s1;
wire		boostFull_s1;
wire		MemExcept_v1m;
wire		stoBufferEmpty_s1;
//wire		BoostedExcept_s1w;


initial begin
    Avalid_s1 = 0;
    Avalid_s2 = 0;
    Bvalid_s1 = 0;
    Bvalid_s2 = 0;
    Ahead_s1 = 0;
    Ahead_s2 = 0;
    Atail_s1 = 0;
    Atail_s2 = 0;
    Bhead_s1 = 0;
    Bhead_s2 = 0;
    Btail_s1 = 0;
    Btail_s2 = 0;
    Asequential_s1m = 0;
    Asequential_s2m = 0;
    popStoreBuffer_s2 = 0;
    MemExcept_s2m = 0;
    Commit_s2e = 0;
    Commit_s1m = 0;
    Commit_s2m = 0;
    Commit_s1w = 0;
    Squash_s2e = 0;
    Squash_s1m = 0;
    Squash_s2m = 0;
    doBufferStore_s2m = 0;
    BoostedInstr_s2m = 0;
end

assign Phi2 = ~Phi1;

//---------------------------------------------------------------------------
//			     --- Store ---
//---------------------------------------------------------------------------
assign Astore_s2m = doBufferStore_s2m & (BoostedInstr_s2m ^  Asequential_s2m);
assign Bstore_s2m = doBufferStore_s2m & (BoostedInstr_s2m ^ ~Asequential_s2m);
assign Alpha_q2 = {4{Phi2 & Astore_s2m}} & Ahead_s2;
assign Beta_q2  = {4{Phi2 & Bstore_s2m}} & Bhead_s2;

//---------------------------------------------------------------------------
//			    --- Conflict ---
//---------------------------------------------------------------------------
assign SeqConflict_v1m =   (Asequential_s1m) ? (|(Avalid_s1 & AMatch_v1m)) :
				(|(Bvalid_s1 & BMatch_v1m));
assign BoostConflict_v1m = (Asequential_s1m) ? (|(Bvalid_s1 & BMatch_v1m)) :
				(|(Avalid_s1 & AMatch_v1m));

//---------------------------------------------------------------------------
//			   --- Valid Bits ---
//---------------------------------------------------------------------------
//
// On an exception need to clear the boosted buffer. If however, a commit
// happened on the previous phase (since commits happen on phi2 of MEM)
// may need to clear out the sequential buffer. Since the machine will
// clear out the sequential buffer BEFORE it allows a commit, there is
// no need to reverse the sequential pointer.
//
assign clearA_s1 = Reset_s1 | (Except_s1w & ~Asequential_s1m) |
			(Except_s1w &  Commit_s1w &  Asequential_s1m);
assign clearB_s1 = Reset_s1 | (Except_s1w &  Asequential_s1m) |
			(Except_s1w &  Commit_s1w & ~Asequential_s1m);
assign popA_s1 = (popStoreBuffer_s1 &  Asequential_s1m) ? ~Atail_s1 : 4'hf;
assign popB_s1 = (popStoreBuffer_s1 & ~Asequential_s1m) ? ~Btail_s1 : 4'hf;

always @(Phi1 or Avalid_s1 or Bvalid_s1 or clearA_s1 or clearB_s1 or
	popA_s1 or popB_s1) begin
    if (Phi1) begin
	`TICK
	Avalid_s2 = {4{~clearA_s1}} & (popA_s1 & Avalid_s1);
	Bvalid_s2 = {4{~clearB_s1}} & (popB_s1 & Bvalid_s1);
    end
end

always @(Phi2 or Avalid_s2 or Bvalid_s2 or Ahead_s2 or Bhead_s2 or
	Squash_s2m or Astore_s2m or Bstore_s2m or Asequential_s2m) begin
    if (Phi2) begin
	`TICK
	Avalid_s1 = (Squash_s2m & ~Asequential_s2m) ? 4'h0 :
			({4{Astore_s2m}} & Ahead_s2) | Avalid_s2;
	Bvalid_s1 = (Squash_s2m &  Asequential_s2m) ? 4'h0 :
			({4{Bstore_s2m}} & Bhead_s2) | Bvalid_s2;
    end
end

//---------------------------------------------------------------------------
//			  --- Head & Tail ---
//---------------------------------------------------------------------------
assign AbufSel_s1w = Atail_s1;	        // Pick earliest entry to retire, if
assign BbufSel_s1w = Btail_s1;	        // an entry will be retired this cycle
assign selAstore_s1w = Asequential_s1m;   // Select whichever buffer is the

always @(Phi1 or Ahead_s1 or Bhead_s1 or Atail_s1 or Btail_s1 or
	clearA_s1 or clearB_s1) begin
    if (Phi1) begin
	`TICK
	Ahead_s2 = (clearA_s1) ? 4'h1 : Ahead_s1;
	Atail_s2 = (clearA_s1) ? 4'h1 : Atail_s1;
	Bhead_s2 = (clearB_s1) ? 4'h1 : Bhead_s1;
	Btail_s2 = (clearB_s1) ? 4'h1 : Btail_s1;
    end
end

always @(Phi2 or Ahead_s2 or Bhead_s2 or Atail_s2 or Btail_s2 or
	Astore_s2m or Bstore_s2m or popStoreBuffer_s2 or Squash_s2m or
	Asequential_s2m) begin
    if (Phi2) begin
	`TICK
	Ahead_s1 = (Squash_s2m & ~Asequential_s2m) ? 4'h1 :
		    (Astore_s2m) ?
			{Ahead_s2[2:0], Ahead_s2[3]} : Ahead_s2;
	Bhead_s1 = (Squash_s2m &  Asequential_s2m) ? 4'h1 :
		    (Bstore_s2m) ?
			{Bhead_s2[2:0], Bhead_s2[3]} : Bhead_s2;
	Atail_s1 = (Squash_s2m & ~Asequential_s2m) ? 4'h1 :
		    ( Asequential_s2m & popStoreBuffer_s2) ?
			{Atail_s2[2:0], Atail_s2[3]} : Atail_s2;
	Btail_s1 = (Squash_s2m &  Asequential_s2m) ? 4'h1 :
		    (~Asequential_s2m & popStoreBuffer_s2) ?
			{Btail_s2[2:0], Btail_s2[3]} : Btail_s2;
    end
end
//---------------------------------------------------------------------------
//			      --- Pong ---
//---------------------------------------------------------------------------
always @(Phi1 or Stall_s1 or Asequential_s1m or Reset_s1) begin
    if (Phi1 & ~Stall_s1) begin
	`TICK
	Asequential_s2m = Reset_s1 | Asequential_s1m;
    end
end

always @(Phi2 or Asequential_s2m or Commit_s2m) begin
    if (Phi2) `TICK Asequential_s1m = Asequential_s2m ^ Commit_s2m;
end

//---------------------------------------------------------------------------
//			 --- Delay Latches ---
//---------------------------------------------------------------------------
always @(Phi1 or doBufferStore_s1m or Except_s1w or Stall_s1) begin
    if (Phi1) `TICK doBufferStore_s2m = ~Except_s1w & doBufferStore_s1m;
end

always @(Phi1 or Stall_s1 or BoostedInstr_s1m) begin
    if (Phi1 & ~Stall_s1) `TICK BoostedInstr_s2m = BoostedInstr_s1m;
end

always @(Phi1 or stoBufferStall_s1m or popStoreBuffer_s1) begin
//    if (Phi1 & ~stoBufferStall_s1m) begin
    if(Phi1) begin
	`TICK
	popStoreBuffer_s2 = popStoreBuffer_s1;
    end
end

always @(Phi1 or Stall_s1 or Commit_s1e or Commit_s1m or
	Squash_s1e or Squash_s1m) begin
    if (Phi1 & ~Stall_s1) begin
	`TICK
	Commit_s2e = Commit_s1e;
	Commit_s2m = Commit_s1m;
	Squash_s2e = Squash_s1e;
	Squash_s2m = Squash_s1m;
    end
end

always @(Phi2 or Commit_s2e or Commit_s2m or Squash_s2e) begin
    if (Phi2) begin
	`TICK
	Commit_s1m = Commit_s2e;
	Commit_s1w = Commit_s2m;
	Squash_s1m = Squash_s2e;
    end
end

//---------------------------------------------------------------------------
//			   --- Exception ---
//---------------------------------------------------------------------------
//
// NOTE: There are two possible ways to manage a sequential conflict
// on a boosted load.
// 1. Stall: Simply stall the machine do the store in the buffer and
//    then continue. The load will then return the correct data. This
//    seems like the preferred way (and is indeed what the hardware will
//    do).
// 2. Except: When the conflict happens post a boosted exception. On a
//    commit the machine will take a sequential exception and go to the
//    correct handler for that piece of boosted code. It will then
//    re-execute the load and get the correct data.
// Either solution works. For now I have implemented solution (1). To
// change to solution (1) need to change the equation below to include
// SeqConflict_v1m ORed with BoostConflict_v1m. This will cause the
// machine to note a boosted exception for that load.
//
assign MemExcept_v1m = (InstrIsLoad_s1m & BoostedInstr_s1m &
			    (BoostConflict_v1m)) |
			(doBufferStore_s1m &
			    ((BoostedInstr_s1m & boostFull_s1) |
			     (~BoostedInstr_s1m & seqntFull_s1)));

always @(Phi1 or Stall_s1 or MemExcept_v1m) begin
    if (Phi1 & ~Stall_s1) begin
	`TICK
	MemExcept_s2m = MemExcept_v1m;
    end
end

// 
// If an exception happens one the same cycle as a commit need to clear
// out the newly sequential buffer since the commit should not have
// happened.  FIXME: Need to make sure that I do not commit a store
// BEFORE I can detect that an exception is going on (like in the 3
// branch case where I stall after the second branch to clear the store
// buffer).
//
//assign BoostedExcept_s1w = Commit_s1w & Except_s1w;

//---------------------------------------------------------------------------
//			  --- Empty & Full ---
//---------------------------------------------------------------------------
assign Afull_s1 = (Ahead_s1 == Atail_s1) & |(Avalid_s1);
assign Bfull_s1 = (Bhead_s1 == Btail_s1) & |(Bvalid_s1);
assign boostFull_s1 = (Asequential_s1m) ? Bfull_s1 : Afull_s1;
assign seqntFull_s1 = (Asequential_s1m) ? Afull_s1 : Bfull_s1;
assign stoBufferEmpty_s1 = (Asequential_s1m) ? ~|(Avalid_s1) : ~|(Bvalid_s1);
assign stoBufferStall_s1m = ~stoBufferEmpty_s1 & Commit_s1m;

//
// DEBUG: Detect sequential conflict on boosted load case:
//
// synopsys translate_off
`ifdef CADENCE
always @(InstrIsLoad_s1m or BoostedInstr_s1m or SeqConflict_v1m) begin
    if (InstrIsLoad_s1m & BoostedInstr_s1m & SeqConflict_v1m) begin
	$display("\t\t\tStore buffer conflict (Except)");
    end
end
`endif
// synopsys translate_on

endmodule				// storeBufferCtrl
