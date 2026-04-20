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
//  Title: 	A-side Register Specifier Bypass Control
//  Created:	Feb 20 1992
//  Author: 	Ricardo E. Gonzalez
//		<ricardog@bill>
//
//
//  ABypassCtrl.v,v 1.2 1995/01/28 00:50:23 ricardog Exp
//
//  TORCH Research Group.
//  Stanford University.
//	1992.
//
//	Description: 
//
//  	Hierarchy:
//
//  Revision History:
//	Modified: Thu Dec  1 21:59:50 1994	<ricardog@chroma.Stanford.EDU>
//	* Changed MUX2 implementation.
//	Modified:	Sat May 28 15:49:52 1994	<ricardog@chroma>
//	* Qualify load data latch with IStall_s2.
//	Modified:	Sun May 22 16:44:18 1994	<ricardog@chroma>
//	* Changed MemStall_s2/IStall_s1 to Stall_s1.
//	Modified:	Sun Apr 10 15:26:33 1994	<ricardog@chroma>
//	* New file for A-side only (derived from bypassSpec).
//	Modified:	Sat Apr  9 17:20:42 1994	<ricardog@chroma>
//	* Fixed verilint errors.
//	Modified:	Tue Mar 29 19:01:36 1994	<ricardog@chroma>
//	* Changed signal names and moved macros.
//
`include "torch.h"

module ABypassCtrl(
    Phi1,
//    Phi2, 
    Stall_s1,
    IStall_s1,
    MemStall_s1,
    ABoosted_s1e,
    ABoostValid_v1e,
    AKill_s1e,
    AIgnore_s2e,
    BBoosted_s1e,
    BBoostValid_v1e,
    BIsLoad_s1e,
    BKill_s1e,
    BIgnore_s2e,
    Commit_s1e,
    Squash_s1e,
    Except_s1w,
    ANoDest_s1e,
    BNoDest_s1e,
    ADestIsZero_v1e,
    BDestIsZero_v1e,
    ASBypBmem_s1e,
    ATBypBmem_s1e,
    Alpha1_s1m,
    Beta1_s1m,
    Delta2_q2,
    ASBypassLoad_s1e,
    ATBypassLoad_s1e,
    ASBypassData_s1e,
    ATBypassData_s1e,
    ABoosted_s2e,
    ABoosted_s2m,
    AValid_s1e,
    AValid_s2e,
    AValid_s2m,
    AValid_s1w,
    BBoosted_s2e,
    BBoosted_s2m,
    BValid_s2e,
    BValid_s2m
    );



//
// Clocks & Stalls
//
input		Phi1;
wire		Phi2;
input		Stall_s1;
input		IStall_s1;
input		MemStall_s1;


//
// Boosting information
//
input		ABoosted_s1e;		 // Dest register is boosted
input		BBoosted_s1e;		 // Dest register is boosted
input		ABoostValid_v1e;	 // this reg has been written as boost
input		BBoostValid_v1e;	 // this reg has been written as boost

//
// Instruction WB cancell
//
input		AKill_s1e;		 // A-side instruction is bogus
input		AIgnore_s2e;		 // inst issued to both sides, A is bad
input		BKill_s1e;		 // B-side instruction is bogus
input		BIgnore_s2e;		 // inst issued to both sides, B is bad
input		ANoDest_s1e;		 // No destination for WB. Should
input		BNoDest_s1e;		 // really be in the decoders
input		ADestIsZero_v1e;
input		BDestIsZero_v1e;

input		ASBypBmem_s1e;
input		ATBypBmem_s1e;
input		BIsLoad_s1e;

//
// Branches & Exceptions
//
input		Commit_s1e;
input		Squash_s1e;
input		Except_s1w;


//--------------------------------------------------------------------------
//                      ---- Outputs ----
//--------------------------------------------------------------------------
output		Alpha1_s1m;		 // Qualified clock for A datapath
output		Beta1_s1m;		 // Qualified clock for B datapath
output		Delta2_q2;		 // Qualified clock Phi2 & ~IStall
output		ASBypassLoad_s1e;	 // select for load bypass fast mux
output		ASBypassData_s1e;	 // bypass from datapath
output		ATBypassLoad_s1e;	 // Same again but now for T bus
output		ATBypassData_s1e;	 // bypass from datapath

output		ABoosted_s2e;
output		ABoosted_s2m;
output		AValid_s1e;
output		AValid_s2e;
output		AValid_s2m;
output		AValid_s1w;
output		BBoosted_s2e;
output		BBoosted_s2m;
output		BValid_s2e;
output		BValid_s2m;

//
// Delayed version of Stall
//
reg		IStall_s2;
reg		MemStall_s2;

//
// For clock qualification
//
wire		Delta2_q2;

//
// random logic signals
//
wire	    ANoDest_s1e;		 // No destination for WB. Should
wire	    BNoDest_s1e;		 // really be in the decoders
wire	    ADestIsZero_v1e;
wire	    BDestIsZero_v1e;

//
// Kill Chain
//
reg	    AValid_s2e;			// Valid bit for WB to register file
reg	    AValid_s1m;			// Also used to qualify delay latches
reg	    AValid_s2m;			// 
reg	    AValid_s1w;			// 

reg	    BValid_s2e;
reg	    BValid_s1m;
reg	    BValid_s2m;

reg	    ABoosted_s2e;
reg	    ABoosted_s1m;
reg	    ABoosted_s2m;

reg	    ABoostValid_s2e;
reg	    ABoostValid_s1m;
reg	    ABoostValid_s2m;

reg	    BBoostValid_s2e;
reg	    BBoostValid_s1m;
reg	    BBoostValid_s2m;

reg	    BBoosted_s2e;
reg	    BBoosted_s1m;
reg	    BBoosted_s2m;

reg	    BIsLoad_s2e;
reg	    BIsLoad_s1m;
reg	    BIsLoad_s2m;
reg	    BIsLoad_s1w;

initial begin
    IStall_s2 = 0;
    MemStall_s2 = 0;
    AValid_s2e = 0;
    AValid_s1m = 0;
    AValid_s2m = 0;
    AValid_s1w = 0;
    BValid_s2e = 0;
    BValid_s1m = 0;
    BValid_s2m = 0;
    ABoosted_s2e = 0;
    ABoosted_s1m = 0;
    ABoosted_s2m = 0;
    ABoostValid_s2e = 0;
    ABoostValid_s1m = 0;
    ABoostValid_s2m = 0;
    BBoostValid_s2e = 0;
    BBoostValid_s1m = 0;
    BBoostValid_s2m = 0;
    BBoosted_s2e = 0;
    BBoosted_s1m = 0;
    BBoosted_s2m = 0;
    BIsLoad_s2e = 0;
    BIsLoad_s1m = 0;
    BIsLoad_s2m = 0;
    BIsLoad_s1w = 0;
end

assign Phi2 = ~Phi1;

//--------------------------------------------------------------------------
//                    ---- Control Logic ----
//--------------------------------------------------------------------------
//
// Qualify the clocks
//
assign Delta2_q2 = (~IStall_s2 | MemStall_s2) & Phi2;

//
// If B-side instr was a load do some special processing. Also don't
// drive the source operand busses if the instruction is being killed.
//
assign ASBypassLoad_s1e = ASBypBmem_s1e & BIsLoad_s1w & ~AKill_s1e;
assign ATBypassLoad_s1e = ATBypBmem_s1e & BIsLoad_s1w & ~AKill_s1e;
assign ASBypassData_s1e = ~(ASBypBmem_s1e & BIsLoad_s1w) & ~AKill_s1e;
assign ATBypassData_s1e = ~(ATBypBmem_s1e & BIsLoad_s1w) & ~AKill_s1e;

//--------------------------------------------------------------------------
//			 ---- Delay IStall ----
//--------------------------------------------------------------------------
always @(Phi1 or IStall_s1 or MemStall_s1) begin
    if (Phi1) begin
	`TICK
	IStall_s2 = IStall_s1;
	MemStall_s2 = MemStall_s1;
    end
