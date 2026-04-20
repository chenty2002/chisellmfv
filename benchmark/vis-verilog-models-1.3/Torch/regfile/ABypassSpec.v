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
//  Title: 	A-side Register Specifier Bypass
//  Created:	Feb 20 1992
//  Author: 	Ricardo E. Gonzalez
//		<ricardog@bill>
//
//
//  ABypassSpec.v,v 7.21 1995/08/07 22:40:36 ricardog Exp
//
//  TORCH Research Group.
//  Stanford University.
//	1992.
//
//	Description: This module contains the hardware needed to do bypass
//  	    detection. It also contains some random logic needed to deal 
//  	    with ponging, exceptions and such stuff. Anything related to the
//  	    register specifiers should be in here. The module compare contains
//  	    a set of four specifier comparators.
//
//  	Hierarchy: system.processor.regFile.ABypass
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

module ABypassSpec(
    Phi1,
//    Phi2, 
    ARSSpec_s2r,
    ARTSpec_s2r,
    ARDSpec_s2r,
    BRTSpec_s2r,
    BRDSpec_s2r,
    ADestIsRD_s1e,
    ADestIsRT_s1e,
    ADestIs31_s1e,
    BDestIsRD_s1e,
    BDestIsRT_s1e,
    BIsLoad_s1e,
    ADestPtr_v1e,
    ADestBoostValid_v1e,
    AKill_s1e,
    AIgnore_s2e,
    BDestPtr_v1e,
    BDestBoostValid_v1e,
    BKill_s1e,
    BIgnore_s2e,
    Commit_s1e,
    Squash_s1e,
    Except_s1w,
    Stall_s1,
    Stall_s2,
    IStall_s1,
    MemStall_s1,
    Alpha2_s2e,
    Alpha1_s1m,
    Alpha2_s2m,
    Beta2_s2e,
    Beta1_s1m,
    Beta2_s2m,
    Delta2_q2,
    ASDSpec_w,
    ATRSpec_w,
    ASBypassLoad_s1e,
    ATBypassLoad_s1e,
    ASBypassLoad_b_s1e,
    ATBypassLoad_b_s1e,
    ASBypassSel_s1e,
    ATBypassSel_s1e
    );



//
// Clocks & Stalls
//
input		Phi1;
wire		Phi2;
input		Stall_s1;
input		Stall_s2;
input		IStall_s1;
input		MemStall_s1;


//
// Register specifiers (from instruction)
//
input	[5:0]	ARSSpec_s2r;
input	[5:0]	ARTSpec_s2r;
input	[5:0]	ARDSpec_s2r;
input	[5:0]	BRTSpec_s2r;
input	[5:0]	BRDSpec_s2r;

//
// Destination register specifier selector
//
input		ADestIsRD_s1e, ADestIsRT_s1e, ADestIs31_s1e;
input		BDestIsRD_s1e, BDestIsRT_s1e, BIsLoad_s1e;

//
// Boosting information
//
input		ADestPtr_v1e;		 // which phys reg to WB to
input		ADestBoostValid_v1e;	 // this reg has been written as boost
input		BDestPtr_v1e;		 // which phys reg to WB to
input		BDestBoostValid_v1e;	 // this reg has been written as boost

//
// Instruction WB cancell
//
input		AKill_s1e;		 // A-side instruction is bogus
input		AIgnore_s2e;		 // inst issued to both sides, A is bad
input		BKill_s1e;		 // B-side instruction is bogus
input		BIgnore_s2e;		 // inst issued to both sides, B is bad

//
// Branches & Exceptions
//
input		Commit_s1e;
input		Squash_s1e;
input		Except_s1w;


