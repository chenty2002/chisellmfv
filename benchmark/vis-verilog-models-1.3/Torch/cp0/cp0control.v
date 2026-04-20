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
//  Title: 	CP0 Control Module
//  Created:	Thu Mar 24 16:46:03 1994
//  Author: 	Ricardo E. Gonzalez
//		<ricardog@chroma>
//
//
//  cp0control.v,v 7.18 1995/01/28 00:40:59 ricardog Exp
//
//  TORCH Research Group.
//  Stanford University.
//	1993.
//
//	Description: 
//
//	Hierarchy: 
//
//  Revision History:
//	Modified: Thu Dec  1 21:27:04 1994	<ricardog@chroma.Stanford.EDU>
//	* Added explicit latch enables.
//	Modified:	Sun May 22 16:12:10 1994	<ricardog@chroma>
//	* Changed MemStall_s2 to Stall_s1.
//	Modified:	Thu Apr  7 11:56:46 1994
//	* Fixed verilint errors.	<ricardog@chroma>
//	Modified:	06-02-92
//	* Added BExTaken_s1w, Reset_s2 to interface. 
//	* Clear MipsMode/SystemBit on Reset.
//	Modified:	05-12-92
//	* fixed MipsMode/SystemBit during RFE
//	Modified:	05-10-92
//	* fixed CBEP_v2 to generate boosted exception only if 
//	* boosted code was executed in the current context (BSc_s2)
//	Modified:	04-17-92
//	* delayed ClearBoost to WB of delay slot,
//	* cleaned-up boosted exception logic - R. Ho
//	Modified:	04-13-92
//	* added MemStall_s2 support
//	Modified:	04-12-92
//	    * fixed boosted exception to happen in s1w of delay slot
//	Modified:	04-10-92
//	* cleaned-up with vcheck, Exception Signals latched on Phi2.
//	Modified:	04-09-92
//	* changed ExceptVector_s1i to s1w
//	Modified:	03-19-92
//	* added MipsMode/SystemBit processing logic
//	Modified:	03-16-92
//	* taken-BoostedExcept > seq. except > noted-BostedExcept
//	Modified:	03-11-92
//	* added boosted exception handling
//	Modified:	03-04-92
//	* qualify with clock ExceptionRaised, RetFromExcept 
//
`include "torch.h"

module cp0control(
    Phi1,
//    Phi2,
    Stall_s1,
    Reset_s1,
    Reset_s2,
    BoostedExcept_v2,
    SeqExcept_v2,
    TLBL1_s1w,
    RetFromExcept_s2e,
    BEP_s2,
    BEV_s1,
    BSp_s2,
    BSc_s2,
    Commit_s1e,
    Squash_s1e,
    MvToCop0_s1m,
    MvFromCop0_s1m,
    SetBoost_s1w,
    CopRegNum_s1m,
    MPc_s2,
    MPp_s2,
    MPo_s2,
    KUc_s2,
    KUp_s2,
    KUo_s2,
    PushStatus_s1w,
    PopStatus_s1w,
    BrDelaySlot_s1w,
    Squash_s1w,
    Except_s1w,
    ExceptVector_s1i,
    BExTaken_s1w,
    MipsMode_s2e,
    SystemBit_s2e,
    drvCp0Bus_q2m,
    writeContext_s1w,
    defContext_s1w,
    writeCause_s1w,
    defCause_s1w,
    writeStatus_s1w,
    defStatus_s1w,
    newBSc_s1,
    newBEP_s1,
    IndexSel_s1m, 
    RandomSel_s1m,
    EntryLoSel_s1m,
    EntryHiSel_s1m,
    ContextSel_s2m,
    BadVAddrSel_s2m, 
    StatusSel_s2m,
    CauseSel_s2m,
    EPCSel_s1m,
    EPCNSel_s1m
    );

//
// Clocks & Stalls
//
input		Phi1;
wire		Phi2;
input		Stall_s1;

//
// Latched Reset Signal
//
input		Reset_s1;
input		Reset_s2;

//
// info needed to process exceptions
//
input		BoostedExcept_v2;
input		SeqExcept_v2;
input		TLBL1_s1w;
input		MPc_s2, MPp_s2, MPo_s2;	  // Current, Previous & Old Mips Mode
input		KUc_s2, KUp_s2, KUo_s2;	  // Current, Previous & Old User bit
input		BEP_s2, BEV_s1, BSp_s2, BSc_s2;
input		Commit_s1e;
input		Squash_s1e;
input		SetBoost_s1w;

//
// Information needed for moves from/to cp0 registers
//
input	[3:0]	CopRegNum_s1m;

//
// Decoded instructions
//
input		RetFromExcept_s2e;
input		MvToCop0_s1m;
input		MvFromCop0_s1m;

//
// Exception taken
//
output		Except_s1w;
output		BExTaken_s1w;
output	[2:0]	ExceptVector_s1i;

//
// Decoded instructions
//
output		writeContext_s1w;
output		defContext_s1w;
output		writeCause_s1w;
output		defCause_s1w;
output		writeStatus_s1w;
output		defStatus_s1w;
output		newBSc_s1;
output		newBEP_s1;


//
// Other Outputs 
//
output		BrDelaySlot_s1w;
output		Squash_s1w;
output		PushStatus_s1w;
output		PopStatus_s1w;
output		MipsMode_s2e;
output		SystemBit_s2e;
output		drvCp0Bus_q2m;

//
// Decoded Outputs
//
output		IndexSel_s1m;		  // Always asserted (even if
output		RandomSel_s1m;		  // not doing a move this cycle).
output		EntryLoSel_s1m;
output		EntryHiSel_s1m;
output		ContextSel_s2m;
output		BadVAddrSel_s2m;
output		StatusSel_s2m;
output		CauseSel_s2m;
output		EPCSel_s1m;		  // Only driven when there is a
output		EPCNSel_s1m;		  // move from cp0.

//
// Internal nodes/variables
//
reg	[2:0]	ExceptVector_s1i;
reg		RetFromExcept_s1m; 
reg		RetFromExcept_s2m; 

//
// Move from/to cp0 register
//
reg		ContextSel_s2m;
reg		BadVAddrSel_s2m;
reg		StatusSel_s2m;
reg		CauseSel_s2m;

//
// Commit/Squash pipeline
//
reg		Commit_s2e;
reg		Commit_s1m;
reg		Commit_s2m;
reg		Squash_s2e;
reg		Squash_s1m;
reg		Squash_s2m;
reg		Squash_s1w;

wire		BrDelaySlot_s2m; 	 // Pack in MEM is delay slot of branch
reg		BrDelaySlot_s1w;  

//
// Exceptions
//
reg		PopStatus_s1w;
wire		PushStatus_s1w;
reg		SetBEP_s1w;
reg		BExTaken_s1w;
reg		SEx_s1w;

//
// Determine Exception type
//
wire		BExNoted_v2;
wire		BExTaken_v2;
wire		SEx_v2;			 // Sequential Exception--any kind
wire		BES_v2;			 // Boosted Exception in slot (correct)
wire		SES_v2;			 // Sequential Exception in slot
wire		SENS_v2;		 // Sequential Exception not in slot
wire		CBSc_v2;		 // Commit & boosted code executed
wire		CBEP_v2;		 // Commit & boosted except pending

wire		MipsMode_s2e;
wire		SystemBit_s2e;

//
// Delayed signals
//
reg		MvToCop0_s2m;
reg		MvToCop0_s1w;
reg		MvFromCop0_s2m;
reg		ContextSel_s1w;
reg		StatusSel_s1w;
reg		CauseSel_s1w;
reg		BEP_s1;
reg		BSc_s1;

//
// exception vectors 
//
`define	RST	2'b00			  // reset vector
`define CEV	2'b01			  // common exception vector
`define	TLBR	2'b10			  // TLB refill vector
`define	BEX	2'b11			  // boosted exception vector

initial begin
    ExceptVector_s1i = 0;
    RetFromExcept_s1m = 0; 
    RetFromExcept_s2m = 0; 
    ContextSel_s2m = 0;
    BadVAddrSel_s2m = 0;
    StatusSel_s2m = 0;
    CauseSel_s2m = 0;
    Commit_s2e = 0;
    Commit_s1m = 0;
    Commit_s2m = 0;
    Squash_s2e = 0;
    Squash_s1m = 0;
    Squash_s2m = 0;
    Squash_s1w = 0;
    BrDelaySlot_s1w = 0;  
    PopStatus_s1w = 0;
    SetBEP_s1w = 0;
    BExTaken_s1w = 0;
    SEx_s1w = 0;
    MvToCop0_s2m = 0;
    MvToCop0_s1w = 0;
    MvFromCop0_s2m = 0;
    ContextSel_s1w = 0;
    StatusSel_s1w = 0;
    CauseSel_s1w = 0;
    BEP_s1 = 0;
    BSc_s1 = 0;
end

assign 	Phi2 = ~Phi1;

//
// Determine if WB of a branch delay slot
//
assign BrDelaySlot_s2m = Commit_s2m | Squash_s2m;

//
// Determine exception type during Phi2-MEM
// I dont really understand the euqations. I will have to figure it out.
//
assign BES_v2  = BoostedExcept_v2 & Commit_s2m;
assign SES_v2  = SeqExcept_v2 &  BrDelaySlot_s2m;
assign SENS_v2 = SeqExcept_v2 & ~BrDelaySlot_s2m;
assign CBSc_v2 = Commit_s2m & BSc_s2;
assign CBEP_v2 = Commit_s2m & (BEP_s2 & ~RetFromExcept_s2m);

assign BExNoted_v2 = BSp_s2 & RetFromExcept_s2m |
                     BoostedExcept_v2 & ~SENS_v2;

assign BExTaken_v2 = BES_v2 | CBEP_v2 | SES_v2 & CBSc_v2;

assign SEx_v2 = SENS_v2 | SES_v2 & ~CBSc_v2;

//
// Generate exception control signals during Phi1-WB 
//
always @(Phi2 or SEx_v2 or BExTaken_v2 or BExNoted_v2) begin
    if (Phi2) begin
	SEx_s1w = SEx_v2;
	BExTaken_s1w = BExTaken_v2;
	SetBEP_s1w = BExNoted_v2 & ~BExTaken_v2; 
    end
end

assign PushStatus_s1w = SEx_s1w | BExTaken_s1w;
assign Except_s1w = PushStatus_s1w | Reset_s1;

//
// Exception vector encoder (note implied logic is a MUX not a LATCH)
//
always @(Reset_s1 or TLBL1_s1w or SEx_s1w or BExTaken_s1w or BEV_s1) begin
    if (Reset_s1)
	ExceptVector_s1i = {1'b1, `RST};
    else if (BExTaken_s1w)
	ExceptVector_s1i = {BEV_s1, `BEX};
    else if (TLBL1_s1w)
	ExceptVector_s1i = {BEV_s1, `TLBR};
    else
	ExceptVector_s1i = {BEV_s1, `CEV};
end

//
// RFE handling (Return From Exception)
//
always @(Phi1 or Stall_s1 or RetFromExcept_s1m or Except_s1w) begin
    if (Phi1 & ~Stall_s1) begin
	RetFromExcept_s2m = RetFromExcept_s1m & ~Except_s1w;
    end
end

always @(Phi2 or RetFromExcept_s2e or RetFromExcept_s2m)
begin
    if (Phi2) begin
	RetFromExcept_s1m = RetFromExcept_s2e;
	PopStatus_s1w = RetFromExcept_s2m;
    end
end

//
// System Bit/Mips Mode Handling
//
assign MipsMode_s2e = ~Reset_s2 & (
			(~RetFromExcept_s2e & ~RetFromExcept_s2m) ? 
			MPc_s2 : 
			(RetFromExcept_s2e & RetFromExcept_s2m) ?
			MPo_s2 : MPp_s2
			);
assign SystemBit_s2e = ~Reset_s2 & (
			(~RetFromExcept_s2e & ~RetFromExcept_s2m) ?
			KUc_s2 :
			(RetFromExcept_s2e & RetFromExcept_s2m) ?
			KUo_s2 : KUp_s2
			);

//---------------------------------------------------------------------------
//			--- Register Decoder ---
//---------------------------------------------------------------------------
assign IndexSel_s1m    = (CopRegNum_s1m[3:0] == 4'd0);
assign RandomSel_s1m   = (CopRegNum_s1m[3:0] == 4'd1);
assign EntryLoSel_s1m  = (CopRegNum_s1m[3:0] == 4'd2);
assign EntryHiSel_s1m  = (CopRegNum_s1m[3:0] == 4'd10);
assign EPCSel_s1m      = (CopRegNum_s1m[3:0] == 4'd14) & MvFromCop0_s1m;
assign EPCNSel_s1m     = (CopRegNum_s1m[3:0] == 4'd11) & MvFromCop0_s1m;

always @(Phi1 or Stall_s1 or CopRegNum_s1m) begin
    if (Phi1 & ~Stall_s1) begin
	ContextSel_s2m  = (CopRegNum_s1m[3:0] == 4'd4);
	BadVAddrSel_s2m = (CopRegNum_s1m[3:0] == 4'd8);
	StatusSel_s2m   = (CopRegNum_s1m[3:0] == 4'd12);
	CauseSel_s2m    = (CopRegNum_s1m[3:0] == 4'd13);
    end
end

always @(Phi2 or ContextSel_s2m or StatusSel_s2m or
	CauseSel_s2m) begin
    if (Phi2) begin
	ContextSel_s1w = ContextSel_s2m;
	StatusSel_s1w = StatusSel_s2m;
	CauseSel_s1w = CauseSel_s2m;
    end
end

//---------------------------------------------------------------------------
//			--- Datapath Control ---
//---------------------------------------------------------------------------
//
// Context Register
//
assign writeContext_s1w = ~Except_s1w & MvToCop0_s1w & ContextSel_s1w;
assign defContext_s1w = ~writeContext_s1w & ~Except_s1w;

//
// Cuase Register
//
assign writeCause_s1w = ~Except_s1w & MvToCop0_s1w & CauseSel_s1w;
assign defCause_s1w = ~writeCause_s1w & ~Except_s1w;

//
// Status register
//
assign writeStatus_s1w = ~Except_s1w & ~PopStatus_s1w & MvToCop0_s1w &
			StatusSel_s1w;
assign defStatus_s1w = ~writeStatus_s1w & ~PushStatus_s1w & ~PopStatus_s1w;
assign newBSc_s1 = ~BrDelaySlot_s1w & ((SetBoost_s1w & ~PushStatus_s1w) |
			(BSc_s1 & ~BExTaken_s1w));
assign newBEP_s1 = SetBEP_s1w | BEP_s1;

//
// Cp0 Bus driver
//
assign drvCp0Bus_q2m = Phi2 & MvFromCop0_s2m;

//---------------------------------------------------------------------------
//			--- Delayed Signals ---
//---------------------------------------------------------------------------
always @(Phi1 or Commit_s1e or Commit_s1m or Squash_s1e or Squash_s1m or
	Except_s1w or Stall_s1) begin
    if (Phi1 & ~Stall_s1) begin
	Commit_s2e = Commit_s1e & ~Except_s1w;
	Commit_s2m = Commit_s1m & ~Except_s1w;
	Squash_s2e = Squash_s1e & ~Except_s1w;
	Squash_s2m = Squash_s1m & ~Except_s1w;
  end
end

always @(Phi2 or Squash_s2e or Squash_s2m or Commit_s2e or
	BrDelaySlot_s2m) begin
    if (Phi2) begin
	Squash_s1m = Squash_s2e;
	Squash_s1w = Squash_s2m;
	Commit_s1m = Commit_s2e;
	BrDelaySlot_s1w = BrDelaySlot_s2m;
    end
end

always @(Phi1 or Stall_s1 or Except_s1w or MvToCop0_s1m or
	MvFromCop0_s1m) begin
    if (Phi1 & ~Stall_s1) begin
	MvToCop0_s2m = MvToCop0_s1m & ~Except_s1w;
	MvFromCop0_s2m = MvFromCop0_s1m & ~Except_s1w;
    end
end

always @(Phi2 or MvToCop0_s2m) begin
    if (Phi2) MvToCop0_s1w = MvToCop0_s2m;
end

always @(Phi2 or BEP_s2 or BSc_s2) begin
    if (Phi2) begin
	BEP_s1 = BEP_s2;
	BSc_s1 = BSc_s2;
    end
end
endmodule				  // cp0control
