//-----------------------------------------------------------------
//
// module IDU: Instruction Decode Unit for subset of Alpha ISA
//
// Determine the control signals necessary for executing an instruction.
//
// Inputs:
// Clk         global clock;
//             from: CHIP
// Work        1..valid operation here / 0..bubble here;
//             from: PCU
// Step        advance pipeline on next clk;
//             from: PCU
// Reset       processor reset;
//             from: CHIP
// Insn        insn bus;
//             insn to decode;
//             from: IFU
// PC          program counter (PC+4);
//             muxed onto Operand2 for PC relative computations;
//             from: IFU
// RegAValue   register Ra value;
//             from: FRU; asynchronous; operates in same cycle as IDU;
// RegBValue   register Rb value;
//             from: FRU; asynchronous; operates in same cycle as IDU;
//
// Outputs:
// Const       constant from immediate field;
//             only valid for insns which define an immediate operand
//             to: EXU; immediate field
// RegA        register number source operand Ra + valid bit;
//             extended with valid bit; msb = 0 / 1 ... invalid / valid
//             to: FRU; asynchronous; operates in same cycle as IDU
// RegB        register number source operand Rb + valid bit;
//             extended with valid bit; msb = 0 / 1 ... invalid / valid
//             to: FRU; asynchronous; operates in same cycle as IDU
// RegDest     register number destination operand + valid bit;
//             normally this is Rc; however, for LDx insns it is Ra;
//             to: EXU; needed for forwarding by FRU and WB stage
// Operand1    operand 1 value;
//             handed through from FRU;
//             to: EXU;
// Operand2    operand 2 value;
//             either Rb value from FRU or PC from IFU;
//             to: EXU;
// OpCode      opcode from instruction encoding;
//             to: EXU; insn opcode field
// FctCode     function code from instruction encoding;
//             to: EXU; operate insn function code field
// Decode      decoded instruction bits (see includes/decode.v)
//             to: ALU; pass to MAU
//                 PCU; pipeline advancing decisions
// Exception   exception request from IDU;
//             can signal EXC_PAL (palcode opcode),
//             EXC_RESV (reserved opcode),
//             EXC_FP (floating point opcode),
//             EXC_LDLSTC (LD_L/ST_C opcode);
//             normal condition signalled by EXC_NONE;
//             to: PCU; exception handling
//
// -------------------------------------------------------------

