 `include "../includes/const.v"
`include "../includes/opcode.v"
`include "../includes/decode.v"

module Bcc (clk,iOpCode, iOp1, iOp2, iConst,ResData, ResAdr, Condition); 

   input `OPC  iOpCode;
   input clk;
   input `DATAQ iOp1, iOp2;
   input `CONST iConst;
   
   output `DATAQ ResData, ResAdr;
   output 	 Condition;
   
   
   reg `OPC	 OpCode;
   reg `DATAQ 	 Op1, Op2;
   reg `DATAQ 	 AluIn1, AluIn2;
   reg [31:0] 	 CondIn;    // condition evaluator input
   reg `DATAQ 	 AluOut;    // ALU output
   reg 		 C;/*carry into MSB */ 
   reg 		 Msb1;// MSB from input 1
   reg 		 Msb2;  // MSB from input 2
   reg `CONST Const;
   reg Condition;
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
	Condition = 0;
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
      
   // input mux
	 AluIn1 = {{`Q-`CM+1{Const`CONSTM}}, Const`CONSTN};
	 AluIn2 = Op2;
	 CondIn = Op1;
    // condition
	 case(OpCode)
	   `OP_BEQ:  Condition = (~|CondIn);
	   `OP_BGE:  Condition = (~|CondIn) | (|CondIn & ~CondIn[`QM]);
	   `OP_BGT:  Condition = (|CondIn & ~CondIn[`QM]);
	   `OP_BLBC: Condition = (~CondIn[0]);
	   `OP_BLBS: Condition = (CondIn[0]);
	   `OP_BLE:  Condition = (~|CondIn) | (|CondIn & CondIn[`QM]);
	   `OP_BLT:  Condition = (|CondIn & CondIn[`QM]);
	   `OP_BNE:  Condition = (|CondIn);
	   default:  Condition = 1'b1;
	 endcase
    //
      
   // output mux
   ResData = AluOut;
   ResAdr  = 0;
   end // always @ (FctCode)
endmodule // IntArith

	
   