//--------------------------------------------------------------------------
//                      ---- Outputs ----
//--------------------------------------------------------------------------
output		Alpha2_s2e;		 // Qualified clock for A datapath
output		Alpha1_s1m;		 // Qualified clock for A datapath
output		Alpha2_s2m;		 // Qualified clock for A datapath
output		Beta2_s2e;		 // Qualified clock for B datapath
output		Beta1_s1m;		 // Qualified clock for B datapath
output		Beta2_s2m;		 // Qualified clock for B datapath
output		Delta2_q2;		 // Qualified clock Phi2 & ~IStall
output	[7:0]	ASDSpec_w;		 // specifiers for RF read/write
output	[6:0]	ATRSpec_w;		 // specifiers for RF read/read
output		ASBypassLoad_s1e;	 // select for load bypass fast mux
output		ASBypassLoad_b_s1e;	 // same but opposite polarity
output		ATBypassLoad_s1e;	 // Same again but now for T bus
output		ATBypassLoad_b_s1e;	 // 
output	[4:0]	ASBypassSel_s1e;	 // 5-bit bypass mux control lines
output	[4:0]	ATBypassSel_s1e;	 // S & T buses (A-side)


//
// Destination register specifier chains
//
reg	[5:0]	ARTSpec_s1e;
reg	[5:0]	ARDSpec_s1e;

wire	[8:0]	ADest_s1e;
reg	[8:0]	ADest_s2e;
reg	[8:0]	ADest_s1m;
reg	[8:0]	ADest_s2m;
reg	[8:0]	ADest_s1w;

reg	[5:0]	BRTSpec_s1e;
reg	[5:0]	BRDSpec_s1e;

wire	[9:0]	BDest_s1e;
reg	[9:0]	BDest_s2e;
reg	[9:0]	BDest_s1m;
reg	[9:0]	BDest_s2m;
reg	[9:0]	BDest_s1w;

//
// These were registers used to behaviorally model "random" logic needed.
//
wire	[8:0]	ADest_v1e;
wire	[8:0]	ADest_v2e;
wire	[8:0]	ADest_v1m;
wire	[9:0]	BDest_v1e;
wire	[9:0]	BDest_v2e;
wire	[9:0]	BDest_v1m;

//
// Delayed version of Stall
//
reg		IStall_s2;
reg		MemStall_s2;

wire	[4:0]	ASBypassSel_v2r;
wire	[4:0]	ATBypassSel_v2r;

reg	[4:0]	ASBypassSel_s1e;
reg	[4:0]	ATBypassSel_s1e;

//
// For clock qualification
//
wire		Delta2_q2;

//
// random logic signals
//
wire	    ANoDest_s1e;		 // No destination for WB. Should
wire	    BNoDest_s1e;		 // really be in the decoders
wire	    ADestValid_s1e;		 // Valid bit for WB to register file
wire	    BDestValid_s1e;
wire	    ADestIsZero_v1e;
wire	    BDestIsZero_v1e;
wire	    BWasLoad_s1w;

//
// Kill Chain
//
reg	    AKill_s2e;
reg	    AKill_s1m;
reg	    BKill_s2e;
reg	    BKill_s1m;

initial begin
    ARTSpec_s1e = 0;
    ARDSpec_s1e = 0;
    ADest_s2e = 0;
    ADest_s1m = 0;
    ADest_s2m = 0;
    ADest_s1w = 0;
    BRTSpec_s1e = 0;
    BRDSpec_s1e = 0;
    BDest_s2e = 0;
    BDest_s1m = 0;
    BDest_s2m = 0;
    BDest_s1w = 0;
    IStall_s2 = 0;
    MemStall_s2 = 0;
    ASBypassSel_s1e = 0;
    ATBypassSel_s1e = 0;
    AKill_s2e = 0;
    AKill_s1m = 0;
    BKill_s2e = 0;
    BKill_s1m = 0;
end

assign Phi2 = ~Phi1;

