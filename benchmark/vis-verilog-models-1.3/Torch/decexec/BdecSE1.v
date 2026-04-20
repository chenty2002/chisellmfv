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
//  Title: 	B-side Instruction decoder
//  Created:	Wed Mar 23 14:41:34 1994
//  Author: 	Ricardo E. Gonzalez
//		<ricardog@chroma>
//
//
//  BdecSE1.v,v 7.11 1995/07/25 22:16:36 ricardog Exp
//
//  TORCH Research Group.
//  Stanford University.
//	1993.
//
//	Description: 
//	    BdecS1E generates control signals for Phi1 of EX of an
//	    instruction.  All of these signals are functions only of
//	    the Instruction Register. Phi1 of EX is the first
//	    clock phase after the latching of the instruction, so the
//	    majority of the processor's control signals are generated
//	    here either to be used directly or to be delayed by
//	    latches for use in subsequent pipeline stages.
//
//	Hierarchy: system.processor.decodeExec
//
//  Revision History:
//	Modified:	Sun May 22 16:24:59 1994	<ricardog@chroma>
//	* Changed MemStall_s2/IStall_s1 to Stall_s1.
//	Modified:	Fri Apr  8 16:24:55 1994	<ricardog@chroma>
//	* Fixed verilint errors.
//
`include "torch.h"

module BdecSE1(
    Phi1,
//    Phi2,
    BInstr_s2r,
    Stall_s1,
    BKill_s1e,
    Except_s1w,
    BImmShift16_s2r,
    BImm26Bit_s2r,
    BImmSigned_s2r,
    BDestIsRT_s1e,
    BDestIsRD_s1e,
    BIsLoad_s1e,
    InstrIsStore_s1m,
    InstrIsLoad_s1m,
    MemOp_s1m,
    BAddOp_s2e,
    BSubOp_s2e,
    BSltOp_s2e,
    BSltUOp_s2e,
    BAndOp_s2e,
    BOrOp_s2e,
    BXorOp_s2e,
    BNorOp_s2e,
    BUseT_s1e,
    BUseImm_s1e,
    Syscall_s2m,
    Break_s2m,
    MvFromCop0_s1m,
    MvToCop0_s1e,
    MvToCop0_s1m,
    TLBRead_s1m,
    TLBWriteI_s1m,
    TLBWriteR_s1m,
    TLBProbe_s1m,
    RetFromExcept_s2e,
    BALUDrv_s2e,
    BoostedInstr_s1m,
    BIsBoosted_s2e, 
    BAUopSigned_s2e,
    BWrong_s1e,
    CopRegNum_s1m
    );

//
// Clocks & Stalls
//
input		Phi1;
wire		Phi2;
input		Stall_s1;

//
// Kill instruction
//
input		Except_s1w;
input		BKill_s1e;

//
// Instruction
//
input	[39:0]	BInstr_s2r;

//
// Decoded outputs
//
output		BImmShift16_s2r;
output		BImm26Bit_s2r;
output		BImmSigned_s2r;
output		BDestIsRT_s1e;
output		BDestIsRD_s1e;
output		BIsLoad_s1e;
output		InstrIsStore_s1m;
output		InstrIsLoad_s1m;
output	[2:0]	MemOp_s1m;
output		BAddOp_s2e;
output		BSubOp_s2e;
output		BSltOp_s2e;
output		BSltUOp_s2e;
output		BAndOp_s2e;
output		BOrOp_s2e;
output		BXorOp_s2e;
output		BNorOp_s2e;
output		BUseT_s1e;
output		BUseImm_s1e;
output		Syscall_s2m;
output		Break_s2m;
output		MvFromCop0_s1m;
output		MvToCop0_s1e;
output		MvToCop0_s1m;
output		TLBRead_s1m;
output		TLBWriteI_s1m;
output		TLBWriteR_s1m;
output		TLBProbe_s1m;
output		RetFromExcept_s2e;
output		BALUDrv_s2e;
output		BoostedInstr_s1m;
output		BIsBoosted_s2e;
output		BAUopSigned_s2e;
output		BWrong_s1e;
output	[3:0]	CopRegNum_s1m;

//
// Latches for decoded signals.
//
reg		BDestIsRT_s1e;
reg		BDestIsRD_s1e;
reg		BIsLoad_s1e;
reg		InstrIsStore_s1e;
reg		InstrIsLoad_s1e;
reg	[2:0]	MemOp_s1e;
reg		BAddOp_s1e;
reg		BSubOp_s1e;
reg		BSltOp_s1e;
reg		BSltUOp_s1e;
reg		BAndOp_s1e;
reg		BOrOp_s1e;
reg		BXorOp_s1e;
reg		BNorOp_s1e;
reg		BUseImm_s1e;
reg		Syscall_s1e;
reg		Break_s1e;
reg		MvFromCop0_s1e;
reg		MvToCop0_s1e;
reg		TLBRead_s1e;
reg		TLBWriteI_s1e;
reg		TLBWriteR_s1e;
reg		TLBProbe_s1e;
reg		RetFromExcept_s1e;
reg		BALUDrv_s1e;
reg		BoostedInstr_s1e;
reg		BIsBoosted_s1e;
reg		BAUopSigned_s1e;
reg		BWrong_s1e;
reg	[3:0]	CopRegNum_s1e;

//---------------------------------------------------------------------------
//			 --- Delay Registers ---
//---------------------------------------------------------------------------
//
// Phi2 of EX
//
reg		BAddOp_s2e;
reg		BSubOp_s2e;
reg		BSltOp_s2e;
reg		BSltUOp_s2e;
reg		BAndOp_s2e;
reg		BOrOp_s2e;
reg		BXorOp_s2e;
reg		BNorOp_s2e;
reg		BALUDrv_s2e;
reg		BAUopSigned_s2e;
reg		BIsBoosted_s2e;
reg		InstrIsLoad_s2e;
reg		InstrIsStore_s2e;
reg	[2:0]	MemOp_s2e;
reg		Syscall_s2e;
reg		Break_s2e;
reg		BoostedInstr_s2e;
reg		TLBRead_s2e;
reg		TLBWriteI_s2e;
reg		TLBWriteR_s2e;
reg		TLBProbe_s2e;
reg		RetFromExcept_s2e;
reg		MvToCop0_s2e;
reg		MvFromCop0_s2e;
reg	[3:0]	CopRegNum_s2e;

//
// Phi1 of MEM
//
reg		BoostedInstr_s1m;
reg		TLBRead_s1m;
reg		TLBWriteI_s1m;
reg		TLBWriteR_s1m;
reg		TLBProbe_s1m;
reg	[2:0]	MemOp_s1m;
reg		MvToCop0_s1m;
reg		MvFromCop0_s1m;
reg	[3:0]	CopRegNum_s1m;
reg		InstrIsLoad_s1m;
reg		InstrIsStore_s1m;
reg		Syscall_s1m;
reg		Break_s1m;

//
// Phi2 of MEM
//
reg		Syscall_s2m;
reg		Break_s2m;

//
// Local wires
//
wire		BUseT_s1e;
wire		BCancel_s1e;

initial begin
    BDestIsRT_s1e = 0;
    BDestIsRD_s1e = 0;
    BIsLoad_s1e = 0;
    InstrIsStore_s1e = 0;
    InstrIsLoad_s1e = 0;
    MemOp_s1e = 0;
    BAddOp_s1e = 0;
    BSubOp_s1e = 0;
    BSltOp_s1e = 0;
    BSltUOp_s1e = 0;
    BAndOp_s1e = 0;
    BOrOp_s1e = 0;
    BXorOp_s1e = 0;
    BNorOp_s1e = 0;
    BUseImm_s1e = 0;
    Syscall_s1e = 0;
    Break_s1e = 0;
    MvFromCop0_s1e = 0;
    MvToCop0_s1e = 0;
    TLBRead_s1e = 0;
    TLBWriteI_s1e = 0;
    TLBWriteR_s1e = 0;
    TLBProbe_s1e = 0;
    RetFromExcept_s1e = 0;
    BALUDrv_s1e = 0;
    BoostedInstr_s1e = 0;
    BIsBoosted_s1e = 0;
    BAUopSigned_s1e = 0;
    BWrong_s1e = 0;
    CopRegNum_s1e = 0;
    BAddOp_s2e = 0;
    BSubOp_s2e = 0;
    BSltOp_s2e = 0;
    BSltUOp_s2e = 0;
    BAndOp_s2e = 0;
    BOrOp_s2e = 0;
    BXorOp_s2e = 0;
    BNorOp_s2e = 0;
    BALUDrv_s2e = 0;
    BAUopSigned_s2e = 0;
    BIsBoosted_s2e = 0;
    InstrIsLoad_s2e = 0;
    InstrIsStore_s2e = 0;
    MemOp_s2e = 0;
    Syscall_s2e = 0;
    Break_s2e = 0;
    BoostedInstr_s2e = 0;
    TLBRead_s2e = 0;
    TLBWriteI_s2e = 0;
    TLBWriteR_s2e = 0;
    TLBProbe_s2e = 0;
    RetFromExcept_s2e = 0;
    MvToCop0_s2e = 0;
    MvFromCop0_s2e = 0;
    CopRegNum_s2e = 0;
    BoostedInstr_s1m = 0;
    TLBRead_s1m = 0;
    TLBWriteI_s1m = 0;
    TLBWriteR_s1m = 0;
    TLBProbe_s1m = 0;
    MemOp_s1m = 0;
    MvToCop0_s1m = 0;
    MvFromCop0_s1m = 0;
    CopRegNum_s1m = 0;
    InstrIsLoad_s1m = 0;
    InstrIsStore_s1m = 0;
    Syscall_s1m = 0;
    Break_s1m = 0;
    Syscall_s2m = 0;
    Break_s2m = 0;
end

assign 	Phi2 = ~Phi1;

//
// Generate immediate select signals
//
assign BImm26Bit_s2r = (BInstr_s2r[31:27]==5'd1) || (BInstr_s2r[31:28]==4'd4);
						     // J, JAL or COPz
assign BImmSigned_s2r = ~(BInstr_s2r[31:28]==4'd3);  // not ANDI ORI XORI LUI
assign BImmShift16_s2r = (BInstr_s2r[31:26]==6'd15); // LUI


//
// Derive some control signals from decoded signals
//
assign BUseT_s1e = BAddOp_s1e | BAndOp_s1e | BOrOp_s1e | BXorOp_s1e |
		    BNorOp_s1e;

//
// Compute the s1e control signals.
//
always @(Phi2 or BInstr_s2r) begin
    if (Phi2) begin
	`TICK
	// Check if A side instruction - used for BIgnore signal
	BWrong_s1e = ((BInstr_s2r[31:28]==4'd5) || // Branch
	    ((BInstr_s2r[31:29]==3'd0) && (BInstr_s2r[28:26]!=3'd0)) ||
	    // Branch & jumps
	    ((BInstr_s2r[31:26]==6'd0) &&
		((BInstr_s2r[5:3]==3'd0) || // Shifts
		    (BInstr_s2r[5:4]==2'd1) || // Mult/Div
		    (BInstr_s2r[5:2]==4'd2)))); // Jr/Jalr

	// Determine which of the register specifier
	// fields if any specifies a destination to
	// be written during WB. Zero means no dest.
	// Three operand instructions
	if (// All SPECIAL ops
	    (BInstr_s2r[31:26]==6'd0) &
	    // Exclude JR, SYSCALL, BREAK
	    ~(((BInstr_s2r[5:3]==3'd1) & (BInstr_s2r[2:0]!=3'd1))
	    // Exclude MULT(U)/DIV(U)
	    | (BInstr_s2r[5:3]==3'd3) |
	    // Exclude MTHI, MTLO
	    ((BInstr_s2r[5:3]==3'd2) & (BInstr_s2r[1]==1'b1)))) begin
		BDestIsRD_s1e = `TRUE;
		BDestIsRT_s1e = `FALSE;
	end
	else begin
	    // All loads and immediate ALU ops, MF, CF
	    if ((BInstr_s2r[31:29]==3'd1) | (BInstr_s2r[31:29]==3'd4)||
		((BInstr_s2r[31:28]==4'd4) & (BInstr_s2r[25:23]==3'd0))) begin
		    BDestIsRD_s1e = `FALSE;
		    BDestIsRT_s1e = `TRUE;
	    end
	    else begin
		// No destination
		    BDestIsRD_s1e = `FALSE;
		    BDestIsRT_s1e = `FALSE;
	    end
	end

	// Boosted instruction
	BIsBoosted_s1e =
	    (BInstr_s2r[37] | BInstr_s2r[36]) |
	    (BInstr_s2r[35] | BInstr_s2r[34]) |
	    (BInstr_s2r[33] | BInstr_s2r[32]);

	// Boosted load
	//BoostedInstr_s1e = ((BInstr_s2r[31]==1'd1) && (BInstr_s2r[29]==1'd0))
	BoostedInstr_s1e = (BInstr_s2r[31]==1'd1)
	    & ((BInstr_s2r[37] | BInstr_s2r[36]) |
		(BInstr_s2r[35] | BInstr_s2r[34]) |
		(BInstr_s2r[33] | BInstr_s2r[32]));
		
	// Loads, Stores
	InstrIsLoad_s1e = (BInstr_s2r[31]==1'd1) & (BInstr_s2r[29]==1'd0);
	InstrIsStore_s1e = (BInstr_s2r[31]==1'd1) & (BInstr_s2r[29]==1'd1);

	// For both loads and stores - 3 bit opcode specifier
	// Load word coprocessor not supported, set to 7 if not L/S
	MemOp_s1e = (BInstr_s2r[31:30]==2'd2) ? BInstr_s2r[28:26] : 3'd7;
		
	//Signed ADD/SUB i.e. ADDI, ADD, SUB
	BAUopSigned_s1e = ((BInstr_s2r[31:26]==6'b001000) |	// ADDI
			    ((BInstr_s2r[31:26]==6'd0) &       // SPECIAL
				(BInstr_s2r[5:3]==3'd4) &   
			    ((BInstr_s2r[2:0]==3'd0) |		// ADD
				(BInstr_s2r[2:0]==3'd2))));	// SUB


	// ADD, ADDU, ADDI, ADDIU, Loads and Stores
	BAddOp_s1e = ((BInstr_s2r[31:26]==6'd0) & (BInstr_s2r[5:1]==5'd16)) |
			(BInstr_s2r[31:27]==5'd4) | (BInstr_s2r[31]==1'b1);

	// SUB, SUBU
	BSubOp_s1e = ((BInstr_s2r[31:26]==6'd0) & (BInstr_s2r[5:1]==5'd17));

	// SLT, SLTI
	BSltOp_s1e = ((BInstr_s2r[31:26]==6'd0) & (BInstr_s2r[5:0]==6'd42)) |
			(BInstr_s2r[31:26]==6'd10);

	// SLTU, SLTIU
	BSltUOp_s1e = ((BInstr_s2r[31:26]==6'd0) & (BInstr_s2r[5:0]==6'd43)) |
			(BInstr_s2r[31:26]==6'd11);

	// AND, ANDI
	BAndOp_s1e = ((BInstr_s2r[31:26]==6'd0) & (BInstr_s2r[5:0]==6'd36)) |
			(BInstr_s2r[31:26]==6'd12);

	// OR, ORI, LUI
	BOrOp_s1e = ((BInstr_s2r[31:26]==6'd0) & (BInstr_s2r[5:0]==6'd37)) |
			(BInstr_s2r[31:26]==6'd13) |
			(BInstr_s2r[31:26]==6'd15);

	// XOR, XORI
	BXorOp_s1e = ((BInstr_s2r[31:26]==6'd0) &
		      (BInstr_s2r[5:0]==6'd38)) |
			(BInstr_s2r[31:26]==6'd14);

	// NOR
	BNorOp_s1e = (BInstr_s2r[31:26]==6'd0) & (BInstr_s2r[5:0]==6'd39);

	// ALU Immediates, Loads, Stores, Branches
	BUseImm_s1e = ((BInstr_s2r[31:29]==3'd1) | (BInstr_s2r[31]==1'b1)) |
		((BInstr_s2r[31:29]==3'd0) & (BInstr_s2r[28:26]!=3'd0)) |
			(BInstr_s2r[31:26]==6'd15);

	// SYSCALL
	Syscall_s1e = (BInstr_s2r[31:26]==6'd0) & (BInstr_s2r[5:0]==6'd12);

	// BREAK
	Break_s1e = (BInstr_s2r[31:26]==6'd0) & (BInstr_s2r[5:0]==6'd13);

	// MFCz, CFCz
	MvFromCop0_s1e = (BInstr_s2r[31:26]==6'd16) &(BInstr_s2r[25:23]==3'd0);

	// Regfile reads from MemBus
	BIsLoad_s1e = 		// Load or MvFromCop0
		((BInstr_s2r[31]==1'b1) & (BInstr_s2r[29]==1'b0)) | 
		((BInstr_s2r[31:26]==6'd16) & (BInstr_s2r[25:23]==3'd0));

	// MTCz, CTCz
	MvToCop0_s1e = (BInstr_s2r[31:26]==6'd16) & (BInstr_s2r[25:23]==3'd1);

	// TLBR
	TLBRead_s1e = (BInstr_s2r[31:26]==6'd16) & (BInstr_s2r[5:0]==6'd1);

	// TLBWI
	TLBWriteI_s1e = (BInstr_s2r[31:26]==6'd16) & (BInstr_s2r[5:0]==6'd2);

	// TLBWR
	TLBWriteR_s1e = (BInstr_s2r[31:26]==6'd16) & (BInstr_s2r[5:0]==6'd6);

	// TLBP
	TLBProbe_s1e = (BInstr_s2r[31:26]==6'd16) & (BInstr_s2r[5:0]==6'd8);

	// RFE
	RetFromExcept_s1e = (BInstr_s2r[31:26]==6'd16) &
			(BInstr_s2r[5:0]==6'd16);

	// ALU Immediates, SPECIALs excluding Shifts, Loads and Stores
	BALUDrv_s1e =   ((BInstr_s2r[31:29]==3'd1)||
			((BInstr_s2r[31:26]==6'd0)&&
			 (BInstr_s2r[5:3]!=3'd0))||
			(BInstr_s2r[31:30]==2'd2));

	// Coprocessor 0 Register number
	CopRegNum_s1e = BInstr_s2r[14:11];

    end
end

//---------------------------------------------------------------------------
//			 --- Delay Latches ---
//---------------------------------------------------------------------------
assign BCancel_s1e = ~(BKill_s1e | Except_s1w);

always @(Phi1 or Stall_s1 or BAddOp_s1e or BSubOp_s1e or BSltOp_s1e or
	BSltUOp_s1e or BAndOp_s1e or BOrOp_s1e or BXorOp_s1e or
	BNorOp_s1e or BAUopSigned_s1e or BALUDrv_s1e or Syscall_s1e or
	Break_s1e or BIsBoosted_s1e or TLBRead_s1e or TLBWriteI_s1e or
	TLBWriteR_s1e or TLBProbe_s1e or BoostedInstr_s1e or
	MemOp_s1e or InstrIsLoad_s1e or InstrIsStore_s1e or
	CopRegNum_s1e or MvToCop0_s1e or MvFromCop0_s1e or
	RetFromExcept_s1e or BCancel_s1e) begin
    if (Phi1 & ~Stall_s1 & ~BKill_s1e) begin
	`TICK
	BAddOp_s2e	= BAddOp_s1e;
	BSubOp_s2e	= BSubOp_s1e;
	BSltOp_s2e	= BSltOp_s1e;
	BSltUOp_s2e	= BSltUOp_s1e;
	BAndOp_s2e	= BAndOp_s1e;
	BOrOp_s2e	= BOrOp_s1e;
	BXorOp_s2e	= BXorOp_s1e;
	BNorOp_s2e	= BNorOp_s1e;
	BAUopSigned_s2e	= BAUopSigned_s1e;
    end

    if (Phi1 & ~Stall_s1) begin
	BALUDrv_s2e	= BALUDrv_s1e & BCancel_s1e;
	Syscall_s2e	= Syscall_s1e & BCancel_s1e;
	Break_s2e	= Break_s1e & BCancel_s1e;
	BIsBoosted_s2e	= BIsBoosted_s1e & BCancel_s1e;
	BoostedInstr_s2e = BoostedInstr_s1e & BCancel_s1e;
	TLBRead_s2e	= TLBRead_s1e & BCancel_s1e;
	TLBWriteI_s2e	= TLBWriteI_s1e & BCancel_s1e;
	TLBWriteR_s2e	= TLBWriteR_s1e & BCancel_s1e;
	TLBProbe_s2e	= TLBProbe_s1e & BCancel_s1e;
	RetFromExcept_s2e = RetFromExcept_s1e & BCancel_s1e;
	MemOp_s2e	= MemOp_s1e;
	MvToCop0_s2e	= MvToCop0_s1e & BCancel_s1e;
	MvFromCop0_s2e	= MvFromCop0_s1e & BCancel_s1e;
	CopRegNum_s2e	= CopRegNum_s1e;
	InstrIsLoad_s2e	= InstrIsLoad_s1e & BCancel_s1e;
	InstrIsStore_s2e = InstrIsStore_s1e & BCancel_s1e;
    end
end

always @(Phi2 or Syscall_s2e or Break_s2e or BoostedInstr_s2e or
	TLBRead_s2e or TLBWriteI_s2e or TLBWriteR_s2e or TLBProbe_s2e or
	MvToCop0_s2e or MvFromCop0_s2e or CopRegNum_s2e or
	MemOp_s2e or InstrIsLoad_s2e or InstrIsStore_s2e) begin
    if (Phi2) begin
	`TICK
	Syscall_s1m = Syscall_s2e;
	Break_s1m = Break_s2e;
	BoostedInstr_s1m = BoostedInstr_s2e;
	TLBRead_s1m = TLBRead_s2e;
	TLBWriteI_s1m = TLBWriteI_s2e;
	TLBWriteR_s1m = TLBWriteR_s2e;
	TLBProbe_s1m = TLBProbe_s2e;
	MemOp_s1m = MemOp_s2e;
	MvToCop0_s1m = MvToCop0_s2e;
	MvFromCop0_s1m = MvFromCop0_s2e;
	CopRegNum_s1m = CopRegNum_s2e;
	InstrIsLoad_s1m = InstrIsLoad_s2e;
	InstrIsStore_s1m = InstrIsStore_s2e;
    end
end

always @(Phi1 or Syscall_s1m or Break_s1m) begin
    if (Phi1) begin
	`TICK
	Syscall_s2m = Syscall_s1m;
	Break_s2m = Break_s1m;
    end
end

endmodule
