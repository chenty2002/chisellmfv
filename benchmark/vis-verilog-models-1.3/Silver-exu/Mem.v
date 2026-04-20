 `include "../includes/const.v"
`include "../includes/opcode.v"
`include "../includes/decode.v"

module Mem(clk,iOpCode, iOp1, iOp2, iConst,ResData, ResAdr); 

   input `OPC  iOpCode;
   input clk;
   input `DATAQ iOp1, iOp2;
   input `CONST iConst;
   
   output `DATAQ ResData, ResAdr;
   //output `EXC 	 Exception;	// exception request from ALU
   
   reg `OPC 	 OpCode;
   reg `DATAQ 	 Op1, Op2;
   reg `DATAQ 	 AluIn1, AluIn2;
   reg `DATAX 	 CondIn;    // condition evaluator input
   reg `DATAQ 	 AluOut;    // ALU output
   reg 		 C;/*carry into MSB */ 
   reg 		 Msb1;// MSB from input 1
   reg 		 Msb2;  // MSB from input 2
   reg `CONST Const;
  // reg `EXC   Exception;
   reg `DATAQ ResData, ResAdr;
   initial 
     begin 
	OpCode = 0;
	Const = 0;
	AluIn1 = 0;
	AluIn2 = 0;
	Op1 = 0;
	Op2 = 0;
	CondIn=0;    // condition evaluator input
	AluOut=0;    // ALU output
	C=0;/*carry into MSB */ 
	Msb1=0;// MSB from input 1
	Msb2=0;  // MSB from input 2
	//Exception = 0;
	ResData=0;
	ResAdr=0;	// 
     end

   always@(posedge clk)
     begin
	OpCode = iOpCode;
	Op1 = iOp1;
	Op2 = iOp2;
	Const = iConst;
     end // always@ (posedge clk)
   
   always @(OpCode) begin
      

      AluIn1 = {{`Q-`CM+1{Const`CONSTM}}, Const`CONSTN};
      AluIn2 = Op2;
      case(OpCode)
	//	   `OP_LDAH:     AluOut[31:0] = (AluIn1[`QM-16:0] << 16) + AluIn2`DATAQ;
	`OP_LDA,
	`OP_LDL,
	`OP_LDQ,
	//      `OP_LDL_L:  not implemented
	//      `OP_LDQ_L:  not implemented
	`OP_LDQ_U,
	`OP_STL,
	`OP_STQ,
	//      `OP_STL_C:  not implemented
	//      `OP_STQ_C:  not implemented
	`OP_STQ_U:    AluOut[31:0] = AluIn1`DATAQ + AluIn2`DATAQ;
	default:      AluOut = 0;
      endcase
				// 
      // force alignment for "unaligned" ld/st
      case(OpCode)
	`OP_LDQ_U,
	`OP_STQ_U:    AluOut[2:0] = 3'b000;
      endcase			// 
      
      // output mux
      case(OpCode)
	`OP_LDAH,
	`OP_LDA:  begin // result is data (stored in RegDest)
	   ResData = AluOut;	// 
	   ResAdr  = 0;		// 
	end
	default:  begin // result is address (used in MAU)
	   ResData = Op1;
	   ResAdr  = AluOut;
	   end
      endcase // case(OpCode)
   end // always @ (FctCode)
endmodule // Mem