//--------------------------------------------------------------------------
//                    ---- Control Logic ----
//--------------------------------------------------------------------------
//
// Qualify the clocks
//
assign Delta2_q2 = (~IStall_s2 & BDest_s2m[`LOAD_BIT]) & Phi2;

//
// If no source set BNoDest_s1e and ANoDest_s1e;
//
assign ANoDest_s1e = ~(ADestIsRD_s1e | ADestIsRT_s1e | ADestIs31_s1e);
assign BNoDest_s1e = ~(BDestIsRD_s1e | BDestIsRT_s1e);

//
// Kill WB if instr is bogus or no destination (more reasons later)
//
assign ADestValid_s1e = ~(AKill_s1e | ANoDest_s1e);
assign BDestValid_s1e = ~(BKill_s1e | BNoDest_s1e);

//
// If B-side instr was a load do some special processing. Also don't
// drive the source operand busses if the instruction is being killed.
//
assign BWasLoad_s1w = BDest_s1w[`LOAD_BIT];

assign ASBypassLoad_s1e = ASBypassSel_s1e[`BYPASS_BMEM_BIT] & BWasLoad_s1w
			& ~AKill_s1e;
assign ATBypassLoad_s1e = ATBypassSel_s1e[`BYPASS_BMEM_BIT] & BWasLoad_s1w
			& ~AKill_s1e;
assign ASBypassLoad_b_s1e = ~(ASBypassSel_s1e[`BYPASS_BMEM_BIT] & BWasLoad_s1w)
			& ~AKill_s1e;
assign ATBypassLoad_b_s1e = ~(ATBypassSel_s1e[`BYPASS_BMEM_BIT] & BWasLoad_s1w)
			& ~AKill_s1e;

//--------------------------------------------------------------------------
//                   ---- Bypass Compares ----
//--------------------------------------------------------------------------
//
// Do all the bypass compares The module will return a 1-hot signal used to
// drive the bypass mux
//
compares ARSCompare ({`VALID, ARSSpec_s2r},
                ADest_s2e[`VALID_BIT:0], ADest_s2m[`VALID_BIT:0],
    	    	BDest_s2e[`VALID_BIT:0], BDest_s2m[`VALID_BIT:0],
                ASBypassSel_v2r);

compares ARTCompare ({`VALID, ARTSpec_s2r},
                ADest_s2e[`VALID_BIT:0], ADest_s2m[`VALID_BIT:0],
    	    	BDest_s2e[`VALID_BIT:0], BDest_s2m[`VALID_BIT:0],
                ATBypassSel_v2r);

//
// Now latch the control signals so they can be used in the beginning of
// next phase.
//
always @ (Phi2 or Stall_s2 or ASBypassSel_v2r)
  if (Phi2 & ~Stall_s2) ASBypassSel_s1e = ASBypassSel_v2r;
always @ (Phi2 or Stall_s2 or ATBypassSel_v2r)
  if (Phi2 & ~Stall_s2) ATBypassSel_s1e = ATBypassSel_v2r;


//--------------------------------------------------------------------------
//                  ---- Register File Signals ----
//--------------------------------------------------------------------------
//  On phi1 the decoders are used for the WB and the result read/write
//  On phi2 they are used for the register file acces
//
MUX2_8 ASDSpec_W(ASDSpec_w, ADest_s1w[7:0],
			{`DONT_CARE, `NOT_VALID, ARSSpec_s2r}, Phi1);

MUX2_7 ATRSpec_W(ATRSpec_w, {ADestValid_s1e, ADest_s1e[5:0]},
			{`NOT_VALID, ARTSpec_s2r}, Phi1);

//--------------------------------------------------------------------------
//                    ---- Destination Chain ----
// I am trying to mix a structural and behavioral definitions. The 
// latches are all explicitly declared, but all the good stuff, i.e. 
// all the logic between stages is contained in always blocks.
// This way I can keep the behavioral definition of the logic, and have a
// more strutural file.
//--------------------------------------------------------------------------


//--------------------------------------------------------------------------
//                          ---- A-side ----
//--------------------------------------------------------------------------
//
// From RF-2 to EX-1
//
always @ (Phi2 or Stall_s2 or ARTSpec_s2r)
  if (Phi2 & ~Stall_s2) ARTSpec_s1e = ARTSpec_s2r;
always @ (Phi2 or Stall_s2 or ARDSpec_s2r)
  if (Phi2 & ~Stall_s2) ARDSpec_s1e = ARDSpec_s2r;
MUX4_9 ADest_V2R(ADest_s1e,
		9'b0,
		{`DONT_CARE, `DONT_CARE, `VALID, 1'b0, 5'd31},
		{`DONT_CARE, `DONT_CARE, `VALID, ARTSpec_s1e},
		{`DONT_CARE, `DONT_CARE, `VALID, ARDSpec_s1e},
		ANoDest_s1e, ADestIs31_s1e, ADestIsRT_s1e, ADestIsRD_s1e);
//
// From EX-1 to EX-2
//
COMP_5 ADestIsZero(ADestIsZero_v1e, ADest_s1e[`SPEC_BIT:0], 5'b0);
always @ (Phi1 or Stall_s1 or ADest_v1e)
  if (Phi1 & ~Stall_s1) ADest_s2e = ADest_v1e;

//
// From EX-2 to MEM-1
//
always @ (Phi2 or Stall_s2 or ADest_v2e)
  if (Phi2 & ~Stall_s2) ADest_s1m = ADest_v2e;

//
// From MEM-1 to MEM-2
//
always @ (Phi1 or Stall_s1 or ADest_v1m)
  if (Phi1 & ~Stall_s1) ADest_s2m = ADest_v1m;

//
// From MEM-2 to WB-1
//
always @ (Phi2 or Stall_s2 or ADest_s2m)
  if (Phi2 & ~Stall_s2) ADest_s1w = ADest_s2m;


//--------------------------------------------------------------------------
//                        ---- B-side ----
//--------------------------------------------------------------------------
//
// From RF-2 to EX-1
//
always @ (Phi2 or Stall_s2 or BRTSpec_s2r)
  if (Phi2 & ~Stall_s2) BRTSpec_s1e = BRTSpec_s2r;
always @ (Phi2 or Stall_s2 or BRDSpec_s2r)
  if (Phi2 & ~Stall_s2) BRDSpec_s1e = BRDSpec_s2r;
MUX3_10	BDest_V2R(BDest_s1e,
		10'b0,
		{`DONT_CARE, `DONT_CARE, `DONT_CARE, `VALID, BRTSpec_s1e},
		{`DONT_CARE, `DONT_CARE, `DONT_CARE, `VALID, BRDSpec_s1e},
		BNoDest_s1e, BDestIsRT_s1e, BDestIsRD_s1e);
//
// From EX-1 to EX-2
//
COMP_5 BDestIsZero(BDestIsZero_v1e, BDest_s1e[`SPEC_BIT:0], 5'b0);
always @ (Phi1 or Stall_s1 or BDest_v1e)
  if (Phi1 & ~Stall_s1) BDest_s2e = BDest_v1e;

//
// From EX-2 to MEM-1
//
always @ (Phi2 or Stall_s2 or BDest_v2e)
  if (Phi2 & ~Stall_s2) BDest_s1m = BDest_v2e;

//
// From MEM-1 to MEM-2
//
always @ (Phi1 or Stall_s1 or BDest_v1m)
  if (Phi1 & ~Stall_s1) BDest_s2m = BDest_v1m;

//
// From MEM-2 to WB-1
//
always @ (Phi2 or Stall_s2 or BDest_s2m)
  if (Phi1 & ~Stall_s1) BDest_s1w = BDest_s2m;


//--------------------------------------------------------------------------
//			 ---- Delay IStall ----
//--------------------------------------------------------------------------
always @(Phi1 or IStall_s1 or MemStall_s1) begin
    if (Phi1) begin
	IStall_s2 = IStall_s1;
	MemStall_s2 = MemStall_s1;
    end
end

//--------------------------------------------------------------------------
//			 ---- Random Logic ----
//--------------------------------------------------------------------------

assign ADest_v1e[`SPEC_BIT:0]	   = ADest_s1e[`SPEC_BIT:0];
assign ADest_v1e[`HARD_DEST_BIT]   = ADestPtr_v1e;
assign ADest_v1e[`VALID_BIT]       = ADestValid_s1e &
       ~(ADestIsZero_v1e | Except_s1w);
assign ADest_v1e[`BOOST_BIT]       = ADest_s1e[`BOOST_BIT] & ~Commit_s1e;
assign ADest_v1e[`BOOST_VALID_BIT] = ADestBoostValid_v1e & 
	~(Commit_s1e | Squash_s1e);

assign ADest_v2e[`SPEC_BIT:0]  	   = ADest_s2e[`SPEC_BIT:0];
assign ADest_v2e[`BOOST_BIT]   	   = ADest_s2e[`BOOST_BIT];
assign ADest_v2e[`HARD_DEST_BIT]   = ADest_s2e[`HARD_DEST_BIT];
assign ADest_v2e[`BOOST_VALID_BIT] = ADest_s2e[`BOOST_VALID_BIT];
assign ADest_v2e[`VALID_BIT]       = ADest_s2e[`VALID_BIT] & ~AIgnore_s2e;


assign ADest_v1m[`SPEC_BIT:0]  	   = ADest_s1m[`SPEC_BIT:0];
assign ADest_v1m[`HARD_DEST_BIT]   = ADest_s1m[`HARD_DEST_BIT];
assign ADest_v1m[`VALID_BIT]	   = ADest_s1m[`VALID_BIT] & ~Except_s1w;
assign ADest_v1m[`BOOST_BIT]       = ADest_s1m[`BOOST_BIT] ^
       (Commit_s1e & ADest_s1m[`BOOST_BIT]);
assign ADest_v1m[`BOOST_VALID_BIT] = ADest_s1m[`BOOST_VALID_BIT] & 
       ~(Commit_s1e | Squash_s1e);


assign BDest_v1e[`SPEC_BIT:0]  	   = BDest_s1e[`SPEC_BIT:0];
assign BDest_v1e[`HARD_DEST_BIT]   = BDestPtr_v1e;
assign BDest_v1e[`LOAD_BIT]  	   = BIsLoad_s1e;
assign BDest_v1e[`VALID_BIT]       = BDestValid_s1e &
       ~(BDestIsZero_v1e | Except_s1w);
assign BDest_v1e[`BOOST_BIT]       = BDest_s1e[`BOOST_BIT] & ~Commit_s1e;
assign BDest_v1e[`BOOST_VALID_BIT] = BDestBoostValid_v1e &
       ~(Commit_s1e | Squash_s1e);


assign BDest_v2e[`SPEC_BIT:0]  	   = BDest_s2e[`SPEC_BIT:0];
assign BDest_v2e[`BOOST_BIT]   	   = BDest_s2e[`BOOST_BIT];
assign BDest_v2e[`HARD_DEST_BIT]   = BDest_s2e[`HARD_DEST_BIT];
assign BDest_v2e[`BOOST_VALID_BIT] = BDest_s2e[`BOOST_VALID_BIT];
assign BDest_v2e[`LOAD_BIT]    	   = BDest_s2e[`LOAD_BIT];
assign BDest_v2e[`VALID_BIT]	   = BDest_s2e[`VALID_BIT] & ~BIgnore_s2e;


assign BDest_v1m[`SPEC_BIT:0]  	   = BDest_s1m[`SPEC_BIT:0];
//    BDest_v1m[`VALID_BIT]   	= BDest_s1m[`VALID_BIT];
assign BDest_v1m[`HARD_DEST_BIT]   = BDest_s1m[`HARD_DEST_BIT];
assign BDest_v1m[`LOAD_BIT]    	   = BDest_s1m[`LOAD_BIT];
assign BDest_v1m[`VALID_BIT]	   = BDest_s1m[`VALID_BIT] & ~Except_s1w;
assign BDest_v1m[`BOOST_BIT]       = BDest_s1m[`BOOST_BIT] ^
       (Commit_s1e & BDest_s1m[`BOOST_VALID_BIT]);
assign BDest_v1m[`BOOST_VALID_BIT] = BDest_s1m[`BOOST_VALID_BIT] &
       ~(Commit_s1e | Squash_s1e);

//---------------------------------------------------------------------------
//			   --- Kill Chain ---
//---------------------------------------------------------------------------
//
// Don't toggle datapath latches if instruction was killed
//
assign Alpha2_s2e = ADest_s2e[`VALID_BIT] & ~Stall_s2;
assign Alpha1_s1m = ADest_s1m[`VALID_BIT] & ~Stall_s1;
assign Alpha2_s2m = ADest_s2m[`VALID_BIT] & ~Stall_s2;

assign Beta2_s2e = BDest_s2e[`VALID_BIT] & ~Stall_s2;
assign Beta1_s1m = BDest_s1m[`VALID_BIT] & ~Stall_s1;
assign Beta2_s2m = BDest_s2m[`VALID_BIT] & ~Stall_s2;

endmodule				 // bypassSpec


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
//  Title: 	Compares
//  Created:	Nov 4 1991
//  Author: 	Ricardo E. Gonzalez
//		<ricardog@bill>
//
//
//  compares.v,v 7.5 1995/01/28 00:50:46 ricardog Exp
//
//  TORCH Research Group.
//  Stanford University.
//	1992.
//
//	Description: This module contains one set of register specifier
//  	    comparators, i.e. four comparators. It takes as input RS or RT
//  	    and generates a 5-bit 1-hot signal that goes to a mux in the
//  	    datapath sections that does the actual bypass.
//
//	Hierarchy: system.processor.regFile.bypass.bypassSpec.compares
//
//  Revision History:
//	Modified:	Sat Apr  9 17:36:57 1994	<ricardog@chroma>
//	* Fixed verilint errors.
//	Modified:	Tue Mar 29 19:01:55 1994	<ricardog@chroma>
//	* Removed old code.
//	Modified:	Feb 20 1992	<ricardog@bill>
//	* Structural version.
//

module compares (
    SrcSpec_s2r,
    ADest_s2e,
    ADest_s2m,
    BDest_s2e,
    BDest_s2m,
    BypassSel_v2r
    );

//
// Inputs
//
input	[6:0]	SrcSpec_s2r;
input	[6:0]	ADest_s2e, ADest_s2m;
input	[6:0]	BDest_s2e, BDest_s2m;

//
// Outputs
//
output	[4:0]	BypassSel_v2r;

//
// Local Wires
//
wire	[4:0]	compareRes_v2r;

//
// CompareRes_v2r is NOT a 1-hot output type signal, but BypassSel_v2r is.
// This signal is then latched in bypassSpec. 
// Four comparators here. This module (compares) will itself be instantiated
// 4 times, for a total of 16 comparators.
//
assign compareRes_v2r[`NO_BYPASS_BIT] = 1'b1;

COMP_7 compareBm (compareRes_v2r[`BYPASS_BMEM_BIT], SrcSpec_s2r, BDest_s2m);
COMP_7 compareAm (compareRes_v2r[`BYPASS_AMEM_BIT], SrcSpec_s2r, ADest_s2m);
COMP_7 compareBe (compareRes_v2r[`BYPASS_BEX_BIT],  SrcSpec_s2r, BDest_s2e);
COMP_7 compareAe (compareRes_v2r[`BYPASS_AEX_BIT],  SrcSpec_s2r, ADest_s2e);

PRIORITY_5 BypassSel_V2R (BypassSel_v2r, compareRes_v2r);


endmodule				  // compares


module PRIORITY_5 (out, in);
    output [4:0] out;
    input [4:0]  in;

    assign out =
	   in[4] ? 5'b10000 :
	   in[3] ? 5'b01000 :
	   in[2] ? 5'b00100 :
	   in[1] ? 5'b00010 :
	   in[0] ? 5'b00001 :
	   5'b00000;
    
endmodule // PRIORITY_5


module COMP_7 (match, in1, in2);
    output match;
    input [6:0] in1, in2;

    assign match = (in1 == in2);

endmodule // COMP_7


module COMP_5 (match, in1, in2);
    output match;
    input [4:0] in1, in2;

    assign match = (in1 == in2);

endmodule // COMP_7


module MUX2_8 (out, in1, in2, sel);
    output [7:0] out;
    input [7:0]  in1, in2;
    input 	 sel;

    assign out = sel ? in1 : in2;

endmodule // MUX2_8


module MUX2_7 (out, in1, in2, sel);
    output [6:0] out;
    input [6:0]  in1, in2;
    input 	 sel;

    assign out = sel ? in1 : in2;

endmodule // MUX2_7


module MUX4_9 (out, in1, in2, in3, in4, sel1, sel2, sel3, sel4);
    output [8:0] out;
    input [8:0]  in1, in2, in3, in4;
    input 	 sel1, sel2, sel3, sel4;

    assign out = {9{sel1}} & in1 | {9{sel2}} & in2 |
	   {9{sel3}} & in3 | {9{sel4}} & in4;

endmodule // MUX4_9


module MUX3_10 (out, in1, in2, in3, sel1, sel2, sel3);
    output [9:0] out;
    input [9:0]  in1, in2, in3;
    input 	 sel1, sel2, sel3;

    assign out = {10{sel1}} & in1 | {10{sel2}} & in2 | {10{sel3}} & in3;

endmodule // MUX3_10
