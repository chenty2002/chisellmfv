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
//  Title: 	CP0 Interrupt Encoder
//  Created:	Thu Mar 24 16:39:24 1994
//  Author: 	Ricardo E. Gonzalez
//		<ricardog@chroma>
//
//
//  cp0IntEncoder.v,v 7.15 1995/01/28 00:40:56 ricardog Exp
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
//	Modified:	Sun May 22 16:11:01 1994	<ricardog@chroma>
//	* Changed MemStall_s2 to Stall_s1.
//	Modified:	Fri Apr  8 15:10:31 1994	<ricardog@chroma>
//	* Fixed verilint errors.
//	Modified:	06-02-92
//	* fixed interrupt masking; added IStall_s1, IEc_s2.
//	* added Boosted Exception cause E_Bst, needs BExTaken_s1w  
//	Modified:	04-13-92
//	* added MemStall_s2 support
//	Modified:	04-10-92
//	* cleaned-up using vcheck
//	Modified:	03-16-92
//	* changed priorities: seq. except > "noted" boosted except
//	Modified:	03-13-92
//	* added boosted exceptions; fixed exception priorities
//
`include "torch.h"

module cp0IntEncoder(
    Phi1,
//    Phi2,
    Stall_s1,
    Except_s1w,
    IEc_s2,
    BExTaken_s1w, 
    Reset_s1,
    AALUOvfl_v2e,
    BALUOvfl_v2e,
    Syscall_s2m,
    Break_s2m,
    TLBRefill_v2m,
    TLBInvalid_v2m,
    TLBModified_v2m,
    MemExcept_s2m,
    Interrupt_w,
    IntPending_s2,
    IntMask_s2,
    InstrIsLoad_s1m,
    InstrIsStore_s1m,
    AIsBoosted_s2e,
    BIsBoosted_s2e,
    SetBoost_s1w,
    BoostedExcept_v2,
    SeqExcept_v2, 
    ExceptionCause_s1w,
    Int_s1,
    Reset_s2,
    TLBL1_s1w
    );

//
// Clocks & Stalls
//
input		Phi1;
wire		Phi2;
input		Stall_s1; 

//
// Exceptions
//
input		Except_s1w;
input		AALUOvfl_v2e;
input		BALUOvfl_v2e;
input		Syscall_s2m;
input		Break_s2m;
input		TLBRefill_v2m;
input		TLBInvalid_v2m;
input		TLBModified_v2m;
input		MemExcept_s2m;   
input	[5:0]	Interrupt_w;
input	[1:0]	IntPending_s2;

//
// Other info needed to process exceptions
//
input	[7:0]	IntMask_s2;		  // Interrupt Mask
input		IEc_s2, BExTaken_s1w;	  // ????
input		InstrIsStore_s1m;
input		InstrIsLoad_s1m;
input		AIsBoosted_s2e;		  // Boosted exception?
input		BIsBoosted_s2e;

//
// Outputs 
//
output		BoostedExcept_v2, SeqExcept_v2; // Type of exception
output		SetBoost_s1w;		  // A or B inst boosted
output	[4:0]	ExceptionCause_s1w;	  // To status register
output	[5:0]	Int_s1;			  // External Interrupt level
input		Reset_s1;
output		Reset_s2;		  // Latched Reset
output		TLBL1_s1w;

//
// Exception codes
//
`define Hi	1'b1
`define Lo	1'b0
`define E_Int	5'd0			  // external interrupt
`define E_Mod	5'd1    		  // tlb modified exception
`define E_TLBL	5'd2			  // tlb exception (load or Ifetch)
`define E_TLBS	5'd3			  // tlb exception (store)
`define E_AdEL	5'd4			  // address error (load)
`define E_AdES	5'd5			  // address error (store)
`define E_Sys	5'd8			  // syscall exception
`define E_Bp	5'd9			  // breakpoint exception
`define E_OvA	5'd12			  // arith overflow exception (A-side)
`define E_OvB	5'd13			  // arith overflow exception (B-side) 
`define E_Bst	5'd14			  // boosted exception 

