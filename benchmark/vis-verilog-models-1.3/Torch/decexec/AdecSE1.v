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
//  Title: 	A-side Instruction Decoder
//  Created:	Tue Mar 15 18:35:22 1994
//  Modified:	Mon Mar 21 10:42:14 1994
//	* Added delay latches.	<ricardog@chroma>
//  Author: 	Ricardo E. Gonzalez
//		<ricardog@chroma>
//
//
//  AdecSE1.v,v 7.16 1995/01/28 00:42:54 ricardog Exp
//
//  TORCH Research Group.
//  Stanford University.
//	1993.
//
//	Description: 
// AdecS1E generates control signals for the A side execution unit.
// Most signals are stable Phi1 of EX of an instruction.
// All of these signals are functions only of the Instruction Register
// and "compare" (for branch) results.
//
// Phi1 of EX is the first clock phase after the latching of the instruction,
// so the majority of the processor's control signals are generated here
// either to be used directly or to be delayed by latches for use in
// subsequent pipeline stages.
//
//	Hierarchy: processor.decodeExec.AExecuteUnit
//
//  Revision History:
//	Modified: Thu Jul 21 16:24:00 1994	<ricardog@chroma.Stanford.EDU>
//	* Added Squash regenerationon RFE.
//	Modified:	Sun May 22 16:18:14 1994	<ricardog@chroma>
//	* Changed MemStall_s2/IStall_s1 to Stall_s1
//	Modified:	Fri Apr  8 15:52:05 1994	<ricardog@chroma>
//	* Fixed verilint errors.
//	Modified:	Jan 11 1991 <monsen@leland>
//	* Akill_s1e added to this module.
//	Modified:	Apr 30 1992 <sidirop@chroma>
//	* AAuOpSigned_s1e added.
//
`include "torch.h"

module AdecSE1(
// Clocks & Stalls
    Phi1,
//    Phi2,
    Stall_s1,
    Except_s1w,
// Inputs
    AInstr_s2r,
    AKill_s1e,
    TakenBranch_s2e,
    RetFromExcept_s2e,
    SquashBit_s1,
// Outputs
    AImmShift16_s2r,
    AImm26Bit_s2r,
    AImmSigned_s2r,
    ADestIsRT_s1e,
    ADestIsRD_s1e,
    ADestIs31_s1e,
    AAddOp_s2e,
    ASubOp_s2e,
    ASltOp_s2e,
    ASltUOp_s2e,
    AAndOp_s2e,
    AOrOp_s2e,
    AXorOp_s2e,
    ANorOp_s2e,
    MultOp_s2e,
    DivOp_s2e,
    SignedMDOp_s2e,
    AUseT_s1e,
    AUseImm_s1e,
    LoadHiLo_s2e,
    StoreHiLo_s2e,
    HiLo_s2e,
    AALUDrv_s2e,
    ShiftLeft_s2e,
    ShiftArithmetic_s2e,
    ShifterDrv_s2e,
    PCDrvResult_s2e,
    BEQnext_s1e,
    BNEnext_s1e,
    BLEZnext_s1e,
    BGTZnext_s1e,
    BLTZnext_s1e,
    BGEZnext_s1e,
    ImmPC_s1e,
    RegPC_s1e,
    Commit_s1e,
    Squash_s1e,
    AIsBoosted_s2e,
    AWrong_s1e,
    AAUopSigned_s2e
);

//
// Clocks & Stalls
//
input		Phi1;
wire		Phi2;
input		Stall_s1;

input		Except_s1w;
//
// Inputs
//
input	[39:0]	AInstr_s2r;
input		AKill_s1e;
input		TakenBranch_s2e;
input		RetFromExcept_s2e;	 // RFE in MEM stage
input		SquashBit_s1;		 // Squash bit from previous context
					 // see cp0 for more details
//
// Outputs (decoded signals)
//
output		AImmShift16_s2r;	  // Select immediate type
output		AImm26Bit_s2r;
output		AImmSigned_s2r;
output		ADestIsRT_s1e;		  // To bypass lagic
output		ADestIsRD_s1e;
output		ADestIs31_s1e;
output		AIsBoosted_s2e;
output		Commit_s1e;		  // Commit point is s1e of delay
output		Squash_s1e;
output		AAddOp_s2e;		  // To A-side ALU
output		ASubOp_s2e;
output		ASltOp_s2e;
output		ASltUOp_s2e;
output		AAndOp_s2e;
output		AOrOp_s2e;
output		AXorOp_s2e;
output		ANorOp_s2e;
output		MultOp_s2e;
output		DivOp_s2e;
output		SignedMDOp_s2e;
output		AUseT_s1e;
output		AUseImm_s1e;
output		LoadHiLo_s2e;
output		StoreHiLo_s2e;
output		HiLo_s2e;
output		AALUDrv_s2e;
output		AAUopSigned_s2e;
output		ShiftLeft_s2e;		  // To shifter
output		ShiftArithmetic_s2e;
output		ShifterDrv_s2e;
output		PCDrvResult_s2e;	  // To instruction fetch
output		BEQnext_s1e;		  // Decoded branch signals to the
output		BNEnext_s1e;		  // instruction fetch unit where
output		BLEZnext_s1e;		  // the branch condition is evaluated
output		BGTZnext_s1e;
output		BLTZnext_s1e;
output		BGEZnext_s1e;
output		ImmPC_s1e;		  // Jump instruction
output		RegPC_s1e;		  // Jump register
output		AWrong_s1e;		  // A-inst should not be exec'ed

//
// Register file signals
//
reg		ADestIsRT_s1e;
reg		ADestIsRD_s1e;
reg		ADestIs31_s1e;

//
// AALU signals
//
reg		AAddOp_s1e;
reg		ASubOp_s1e;
reg		ASltOp_s1e;
reg		ASltUOp_s1e;
reg		AAndOp_s1e;
reg		AOrOp_s1e;
reg		AXorOp_s1e;
reg		ANorOp_s1e;
reg		MultOp_s1e;
reg		DivOp_s1e;
reg		SignedMDOp_s1e;
reg		AUseImm_s1e;
reg		LoadHiLo_s1e;
reg		StoreHiLo_s1e;
reg		HiLo_s1e;
reg		AALUDrv_s1e;
reg		AAUopSigned_s1e;

//
// Shifter
//
reg		ShiftLeft_s1e;
reg		ShiftArithmetic_s1e;
reg		ShifterDrv_s1e;

//
// Instruction fetch
//
reg		PCDrvResult_s1e;
reg		PCDrvResult_s2e;
reg		immPC_s1e;
reg		regPC_s1e;
reg		AIsBoosted_s1e;
reg		AIsBoosted_s2e;
reg		AWrong_s1e;
reg		commit_s1e;		  // _s1e of the delay slot (beware)
reg		squash_s1e;
wire		Commit_s1e;		  // _s1e of the delay slot (beware)
wire		Squash_s1e;
reg		predictTaken_b_s1e;
reg		predictTaken_b_s2e;

//
// Delayed signals
//
reg		AAddOp_s2e;
reg		ASubOp_s2e;
reg		ASltOp_s2e;
reg		ASltUOp_s2e;
reg		AAndOp_s2e;
reg		AOrOp_s2e;
reg		AXorOp_s2e;
reg		ANorOp_s2e;
reg		MultOp_s2e;
reg		DivOp_s2e;
reg		SignedMDOp_s2e;
reg		LoadHiLo_s2e;
reg		StoreHiLo_s2e;
reg		HiLo_s2e;
reg		AALUDrv_s2e;
reg		AAUopSigned_s2e;
reg		ShiftLeft_s2e;
reg		ShiftArithmetic_s2e;
reg		ShifterDrv_s2e;

//
// MEM Stage
//
reg		RetFromExcept_s1m;

//
// Local Wires
//
wire		AUseT_s1e;
//wire	[3:0]	COC_s1e;		  // Coproc condition codes, not used
reg		branch_s1e;
reg		branch_s2e;
wire		killBrOrJ_s1e;		 // Dont execute branch or jump

//
// Decoded branch signals before they are qualified with Akill and Squash
//
reg		instrIsBEQ_s1e;
reg		instrIsBNE_s1e;
reg		instrIsBLEZ_s1e;
reg		instrIsBGTZ_s1e;
reg		instrIsBLTZ_s1e;
reg		instrIsBGEZ_s1e;

initial begin
    ADestIsRT_s1e = 0;
    ADestIsRD_s1e = 0;
    ADestIs31_s1e = 0;
    AAddOp_s1e = 0;
    ASubOp_s1e = 0;
    ASltOp_s1e = 0;
    ASltUOp_s1e = 0;
    AAndOp_s1e = 0;
    AOrOp_s1e = 0;
    AXorOp_s1e = 0;
    ANorOp_s1e = 0;
    MultOp_s1e = 0;
    DivOp_s1e = 0;
    SignedMDOp_s1e = 0;
    AUseImm_s1e = 0;
    LoadHiLo_s1e = 0;
    StoreHiLo_s1e = 0;
    HiLo_s1e = 0;
    AALUDrv_s1e = 0;
    AAUopSigned_s1e = 0;
    ShiftLeft_s1e = 0;
    ShiftArithmetic_s1e = 0;
    ShifterDrv_s1e = 0;
    PCDrvResult_s1e = 0;
    PCDrvResult_s2e = 0;
    immPC_s1e = 0;
    regPC_s1e = 0;
    AIsBoosted_s1e = 0;
    AIsBoosted_s2e = 0;
    AWrong_s1e = 0;
    commit_s1e = 0;
    squash_s1e = 0;
    predictTaken_b_s1e = 0;
    predictTaken_b_s2e = 0;
    AAddOp_s2e = 0;
    ASubOp_s2e = 0;
    ASltOp_s2e = 0;
    ASltUOp_s2e = 0;
    AAndOp_s2e = 0;
    AOrOp_s2e = 0;
    AXorOp_s2e = 0;
    ANorOp_s2e = 0;
    MultOp_s2e = 0;
    DivOp_s2e = 0;
    SignedMDOp_s2e = 0;
    LoadHiLo_s2e = 0;
    StoreHiLo_s2e = 0;
    HiLo_s2e = 0;
    AALUDrv_s2e = 0;
    AAUopSigned_s2e = 0;
    ShiftLeft_s2e = 0;
    ShiftArithmetic_s2e = 0;
    ShifterDrv_s2e = 0;
    RetFromExcept_s1m = 0;
    branch_s1e = 0;
    branch_s2e = 0;
    instrIsBEQ_s1e = 0;
    instrIsBNE_s1e = 0;
    instrIsBLEZ_s1e = 0;
    instrIsBGTZ_s1e = 0;
    instrIsBLTZ_s1e = 0;
    instrIsBGEZ_s1e = 0;
end

assign Phi2 = ~Phi1;

//
// Generate immediate select signals
//
assign AImm26Bit_s2r = (AInstr_s2r[31:27]==5'd1) || (AInstr_s2r[31:28]==4'd4);
						     // J, JAL or COPz
assign AImmSigned_s2r = ~(AInstr_s2r[31:28]==4'd3);  // not ANDI ORI XORI LUI
assign AImmShift16_s2r = (AInstr_s2r[`OPCODE]==6'd15); // LUI