end

//--------------------------------------------------------------------------
//			 ---- Random Logic ----
//--------------------------------------------------------------------------
assign AValid_s1e = ~(AKill_s1e | ANoDest_s1e | Except_s1w);

always @(Phi1 or Stall_s1 or ADestIsZero_v1e or Except_s1w or
	Commit_s1e or Squash_s1e or AValid_s1m or
	ABoosted_s1e or ABoosted_s1m or
	ABoostValid_v1e or ABoostValid_s1m) begin
    if (Phi1 & ~Stall_s1) begin
	`TICK
	AValid_s2e = ~(AKill_s1e | ANoDest_s1e | Except_s1w);
	AValid_s2m = AValid_s1m & ~Except_s1w;
	//
	// If commit happens do a pong, and if squash
	// clear boost and boost valid bit.
	//
	ABoosted_s2e = ABoosted_s1e ^ (Commit_s1e & ABoostValid_v1e);
	ABoostValid_s2e = ABoostValid_v1e & ~(Commit_s1e | Squash_s1e);
	ABoosted_s2m = ABoosted_s2e ^ (Commit_s1e & ABoostValid_v1e);
	ABoostValid_s2m = ABoostValid_s1m & ~(Commit_s1e | Squash_s1e);
    end
end

always @(Phi2 or AValid_s2e or AValid_s2m or ABoosted_s2e or ABoosted_s2m or
	AIgnore_s2e or ABoostValid_s2e) begin
    if (Phi2) begin
	`TICK
	AValid_s1m = AValid_s2e & ~AIgnore_s2e;
	AValid_s1w = AValid_s2m;
	ABoosted_s1m = ABoosted_s2e;
	ABoostValid_s1m = ABoostValid_s2e;
    end