//
// Internal nodes/variables
//
reg	[4:0]	ExceptionCauseTmp_s1w; 
wire	[4:0]	ExceptionCause_s1w; 
reg	[4:0]	ExceptionCause_v2;
reg		InstrIsLoad_s2m, InstrIsStore_s2m;
reg		AALUOvfl_s1m, AALUOvfl_s2m; 
reg		BALUOvfl_s1m, BALUOvfl_s2m; 
reg		AIsBoosted_s1m, AIsBoosted_s2m; 
reg		BIsBoosted_s1m, BIsBoosted_s2m; 
reg		SetBoost_s1w;
reg	[5:0]	Int_s2;
reg	[5:0]	Int_s1;
reg		Reset_s2;
reg		TLBL1_s1w;
wire		BoostedExcept_v2, SeqExcept_v2;
wire		TLBL1_v2;		  // TLB miss on inst access
wire		TLBL2_v2;		  // TLB miss on load access
wire		TLBS_v2;		  // TLB miss on store access
wire		AdEL_v2;		  // Address error on load
wire		AdES_v2;		  // Address error on store
wire		ExtExcept_s2;


initial begin
    ExceptionCauseTmp_s1w = 0; 
    ExceptionCause_v2 = 0;
    InstrIsLoad_s2m = 0;
    InstrIsStore_s2m = 0;
    AALUOvfl_s1m = 0;
    AALUOvfl_s2m = 0; 
    BALUOvfl_s1m = 0;
    BALUOvfl_s2m = 0; 
    AIsBoosted_s1m = 0;
    AIsBoosted_s2m = 0; 
    BIsBoosted_s1m = 0;
    BIsBoosted_s2m = 0; 
    SetBoost_s1w = 0;
    Int_s2 = 0;
    Int_s1 = 0;
    Reset_s2 = 0;
    TLBL1_s1w = 0;
end

assign Phi2 = ~Phi1;

//
// latch/delay signals,
// sample external interrupts on phi1 
//
always @(Phi1 or Reset_s1) begin
    if (Phi1) Reset_s2 = Reset_s1;
end

always @(Phi1 or Interrupt_w or InstrIsLoad_s1m or InstrIsStore_s1m or
         AIsBoosted_s1m or BIsBoosted_s1m or AALUOvfl_s1m or BALUOvfl_s1m or  
	 Stall_s1 or Except_s1w) begin
    if (Phi1 & ~Stall_s1) begin
	Int_s2           = Interrupt_w;
	AIsBoosted_s2m   = AIsBoosted_s1m & ~Except_s1w;
	BIsBoosted_s2m   = BIsBoosted_s1m & ~Except_s1w;
	InstrIsLoad_s2m  = InstrIsLoad_s1m & ~Except_s1w;
	InstrIsStore_s2m = InstrIsStore_s1m & ~Except_s1w;
	AALUOvfl_s2m     = AALUOvfl_s1m & ~Except_s1w;
	BALUOvfl_s2m     = BALUOvfl_s1m & ~Except_s1w;
    end
end

always @(Phi2 or AIsBoosted_s2e or BIsBoosted_s2e or AIsBoosted_s2m or 
         BIsBoosted_s2m or ExceptionCause_v2 or AALUOvfl_v2e or BALUOvfl_v2e 
         or Int_s2 or TLBL1_v2) begin
    if (Phi2) begin
	Int_s1 = Int_s2;
	AIsBoosted_s1m = AIsBoosted_s2e;
	BIsBoosted_s1m = BIsBoosted_s2e;
	SetBoost_s1w = AIsBoosted_s2m | BIsBoosted_s2m;
	AALUOvfl_s1m = AALUOvfl_v2e;
	BALUOvfl_s1m = BALUOvfl_v2e;
	ExceptionCauseTmp_s1w = ExceptionCause_v2;
        TLBL1_s1w = TLBL1_v2;
    end
end

