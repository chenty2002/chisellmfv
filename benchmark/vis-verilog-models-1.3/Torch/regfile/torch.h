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
//  Title: 	Torch Verilog Global Macros
//  Created:	Wed Apr 13 14:35:11 1994
//  Author: 	Ricardo E. Gonzalez
//		<ricardog@chroma>
//
//
//  torch.h,v 1.21 1995/06/09 05:50:39 ricardog Exp
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
//
`define	TORCH		1.21

`define	TICK		

`define	CYCLE_TIME	100
`define	PHASE_TIME	(`CYCLE_TIME/2)
`define	CYCLE		#`CYCLE_TIME
`define	PHASE		#`PHASE_TIME

`define TRUE		1'b1
`define FALSE		1'b0
`define VALID		1'b1
`define NOT_VALID	1'b0
`define ZERO		0

`define MSB		31

`define SYSTEM		system
`define L2C		`SYSTEM.L2Cache
`define GA		`SYSTEM.gateArray

`define	PROCESSOR	system.Torch

`define	EXT_INT		`PROCESSOR.externInt
`define EIC		`EXT_INT.extIntControl
`define EID		`EXT_INT.extIntDatapath

`define	LDSTO		`PROCESSOR.loadStore
`define LSU		`LDSTO.loadStoreUnit
`define SBUFF		`LSU.storeBuffer
`define TBUFF		`LDSTO.tagBuffer

`define	REG_FILE	`PROCESSOR.regFile
`define	A_BYPASS	`REG_FILE.ABypass
`define	B_BYPASS	`REG_FILE.BBypass
`define	REG		`REG_FILE.REGFILE
`define RFC		`REG_FILE.regControl
`define RSEQ		`REG_FILE.regSeqLoop
`define RBOOST		`REG_FILE.regBoostLoop
`define A_BPS		`A_BYPASS.ABypassSpec
`define B_BPS		`B_BYPASS.BBypassSpec
`define A_BPD		`A_BYPASS.ABypassData
`define B_BPD		`B_BYPASS.BBypassData

`define	IF		`PROCESSOR.instrFetch
`define IFI		`IF.ICACHE
`define	IFD		`IF.IFetchDatapath
`define	IFC		`IF.IFetchControl
`define	PCU		`IF.PCUnitDatapath

`define	COP0		`PROCESSOR.coproc0
`define	CP0_C		`COP0.cp0control
`define CP0_INTENC	`COP0.cp0IntEncoder
`define TLB		`COP0.tlb
`define TLBD		`TLB.tlbDatapath
`define TLBC		`TLB.tlbControl

`define	DEC_EXEC	`PROCESSOR.decodeExec
`define	AEXE		`DEC_EXEC.AExecuteUnit
`define	AALU		`AEXE.AALU
`define	BEXE		`DEC_EXEC.BExecuteUnit
`define	BALU		`BEXE.BALU

//
// The following are the groups for the waves display
//
`define EW_WAVES_GROUP		4
`define GA_WAVES_GROUP		5
`define EIC_WAVES_GROUP		6
`define EID_WAVES_GROUP		7
`define TLBE_WAVES_GROUP	8
`define EXCEPT_WAVES_GROUP	9
`define INT_WAVES_GROUP		10
`define TLB_WAVES_GROUP		11
`define LDSTO_WAVES_GROUP	12
`define REG_WAVES_GROUP		13
`define BYPASS_WAVES_GROUP	14
`define ALU_WAVES_GROUP		15
`define IMISS_WAVES_GROUP	16
`define IFETCH_WAVES_GROUP	17
`define PCUNIT_WAVES_GROUP	18
`define PCCHAIN_WAVES_GROUP	19
`define IFILL_WAVES_GROUP	20

//
// Instruction Decoding
//
`define OPCODE		31:26		 // Bit-filed in instruction
`define SPECIAL		6'd0		 // Value of opcode
`define BCOND		6'd1		 // Value of opcode
`define NOP		32'h00000020	 // NOP instruction

//
// Bit selects for the register specifiers (in the instruction)
//
`define MSB_RS          25
`define LSB_RS          21
`define RS_BOOST        36

`define MSB_RT          20
`define LSB_RT          16
`define RT_BOOST        34

`define MSB_RD          15
`define LSB_RD          11
`define RD_BOOST        32

//
// Register file definitions
//
`define BOOSTED     1'b1
`define NOT_BOOSTED 1'b0
`define DONT_CARE   1'b0

//
// Register File Specifier Bit Positions (i.e. what each bit means)
//
`define SPEC_BIT        4
`define BOOST_BIT       5
`define VALID_BIT       6
`define HARD_DEST_BIT   7
`define BOOST_VALID_BIT 8
`define LOAD_BIT        9

//
// Control selects for the bypass muxes. The stage refers to the time when
// the bypassing instruction is in the RF stage
//
`define BYPASS_AEX_BIT	4		  // bypass from A-side EX stage
`define BYPASS_BEX_BIT	3		  // bypass from B-side EX stage
`define BYPASS_AMEM_BIT	2		  // bypass from A-side MEM stage
`define BYPASS_BMEM_BIT	1		  // bypass from B-side MEM stage
`define NO_BYPASS_BIT	0		  // No bypass

//
// Load/Store Encoding
//
`define DOUBLE		2'b10
`define WORD		2'b11
`define HALF		2'b01
`define BYTE		2'b00

//
// Memory System Parameters
//
`define MEM_LATENCY	3
`define RATE		5'd3
`define LATENCY		5'd5
`define RATIO		4'b0010

//
// Communication Addresses (Magical I/O)
//
`define COM_ADDR	32'h00000010
`define IO_DWADDR 32'hA0000800
`define SC_DWADDR 32'hA0000900
`define IC_DWADDR 32'hA0000A00
//`define IO_DWADDR (32'hA0000800 >> 2'h3)
//`define SC_DWADDR (32'hA0000900 >> 2'h3)
//`define IC_DWADDR (32'hA0000A00 >> 2'h3)
// The word addresses turn out to be:
// IO_DWADDR	0x14000100
// SC_DWADDR	0x14000120
// IC_DWADDR	0x14000140

//
// Enable the multiplier by default. This may be a bad idea since I am
// bound to forget that it is here...
//
`define MULTIPLIER

//
// How many levels to dump by default
//
`define dumplevels	3

// Local Variables: ***
// mode:verilog ***
// End: ***