`include "../includes/const.v"
`include "../includes/opcode.v"
`include "../includes/decode.v"

`ifdef DEBUG_IDU
`else
`define DEBUG_IDU 0
`endif
//`define IDU_DEBUG if(`DEBUG_IDU) $display

module idu (Clk, iWork, iStep, iReset,iInsn,iPC,iRegAValue,iRegBValue,Const, 
	    Operand1, Operand2, RegDest, OpCode, FctCode, Decode, Exception, 
	    RegA, RegB);

  // Inputs
  input         Clk,            // global clock
                iWork,           // valid operation here / bubble here
               iStep,           // latch new input on clk
                iReset;          // processor reset
  input   `INSN iInsn;           // insn bus from Ifetch
  input  `PCADR iPC;             // program counter (PC+4)
  input  `DATAQ iRegAValue,      // register Ra value from FRU
                iRegBValue;      // register Rb value from FRU

  // Outputs
  output `CONST Const;          // immediate operand (sign extended)
  output   `REG RegA,           // register number source operand Ra
                RegB,           // register number source operand Rb
                RegDest;        // register number destination operand
  output `DATAQ Operand1,       // operand 1 value
                Operand2;       // operand 2 value
  output   `OPC OpCode;         // opcode
  output   `FCT FctCode;        // function code (subcode of opcode)
  output   `DEC Decode;         // decoded insn bits
  output   `EXC Exception;      // exception request from IDU

  //
  // delayed internal wires
  //
  // Inputs
  wire          I_Work,
                I_Step,
                I_Reset;
  wire    `INSN I_Insn;
  wire    `PCADR I_PC;
  wire    `DATAQ I_RegAValue,
                I_RegBValue;

  // Outputs
  wire   `CONST I_Const;
  wire     `REG I_RegA,
                I_RegB,
                I_RegDest;
 wire  `DATAQ 	I_Operand1,
                I_Operand2;
 wire      `OPC I_OpCode;
 wire      `FCT I_FctCode;
 wire      `DEC I_Decode;
 wire      `EXC I_Exception;

//
  // registered internal wires
  //
  wire           R_Work;         // latched work signal
  wire    `INSN R_Insn;         // latched instruction
  wire    `PCADR R_PC;           // latched PC


   reg 		 Work,
                Step,
                Reset;
   reg `INSN 	 Insn;
   reg `PCADR 	 PC;
   reg `DATAQ 	 RegAValue,
                RegBValue;
  
   initial begin
      Work = 0;
      Step = 0;     
      Reset= 1;
      Insn = 0;
      PC = 0;
      RegAValue = 0;
      RegBValue = 0;
   end // initial begin
   
always @(posedge Clk) begin
   Work = iWork;
   Step = iStep;
   Reset = iReset;
   Insn = iInsn;
   PC = iPC;
   RegAValue = iRegAValue;
   RegBValue = iRegBValue;
end // always @ (posedge Clk)
   
   //
  // assigning delayed wires
  //
  // Inputs
   assign 	 I_Work      = Work;
  assign I_Step      = Step;
  assign I_Reset     = Reset;
  assign I_Insn      = Insn;
  assign I_PC        = PC;
  assign I_RegAValue = RegAValue;
  assign I_RegBValue = RegBValue;



  // Outputs
  assign Const       = I_Const;
  assign RegA        = I_RegA;
  assign RegB        = I_RegB;
  assign RegDest     = I_RegDest;
  assign Operand1    = I_Operand1;
  assign Operand2    = I_Operand2;
  assign OpCode      = I_OpCode;
  assign FctCode     = I_FctCode;
  assign Decode      = I_Decode;
  assign Exception   = I_Exception;

     
  //
  // instantiations of sequential and combinational subblocks
  //
   IDU_Pipeline Pipeline( Clk, I_Step, I_Reset,I_Work, I_Insn, I_PC,
			  R_Work, R_Insn, R_PC);
  IDU_Logic Logic(R_Work, R_Insn, R_PC,I_RegAValue, I_RegBValue, 
		   I_Const, I_Operand1, I_Operand2, I_RegDest, I_OpCode, 
		   I_FctCode,I_Decode, I_Exception,I_RegA, I_RegB  
		   /* asynchronous to FRU*/ );
  //------------------------------------------------------------------------
  // debug decoder - display disassembled insn
  //------------------------------------------------------------------------
/*  `include "disassemble.v"
 always @(posedge Clk) begin// 
 if (`DEBUG_IDU) begin
 if (R_Work & test.speed_racer.PCU_Step_EXU) begin
 $write(  "IDU: %h %h   %h/%h ", R_PC-4, R_Insn, OpCode, FctCode);
 Disassemble(OpCode, FctCode, Decode, Const,// 
 RegA, RegB, RegDest);// 
 $write(  "   (%0s)", DecodeStr(Decode));
 $display;
      end
    end
  end
 */
endmodule			// idu


//----------------------------------------------------------------------------
// synchronous interface stage of IDU
// latch incoming signals on rising edge of clock if pipeline stage is enabled
//----------------------------------------------------------------------------
 module IDU_Pipeline(Clk, iStep, iReset,iWork,iInsn,iPC,R_Work,R_Insn, R_PC );

  // Inputs
  input         Clk,            // system clock
                iStep,           // latch new input on clk
                iReset;          // reset
  input         iWork;           // valid operation here / bubble here
  input   `INSN iInsn;           // instruction bus
  input  `PCADR iPC;             // program counter (PC+4)

  // Outputs
  output        R_Work;         // latched work
  output  `INSN R_Insn;         // latched instruction
  output `PCADR R_PC;           // latched PC

  //
  // internal registers
  //
  reg           R_Work;
  reg     `INSN R_Insn;
  reg    `PCADR R_PC;

   reg          Step,           // latch new input on clk
                Reset;          // reset
   reg 		Work;           // valid operation here / bubble here
   reg `INSN 	Insn;           // instruction bus
   reg `PCADR 	PC;             // program counter (PC+4)

   initial begin
      R_Insn = 32'h0000_0000;
      R_PC =  32'h0000_0000;
      R_Work = 1'b0;
      Step = 0;
      Reset = 1;
      Work = 0;
      Insn = 0;
      PC = 0;
   end // initial begin
   
  //
  // When reset, purge pipeline stage
  //

   always @(posedge Clk) begin
      Step = iStep;
      Reset = iReset;
      Work = iWork;
      Insn = iInsn;
      PC = iPC;
   end // always @ (posedge Clk)
   
  always @(Reset) begin
    if (Reset) begin
      assign R_Work = `FALSE;
    end 
  end // always @ (Reset)
   
  //
  // latch signals
  //
  always @(posedge Clk) begin
    R_Work = Work;
    if (Step) begin
      R_Insn = Insn;
      R_PC   = PC;
    end
  end

endmodule // IDU_Pipeline


//----------------------------------------------------------------------------
//
// This module determines the control signals necessary
// for execution of the present instruction.
//
//----------------------------------------------------------------------------

 module IDU_Logic(iWork, iInsn, iPC,iRegAValue, iRegBValue,Const, Operand1, 
		  Operand2, RegDest, OpCode, FctCode, Decode, Exception,
		  RegA, RegB);

  // Inputs
  input         iWork;           // valid insn in pipeline
  input   `INSN iInsn;           // instruction bus
  input  `PCADR iPC;             // program counter (PC+4)
  input  `DATAQ iRegAValue,      // register Ra value from FRU
                iRegBValue;      // register Rb value from FRU

  // Outputs
  output `CONST Const;          // immediate operand
  output   `REG RegA,           // register number operand A
                RegB,           // register number operand B
                RegDest;        // register number destination operand
  output `DATAQ Operand1,       // operand 1 value
                Operand2;       // operand 2 value
  output   `OPC OpCode;         // opcode
  output   `FCT FctCode;        // opcode sub-function
  output   `DEC Decode;         // decoded insn
  output   `EXC Exception;      // exception request from IDU

  reg    `CONST Const;
  reg      `REG RegA_int,
                RegB_int,
                RegDest_int;
  reg    `DATAQ Operand1,
                Operand2;
  reg      `OPC OpCode;
  reg      `FCT FctCode;
  reg      `DEC Decode;
  reg      `EXC Exception;

   //inputs
  reg         Work;           // valid insn in pipeline
  reg   `INSN Insn;           // instruction bus
  reg  `PCADR PC;             // program counter (PC+4)
  reg  `DATAQ RegAValue,      // register Ra value from FRU
                RegBValue; 

  // internal signals
  wire     `REG RegA,
                RegB,
                RegDest;


   initial begin
      Const = 22'b0000_0000_0000_0000_0000_00;
      RegA_int = 6'b0000_00;      
      RegB_int = 6'b0000_00;
      RegDest_int = 6'b0000_00;
      Operand1 = 32'h0000_0000;
      Operand2= 32'h0000_0000;
      OpCode = 6'b0000_00;
      FctCode = 7'b0000_000;
      Decode = 6'b0000_00;
      Exception = 4'b0000;
      Work= 0;           // valid insn in pipeline
      Insn = 0;
           // instruction bus
      PC= 0;             // program counter (PC+4)
      RegAValue = 0;   // register Ra value from FRU
      RegBValue = 0; 
   end // initial begin

   always @(posedge Clk) begin
      Work = iWork;
      Insn = iInsn;
      PC = iPC;
      RegAValue = iRegAValue;
      RegBValue = iRegBValue;
   end // always @ (posedge Clk)
   
   
  //--------------------------------------------------------------------------
  // qualify register valid signals
  //--------------------------------------------------------------------------

  // RegA
  assign RegA`REGV = RegA_int`REGV & Work;
  assign RegA`REGN = RegA_int`REGN;
  // RegB
  assign RegB`REGV = RegB_int`REGV & Work;
  assign RegB`REGN = RegB_int`REGN;
  // RegDest
  assign RegDest`REGV = RegDest_int`REGV & Work;
  assign RegDest`REGN = RegDest_int`REGN;

  //--------------------------------------------------------------------------
  //
  // Update all outputs depending on instruction register
  //
  //--------------------------------------------------------------------------

  always @(Insn) begin
    //----------------------------------------------------------------------
    // opcode is always at same position (no mux needed)
    //----------------------------------------------------------------------
    OpCode = Insn[`POS_OPCODE];
  end

  always @(OpCode or Work) begin
    //----------------------------------------------------------------------
    // exceptions
    // figure out if we can deal with this insn
    //----------------------------------------------------------------------
    casez({Work, OpCode})
      // PAL codes
      7'b1_00_0000: Exception = `EXC_PAL; // PAL00
      7'b1_01_1??1: Exception = `EXC_PAL; // reserved PAL codes
      7'b1_01_1110: Exception = `EXC_PAL; // reserved PAL codes

      // reserved opcodes
      7'b1_00_0??1: Exception = `EXC_RESV;
      7'b1_0?_?100: Exception = `EXC_RESV;
      7'b1_00_??10: Exception = `EXC_RESV;
      7'b1_00_1101: Exception = `EXC_RESV;
      
      // floating point opcodes
      7'b1_?1_011?: Exception = `EXC_FP; 
      7'b1_?1_0101: Exception = `EXC_FP;
      7'b1_11_001?: Exception = `EXC_FP;
      7'b1_11_0001: Exception = `EXC_FP;
      7'b1_10_0???: Exception = `EXC_FP; // fp load/store

      // load locked / store conditional
      7'b1_10_1?1?: Exception = `EXC_LDLSTC; 

      default:      Exception = `EXC_NONE;
    endcase
  end

  //----------------------------------------------------------------------
  // generate the decode bits (internal processor encoding)
  // (see decode.v for definitions)
  //----------------------------------------------------------------------
`ifdef SILVER
  //
  // horizontal encoding (1 bit per insn class)
  //
  always @(OpCode or Work) begin
    if (Work) begin
      Decode[`DEC_ALU] = (OpCode[5:3] == 3'b010);  // ALU operation
      Decode[`DEC_MEM] = (OpCode[4:3] == 2'b01);   // memory operation
      Decode[`DEC_CTR] = (OpCode[5:4] == 2'b11) |
                          (OpCode[5:0] == 01_1010); // control transfer operation
    end else begin
      Decode[`DEC_HOR] = 'b0;
    end
  end
  //
  // vertical encoding (shared bits)
  //
  always @(OpCode or Decode[`DEC_HOR]) begin
    if (Decode[`DEC_ALU]) begin
      Decode[`DEC_ALU_MULT] = (OpCode[1:0] == 2'b11);
    end 
    else if (Decode[`DEC_MEM]) begin
      Decode[`DEC_MEM_ST]   = OpCode[2];
      Decode[`DEC_MEM_QW]   = OpCode[0];
      Decode[`DEC_MEM_ACC]  = ~({OpCode[5],OpCode[1]} == 2'b00);
    end 
    else if (Decode[`DEC_CTR]) begin
      Decode[`DEC_CTR_COND] = (OpCode[5:3] == 3'b11_1);
      Decode[`DEC_CTR_PC]   = (OpCode[5:4] == 2'b11);
    end
  end
`else
  always @(OpCode or Work) begin
    Decode = `FALSE;
    //
    // horizontal encoding (1 bit per insn class)
    case(OpCode)
      `OP_INTA,
      `OP_INTL,
      `OP_INTS,
      `OP_INTM:  Decode[`DEC_ALU] = `TRUE;
      `OP_LDA,
      `OP_LDAH,
      `OP_LDL,
      `OP_LDQ,
      `OP_LDL_L,
      `OP_LDQ_L,
      `OP_LDQ_U,
      `OP_STL,
      `OP_STQ,
      `OP_STL_C,
      `OP_STQ_C,
      `OP_STQ_U: Decode[`DEC_MEM] = `TRUE;
      `OP_BLBC,
      `OP_BEQ,
      `OP_BLT,
      `OP_BLE,
      `OP_BLBS,
      `OP_BNE,
      `OP_BGE,
      `OP_BGT,
      `OP_BR,
      `OP_BSR,
      `OP_JMP:   Decode[`DEC_CTR] = `TRUE;
    endcase
    //
    // vertical encoding (shared bits)
    case(OpCode)
      `OP_INTM:  Decode[`DEC_ALU_MULT] = `TRUE;
      `OP_STL,
      `OP_STQ,
      `OP_STL_C,
      `OP_STQ_C,
      `OP_STQ_U: Decode[`DEC_MEM_ST] = `TRUE;
      `OP_BLBC,
      `OP_BEQ,
      `OP_BLT,
      `OP_BLE,
      `OP_BLBS,
      `OP_BNE,
      `OP_BGE,
      `OP_BGT:   Decode[`DEC_CTR_COND] = `TRUE;
    endcase
    case(OpCode)
      `OP_LDA,
      `OP_LDAH,
      `OP_LDQ,
      `OP_LDQ_L,
      `OP_LDQ_U,
      `OP_STQ,
      `OP_STQ_C,
      `OP_STQ_U: Decode[`DEC_MEM_QW] = `TRUE;
      `OP_BLBC,
      `OP_BEQ,
      `OP_BLT,
      `OP_BLE,
      `OP_BLBS,
      `OP_BNE,
      `OP_BGE,
      `OP_BGT,
      `OP_BR,
      `OP_BSR:   Decode[`DEC_CTR_PC] = `TRUE;
    endcase
    case(OpCode)
      `OP_LDL,
      `OP_LDQ,
      `OP_LDL_L,
      `OP_LDQ_L,
      `OP_LDQ_U,
      `OP_STL,
      `OP_STQ,
      `OP_STL_C,
      `OP_STQ_C,
      `OP_STQ_U: Decode[`DEC_MEM_ACC] = `TRUE;
    endcase
    if (~Work) begin
      Decode[`DEC_HOR] = 'b0;
    end
  end
