 `include "../includes/const.v"
`include "../includes/opcode.v"
`include "../includes/decode.v"

module IntLogic (clk,iFctCode, iOp1, iOp2, iConst,ResData, ResAdr, Condition); 

   input `FCT  iFctCode;
   input clk;
   input `DATAQ iOp1, iOp2;
   input `CONST iConst;
   
   output `DATAQ ResData, ResAdr;
   output 	 Condition;
   
      
   reg `FCT 	 FctCode;
   reg `DATAQ 	 Op1, Op2;
   reg `DATAQ 	 AluIn1, AluIn2;
   reg `DATAX 	 CondIn;    // condition evaluator input
   reg `DATAQ 	 AluOut;    // ALU output
   reg 		 C;/*carry into MSB */ 
   reg 		 Msb1;// MSB from input 1
   reg 		 Msb2;  // MSB from input 2
   reg 		 Condition;
   reg `CONST 	 Const;
   reg `DATAQ 	 ResData, ResAdr;
   
   initial 
     begin 
	FctCode = 0;
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
	ResAdr=0;
	Const=0;
     end

   always@(posedge clk)
     begin
	FctCode = iFctCode;
	Op1 = iOp1;
	Op2 = iOp2;
	Const = iConst;
     end // always@ (posedge clk)
   
   always @(FctCode) begin
      
   // input mux
   AluIn1 = Op1;
   AluIn2 = (Const`CONSTV) ? {{`Q - `CM+1{Const`CONSTM}}, Const`CONSTN} : Op2;
    //
	 // logic operation
	 case(FctCode)
	   `INTL_AND:     AluOut = AluIn1 &  AluIn2;
	   `INTL_BIC:     AluOut = AluIn1 &~ AluIn2;
	   `INTL_BIS:     AluOut = AluIn1 |  AluIn2;
	   `INTL_EQU:     AluOut = AluIn1 ^~ AluIn2;
	   `INTL_ORNOT:   AluOut = AluIn1 |~ AluIn2;
	   `INTL_XOR:     AluOut = AluIn1 ^  AluIn2;
	   `INTL_CMOVEQ,
	     `INTL_CMOVGE,
	     `INTL_CMOVGT,
	     `INTL_CMOVLBC,
	     `INTL_CMOVLBS,
	     `INTL_CMOVLE,
	     `INTL_CMOVLT,
	     `INTL_CMOVNE:  AluOut = AluIn2;
	   //    `INTL_AMASK:   not implemented
	   //    `INTL_IMPLVER: not implemented
	   default:       AluOut = 0;
	 endcase
	 case(FctCode)
	   `INTL_CMOVEQ:  Condition = (~|CondIn);
	   `INTL_CMOVGE:  Condition = (~|CondIn) | (|CondIn & ~CondIn[`QM]);
	   `INTL_CMOVGT:  Condition = (|CondIn & ~CondIn[`QM]);
	   `INTL_CMOVLBC: Condition = (~CondIn[0]);
	   `INTL_CMOVLBS: Condition = (CondIn[0]);
	   `INTL_CMOVLE:  Condition = (~|CondIn) | (|CondIn & CondIn[`QM]);
	   `INTL_CMOVLT:  Condition = (|CondIn & CondIn[`QM]);
	   `INTL_CMOVNE:  Condition = (|CondIn);// 
	   default:       Condition = 1'b1;// 
	 endcase		// 
    //
  
   
   // output mux
   ResData = AluOut;
   ResAdr  = 0;
   end // always @ (FctCode)
endmodule // IntArith

	
   