//
// Derive some control signals from decoded signals
//
assign AUseT_s1e = AAddOp_s1e | AAndOp_s1e | AOrOp_s1e | AXorOp_s1e |
		    ANorOp_s1e;

//
// PC control signal which can be computed before S==T or S<0 signals 
// arrive (unconditinal jumps)
// 
assign killBrOrJ_s1e = AKill_s1e | (AIsBoosted_s1e & Squash_s1e) | Except_s1w;
assign RegPC_s1e = regPC_s1e & ~killBrOrJ_s1e;
assign ImmPC_s1e = immPC_s1e & ~killBrOrJ_s1e;

//
// Branch control signals. whether the branch is taken or not is determined
// in the instruction fetch unit.
//
assign BEQnext_s1e = instrIsBEQ_s1e & ~killBrOrJ_s1e;
assign BNEnext_s1e = instrIsBNE_s1e & ~killBrOrJ_s1e;
assign BLEZnext_s1e = instrIsBLEZ_s1e & ~killBrOrJ_s1e;
assign BGTZnext_s1e = instrIsBGTZ_s1e & ~killBrOrJ_s1e;
assign BLTZnext_s1e = instrIsBLTZ_s1e & ~killBrOrJ_s1e;
assign BGEZnext_s1e = instrIsBGEZ_s1e & ~killBrOrJ_s1e;