`endif

  always @(Insn or Decode) begin
    //----------------------------------------------------------------------
    // mux register numbers to IFU
    //----------------------------------------------------------------------
    //
    // Ra
    // always present in encoding (but might be dest, not source)
    if ((Decode[`DEC_CTR] & ~Decode[`DEC_CTR_COND]) | // uncond ctr
        (Decode[`DEC_MEM] & ~Decode[`DEC_MEM_ST]))    // ld
    begin
      RegA_int = {`FALSE, Insn[`POS_REGA]};
    end else begin
      RegA_int = {`TRUE, Insn[`POS_REGA]};
    end
    // 
    // Rb
    // can be missing
    if ((Decode[`DEC_CTR] & Decode[`DEC_CTR_PC]) | // bra encoding
        (Decode[`DEC_ALU] & Insn[12]))               // op immediate encoding was [12]
    begin
      // does not have Rb
      RegB_int = {`FALSE, Insn[`POS_REGB]};
    end else begin
      // all others have Rb
      RegB_int = {`TRUE, Insn[`POS_REGB]};
    end
    // 
    // RDest
    // can be Rc or Ra or missing
    if (Decode[`DEC_ALU]) begin
      // Rc for operate encoding
      RegDest_int [4:0] = Insn[`POS_REGC]; // [4:0] =`REGN
      if (Insn[`POS_REGC] == 31) begin  // used to be 31 instead of 31
        // r31 dest treated like no dest
        RegDest_int[5] = `FALSE;  // [5] = `REGV
      end else begin
        RegDest_int[5] = `TRUE;
      end
    end else if ((Decode[`DEC_MEM] & ~Decode[`DEC_MEM_ST]) |
                 (Decode[`DEC_CTR] & ~Decode[`DEC_CTR_COND])) begin
      RegDest_int[4:0] = Insn[`POS_REGA];  // RegDest_int`REGN did not work
      if (Insn[`POS_REGA] == 31) begin
        // r31 dest treated like no dest
        RegDest_int[5] = `FALSE; //`REGB = [5]
      end else begin
        RegDest_int[5] = `TRUE;
      end
    end else begin
      RegDest_int[5] = `FALSE;
    end

    //----------------------------------------------------------------------
    // mux function code bus to EXU
    //----------------------------------------------------------------------
    if (Decode[`DEC_CTR] & ~Decode[`DEC_CTR_PC]) begin
      FctCode = Insn[`POS_HINT];
    end 
    else begin
      FctCode = Insn[`POS_FUNCTION];
    end

    //----------------------------------------------------------------------
    // mux constant bus to EXU
    //----------------------------------------------------------------------
    //Const[21:0]= 'bx; // behavioral sanitizer  // [4:0] = `CONST
     if (Decode[`DEC_ALU]) begin
      if (Insn[12]) begin  // used to be 12
         // operate insn with immediate field
        Const[21] = `TRUE;   // [4] = `CONSTV
	 // POS_LITERAL was in place of POS_IMMEDIATE
        Const[20:0]/*`CONSTN*/ = Insn[`POS_IMMEDIATE]; // zero extended 
      end else begin
        // operate insn without immediate field
        Const[21]/*`CONSTV*/ = `FALSE;
      end
    end else if (Decode[`DEC_MEM]) begin
      // short displacement field
      Const[20:0]/*`CONSTN*/ = {{5{Insn[`POS_DISPHI]}},
                       Insn[`POS_DISP]}; // sign extended
    end else if (Decode[`DEC_CTR] & Decode[`DEC_CTR_PC]) begin
      // full displacement field
      Const[20:0]/*`CONSTN*/ = Insn[`POS_IMMEDIATE];
    end
  end

  //------------------------------------------------------------------------
  // mux operand busses to EXU
  //------------------------------------------------------------------------
   always @(Decode or RegAValue or RegBValue or PC) begin
    if (Decode[`DEC_CTR]) begin
      if (Decode[`DEC_CTR_COND]) begin
        Operand1 = RegAValue;
        Operand2 = PC;
      end else begin
        Operand1 = PC;
        Operand2 = RegBValue;
      end
    end else begin
      Operand1 = RegAValue;
      Operand2 = RegBValue;
    end
  end

endmodule // IDU_Logic





