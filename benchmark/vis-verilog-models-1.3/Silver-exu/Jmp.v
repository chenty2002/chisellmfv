 `include "../includes/const.v"
`include "../includes/opcode.v"
`include "../includes/decode.v"

module Jmp (clk,iFctCode, iOp1, iOp2, iConst,ResData, ResAdr); 

   input `FCT  iFctCode;
   input clk;
   input `DATAQ iOp1, iOp2;
   input `CONST iConst;
   
   output `DATAQ ResData, ResAdr;
   //output 	 Condition;
   
   
   reg `FCT	 FctCode;
   reg `DATAQ 	 Op1, Op2;
   reg `DATAQ 	 AluIn1, AluIn2;
   reg [31:0] 	 CondIn;    // condition evaluator input
   reg `DATAQ 	 AluOut;    // ALU output
   reg 		 C;/*carry into MSB */ 
   reg 		 Msb1;// MSB from input 1
   reg 		 Msb2;  // MSB from input 2
   reg `CONST Const;
   //reg Condition;
   reg `DATAQ ResData, ResAdr;
   initial 
     begin 
	FctCode = 0;
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
	//Condition = 0;
	ResData=0;
	ResAdr=0;	// 
     end

   always@(posedge clk)
     begin
	FctCode = iFctCode;
	Op1 = iOp1;
	Op2 = iOp2;
	Const = iConst;
     end // always@ (posedge clk)
   
   always @(FctCode) begin
     //
    // input mux
	 AluIn1 = Op1;
	 AluIn2 = Op2;
	 case(FctCode)
	   `JMP_JMP,
	   `JMP_JSR,
	   `JMP_JSR_COROUTINE,
	   `JMP_RET:            AluOut[31:0] = {AluIn2[`QM:2], 2'b00};
	   default:             AluOut[31:0] = 0;
	 endcase
    //  

      
   // output mux
   ResData = AluOut;
   ResAdr  = 0;
   end // always @ (FctCode)
endmodule // IntArith

	
   