//
// Commit and Squahs signals are computed with information from instruction
// fetch unit on whether the branch was taken or not.
// WARNING: The signals are labeled _s1e because they should be s1e of
// the delay slot
//
// NOTE: There is corner case dealing with exceptions that has to be
// handled here. If an exception happens in the delay slot of a wrongly
// predicted branch then on the MEM stage of the RFE instruction need to
// re-assert the Squash_s1e signal to kill any boosted instructions that
// will be re-executed. There is a bit-stack in the PSW (or Status reg)
// that indicates whether this condition happened--called SquashBit_s1
always @(Phi2 or TakenBranch_s2e or branch_s2e or
	 predictTaken_b_s2e) begin
    if (Phi2) begin
	commit_s1e = (TakenBranch_s2e & ~predictTaken_b_s2e) |
			(branch_s2e & ~TakenBranch_s2e & predictTaken_b_s2e);

	squash_s1e = (TakenBranch_s2e & predictTaken_b_s2e) |
			(branch_s2e & ~TakenBranch_s2e & ~predictTaken_b_s2e);
    end
end
assign Commit_s1e = commit_s1e;
assign Squash_s1e = (squash_s1e) |
			(RetFromExcept_s1m & SquashBit_s1);

//
// This section computes the s1e signals.
//
always @(Phi2 or AInstr_s2r) begin
    if (Phi2) begin
    // Check if B side instruction - used for AIgnore signal
	AWrong_s1e = (AInstr_s2r[31]==1'd1  |		// Ld, Store
		      AInstr_s2r[31:28]==4'd4 | 	// coprocessor
		      (AInstr_s2r[`OPCODE]==`SPECIAL & AInstr_s2r[5:2]==4'd3));
							// Syscall & break

    // Determine which of the register specifier
    // fields if any specifies a destination to
    // be written during WB. Zero means no dest.

    // Three operand instructions
	if (// All SPECIAL ops
	    AInstr_s2r[`OPCODE]==`SPECIAL &
	    // Exclude JR, SYSCALL, BREAK
	    ~((AInstr_s2r[5:3]==3'd1 & AInstr_s2r[2:0]!=3'd1) |
	    // Exclude MULT(U)/DIV(U)
	    AInstr_s2r[5:3]==3'd3 |
	    // Exclude MTHI, MTLO
	    (AInstr_s2r[5:3]==3'd2 & AInstr_s2r[0]==1'd1))) begin
		ADestIsRT_s1e = `FALSE;
		ADestIsRD_s1e = `TRUE;
		ADestIs31_s1e = `FALSE;
	end
	else if ((AInstr_s2r[31:29]==3'd1)) begin
	    // All immediate ALU ops
		ADestIsRT_s1e = `TRUE;
		ADestIsRD_s1e = `FALSE;
		ADestIs31_s1e = `FALSE;
	end
	else if (// JAL, BLTZAL, BGEZAL
	    (AInstr_s2r[`OPCODE]==6'd3)||
	    ((AInstr_s2r[`OPCODE]==6'd1)&&(AInstr_s2r[20:19]==2'd2))) begin
		ADestIsRT_s1e = `FALSE;
		ADestIsRD_s1e = `FALSE;
		ADestIs31_s1e = `TRUE;
	end
	else begin
	    // No destination instructions
		ADestIsRT_s1e = `FALSE;
		ADestIsRD_s1e = `FALSE;
		ADestIs31_s1e = `FALSE;
	end


	// Boosted instruction
	AIsBoosted_s1e = AInstr_s2r[37] | AInstr_s2r[36] |
			AInstr_s2r[35] | AInstr_s2r[34] |
			AInstr_s2r[33] | AInstr_s2r[32];


	// Signed ADD/SUB i.e. ADDI, ADD, SUB
	AAUopSigned_s1e = AInstr_s2r[`OPCODE]==6'b001000 |  // ADDI
			   (AInstr_s2r[`OPCODE]==`SPECIAL & // SPECIAL
			    AInstr_s2r[5:3]==3'd4 &   
			    (AInstr_s2r[2:0]==3'd0 |	    // ADD
			     AInstr_s2r[2:0]==3'd2));	    // SUB

	// ADD, ADDU, ADDI, ADDIU, Loads and Stores
	AAddOp_s1e = (AInstr_s2r[`OPCODE]==`SPECIAL & AInstr_s2r[5:1]==5'd16) |
			AInstr_s2r[31:27]==5'd4 | AInstr_s2r[31]==1'd1;

	// SUB, SUBU
	// SPECIAL instr & SUB
	ASubOp_s1e = AInstr_s2r[`OPCODE]==`SPECIAL & AInstr_s2r[5:1]==5'd17;

	// SLT, SLTI
	ASltOp_s1e = (AInstr_s2r[`OPCODE]==`SPECIAL & AInstr_s2r[5:0]==6'd42) |
			AInstr_s2r[`OPCODE]==6'd10;

	// SLTU, SLTIU
	ASltUOp_s1e = (AInstr_s2r[`OPCODE]==`SPECIAL &
			AInstr_s2r[5:0]==6'd43) | (AInstr_s2r[`OPCODE]==6'd11);

	// AND, ANDI
	AAndOp_s1e = (AInstr_s2r[`OPCODE]==`SPECIAL & AInstr_s2r[5:0]==6'd36) |
			AInstr_s2r[`OPCODE]==6'd12;

	// OR, ORI, LUI
	AOrOp_s1e = (AInstr_s2r[`OPCODE]==`SPECIAL & AInstr_s2r[5:0]==6'd37) |
			AInstr_s2r[`OPCODE]==6'd13 |
			AInstr_s2r[`OPCODE]==6'd15;

	// XOR, XORI
	AXorOp_s1e = (AInstr_s2r[`OPCODE]==`SPECIAL & AInstr_s2r[5:0]==6'd38) |
			AInstr_s2r[`OPCODE]==6'd14;

	// NOR
	ANorOp_s1e = AInstr_s2r[`OPCODE]==`SPECIAL & AInstr_s2r[5:0]==6'd39;

	// MULT, MULTU
	MultOp_s1e = AInstr_s2r[`OPCODE]==`SPECIAL & AInstr_s2r[5:1]==5'd12;

	// DIV, DIVU
	DivOp_s1e = AInstr_s2r[`OPCODE]==`SPECIAL & AInstr_s2r[5:1]==5'd13;

	// MULT, DIV
	SignedMDOp_s1e = AInstr_s2r[`OPCODE]==`SPECIAL & AInstr_s2r[5:2]==4'd6 &
			AInstr_s2r[0]==1'd0;

	// MFHI, MFLO
	LoadHiLo_s1e = AInstr_s2r[`OPCODE]==`SPECIAL & AInstr_s2r[5:2]==4'd4 &
			AInstr_s2r[0]==1'd0;

	// MTHI, MTLO
	StoreHiLo_s1e = AInstr_s2r[`OPCODE]==`SPECIAL & AInstr_s2r[5:2]==4'd4 &
			AInstr_s2r[0]==1'd1;

	// MFHI, MFLO, MTHI, MTLO
	HiLo_s1e = AInstr_s2r[`OPCODE]==`SPECIAL & AInstr_s2r[5:2]==4'd4 &
			~AInstr_s2r[1];

	// ALU Immediates, Branches
	AUseImm_s1e = AInstr_s2r[31:29]==3'd1 |
		(AInstr_s2r[31:29]==3'd0 & AInstr_s2r[28:26]!=3'd0) |
		AInstr_s2r[`OPCODE]==6'd15;

	// ALU Immediates, SPECIALs excluding shifts,
	// Loads and Stores
	AALUDrv_s1e =   AInstr_s2r[31:29] == 3'd1 |
			(AInstr_s2r[`OPCODE] == `SPECIAL &
			    AInstr_s2r[5:3] != 3'd0) |
			AInstr_s2r[31:30] == 2'd2;

	// SLL, SLLV
	ShiftLeft_s1e = AInstr_s2r[`OPCODE] == `SPECIAL &
			AInstr_s2r[5:3]==3'd0 & AInstr_s2r[1:0]==2'd0;

	// SRA, SRAV
	ShiftArithmetic_s1e = AInstr_s2r[1:0]==2'd3;

	// SLL, SRL, SRA, SLLV, SRLV, SRAV
	ShifterDrv_s1e = AInstr_s2r[`OPCODE]==`SPECIAL &
			    AInstr_s2r[5:3]==3'd0;

	// BEQ
	instrIsBEQ_s1e = ~AInstr_s2r[31] & ~AInstr_s2r[29] &
			    (AInstr_s2r[28:26]==3'd4);

	// BNE
	instrIsBNE_s1e = ~AInstr_s2r[31] & ~AInstr_s2r[29] &
			    (AInstr_s2r[28:26]==3'd5);

	// BLEZ
	instrIsBLEZ_s1e = ~AInstr_s2r[31] & ~AInstr_s2r[29] &
			    (AInstr_s2r[28:26]==3'd6);

	// BGTZ
	instrIsBGTZ_s1e = ~AInstr_s2r[31] & ~AInstr_s2r[29] &
			    (AInstr_s2r[28:26]==3'd7);

	// BLTZ
	instrIsBLTZ_s1e = (AInstr_s2r[`OPCODE] == `BCOND) &
			    (AInstr_s2r[16] == 1'd0);

	// BGEZ
	instrIsBGEZ_s1e = (AInstr_s2r[`OPCODE] == `BCOND) &
			    (AInstr_s2r[16] == 1'd1);

	// BEQ, BNE, BLEZ, BGTZ, BLTZ, BGEZ
	branch_s1e = (~AInstr_s2r[31] & ~AInstr_s2r[29] & AInstr_s2r[28]) |
			AInstr_s2r[`OPCODE] == `BCOND;

	// Keep the prediction information
	predictTaken_b_s1e = AInstr_s2r[30] | 
		((AInstr_s2r[`OPCODE] == `BCOND) & AInstr_s2r[19]);

	// JAL, BLTZAL, BGEZAL, JALR
	PCDrvResult_s1e = AInstr_s2r[`OPCODE]==6'd3 |
		(AInstr_s2r[`OPCODE]==6'd1 & AInstr_s2r[20:19]==2'd2) |
		(AInstr_s2r[`OPCODE]==`SPECIAL & AInstr_s2r[5:0]==6'd9);

	// J, JAL
	immPC_s1e = AInstr_s2r[31:27]==5'd1;

	// JR, JALR
	regPC_s1e = AInstr_s2r[`OPCODE]==`SPECIAL & AInstr_s2r[5:1]==5'd4;

    end
end

always @(Phi1 or Stall_s1 or AAddOp_s1e or ASubOp_s1e or
	ASltOp_s1e or ASltUOp_s1e or AAndOp_s1e or AOrOp_s1e or
	AXorOp_s1e or ANorOp_s1e or MultOp_s1e or DivOp_s1e or
	SignedMDOp_s1e or LoadHiLo_s1e or StoreHiLo_s1e or 
	HiLo_s1e or AALUDrv_s1e or AAUopSigned_s1e or ShiftLeft_s1e or
	ShiftArithmetic_s1e or ShifterDrv_s1e or AKill_s1e or branch_s1e or
	predictTaken_b_s1e or PCDrvResult_s1e or AIsBoosted_s1e or
	Except_s1w or killBrOrJ_s1e) begin
    if (Phi1 & ~Stall_s1 & ~AKill_s1e) begin
	AAddOp_s2e = AAddOp_s1e;
	ASubOp_s2e = ASubOp_s1e;
	ASltOp_s2e = ASltOp_s1e;
	ASltUOp_s2e = ASltUOp_s1e;
	AAndOp_s2e = AAndOp_s1e;
	AOrOp_s2e = AOrOp_s1e;
	AXorOp_s2e = AXorOp_s1e;
	ANorOp_s2e = ANorOp_s1e;
	MultOp_s2e = MultOp_s1e;
	DivOp_s2e = DivOp_s1e;
	SignedMDOp_s2e = SignedMDOp_s1e;
	LoadHiLo_s2e = LoadHiLo_s1e;
	StoreHiLo_s2e = StoreHiLo_s1e;
	HiLo_s2e = HiLo_s1e;
	AAUopSigned_s2e = AAUopSigned_s1e;
	ShiftLeft_s2e = ShiftLeft_s1e;
	ShiftArithmetic_s2e = ShiftArithmetic_s1e;
    end

    if (Phi1 & ~Stall_s1) begin
	AALUDrv_s2e = AALUDrv_s1e & ~AKill_s1e;
	ShifterDrv_s2e = ShifterDrv_s1e & ~AKill_s1e;
	branch_s2e = branch_s1e & ~killBrOrJ_s1e;
	predictTaken_b_s2e = predictTaken_b_s1e;
	PCDrvResult_s2e = PCDrvResult_s1e & ~AKill_s1e;
	AIsBoosted_s2e = AIsBoosted_s1e;
    end
end

always @(Phi2 or RetFromExcept_s2e) begin
    if (Phi2) RetFromExcept_s1m = RetFromExcept_s2e;
end

endmodule				  // AdecSE1