assign ExceptionCause_s1w = (BExTaken_s1w) ? `E_Bst : ExceptionCauseTmp_s1w;

//------------------------------------------------------------------------
//                    ---- Exception raised ----
//------------------------------------------------------------------------
assign  TLBL1_v2 = (TLBRefill_v2m | TLBInvalid_v2m) & 
			~(InstrIsLoad_s2m | InstrIsStore_s2m);
assign  TLBL2_v2 = (TLBRefill_v2m | TLBInvalid_v2m) & InstrIsLoad_s2m;
assign  TLBS_v2  = (TLBRefill_v2m | TLBInvalid_v2m) & InstrIsStore_s2m;
assign  AdEL_v2  = MemExcept_s2m & InstrIsLoad_s2m;
assign  AdES_v2  = MemExcept_s2m & InstrIsStore_s2m;
assign  ExtExcept_s2 = (| (IntMask_s2[7:0] & {Int_s2[5:0],
			    IntPending_s2[1:0]})) &
			IEc_s2; 

//
// BoostedExcept_v2 and SeqExcept_v2 compute the same function,
// except that one will be asserted when the excepting instruction
// is boosted
//
assign BoostedExcept_v2 = ~Reset_s2 & 
                         (AIsBoosted_s2m &  AALUOvfl_s2m |
                          BIsBoosted_s2m & (BALUOvfl_s2m | 
                                            Syscall_s2m | Break_s2m | 
                                            AdEL_v2 | AdES_v2 | 
                                            TLBL2_v2 | TLBS_v2 | 
                                            TLBModified_v2m)
                         );
assign SeqExcept_v2 = Reset_s2 | ExtExcept_s2 | TLBL1_v2 |  
                     (~AIsBoosted_s2m &  AALUOvfl_s2m |
                      ~BIsBoosted_s2m & (BALUOvfl_s2m | 
                                         Syscall_s2m | Break_s2m | 
                                         AdEL_v2 | AdES_v2 | 
                                         TLBL2_v2 | TLBS_v2 | 
                                         TLBModified_v2m)
                     );
//------------------------------------------------------------------------
// Priority encoder 
//------------------------------------------------------------------------
always @(Reset_s2 or TLBL1_v2 or AALUOvfl_s2m or BALUOvfl_s2m or Syscall_s2m
	 or Break_s2m or AdEL_v2 or AdES_v2 or TLBL2_v2 or TLBS_v2 or
	 TLBModified_v2m or ExtExcept_s2 or AIsBoosted_s2m or
	 BIsBoosted_s2m) begin
    if      (Reset_s2)				ExceptionCause_v2 = `E_Int;
    else if (TLBL1_v2)				ExceptionCause_v2 = `E_TLBL;
    else if (~AIsBoosted_s2m & AALUOvfl_s2m)	ExceptionCause_v2 = `E_OvA; 
    else if (~BIsBoosted_s2m & BALUOvfl_s2m)	ExceptionCause_v2 = `E_OvB; 
    else if (~BIsBoosted_s2m & Syscall_s2m)	ExceptionCause_v2 = `E_Sys; 
    else if (~BIsBoosted_s2m & Break_s2m)	ExceptionCause_v2 = `E_Bp; 
    else if (~BIsBoosted_s2m & AdEL_v2)		ExceptionCause_v2 = `E_AdEL; 
    else if (~BIsBoosted_s2m & AdES_v2)		ExceptionCause_v2 = `E_AdES; 
    else if (~BIsBoosted_s2m & TLBL2_v2)	ExceptionCause_v2 = `E_TLBL;
    else if (~BIsBoosted_s2m & TLBS_v2)		ExceptionCause_v2 = `E_TLBS; 
    else if (~BIsBoosted_s2m & TLBModified_v2m)	ExceptionCause_v2 = `E_Mod; 
    else if (ExtExcept_s2)			ExceptionCause_v2 = `E_Int;
    else					ExceptionCause_v2 = 5'b11111;
end

endmodule