end

always @(Phi1 or Stall_s1 or BDestIsZero_v1e or Except_s1w or
	Commit_s1e or Squash_s1e or BValid_s1m or
	BBoosted_s1e or BBoosted_s1m or
	BBoostValid_v1e or BBoostValid_s1m or
	BIsLoad_s1e or BIsLoad_s1m) begin
    if (Phi1 & ~Stall_s1) begin
	`TICK
	BValid_s2e = ~(BKill_s1e | BNoDest_s1e | Except_s1w);
	BValid_s2m = BValid_s1m & ~Except_s1w;
	//
	// If commit happens do a pong, and if squash
	// clear boost and boost valid bit.
	//
	BBoosted_s2e = BBoosted_s1e ^ (Commit_s1e & BBoostValid_v1e);
	BBoostValid_s2e = BBoostValid_v1e & ~(Commit_s1e | Squash_s1e);
	BBoosted_s2m = BBoosted_s2e ^ (Commit_s1e & BBoostValid_v1e);
	BBoostValid_s2m = BBoostValid_s1m & ~(Commit_s1e | Squash_s1e);
	//
	// Keep track of which B-side instrs are loads (special bypass)
	//
	BIsLoad_s2e = BIsLoad_s1e;
	BIsLoad_s2m = BIsLoad_s1m;
    end
end

always @(Phi2 or BValid_s2e or BValid_s2m or BBoosted_s2e or
	BIgnore_s2e or BBoostValid_s2e or BIsLoad_s2e or BIsLoad_s2m) begin
    if (Phi2) begin
	`TICK
	BValid_s1m = BValid_s2e & ~BIgnore_s2e;
	BBoosted_s1m = BBoosted_s2e;
	BBoostValid_s1m = BBoostValid_s2e;
	BIsLoad_s1m = BIsLoad_s2e;
	BIsLoad_s1w = BIsLoad_s2m;
    end
end

//
// Don't toggle datapath latches if instruction was killed
//
assign Alpha1_s1m = AValid_s1m & ~Stall_s1;
assign Beta1_s1m = BValid_s1m & ~Stall_s1;

endmodule				 // bypassSpec

