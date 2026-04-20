 `include "../includes/const.v"
`include "../includes/opcode.v"
`include "../includes/decode.v"

module IntArith(clk,iFctCode, iOp1, iOp2, iConst,ResData, ResAdr, Exception); 

   input `FCT  iFctCode;
   input clk;
   input `DATAQ iOp1, iOp2;
   input `CONST iConst;
   
   output `DATAQ ResData, ResAdr;
   output `EXC 	 Exception;	// exception request from ALU
   
   reg `FCT 	 FctCode;
   reg `DATAQ 	 Op1, Op2;
   reg `DATAQ 	 AluIn1, AluIn2;
   reg `DATAX 	 CondIn;    // condition evaluator input
   reg `DATAQ 	 AluOut;    // ALU output
   reg 		 C;/*carry into MSB */ 
   reg 		 Msb1;// MSB from input 1
   reg 		 Msb2;  // MSB from input 2
   reg `CONST Const;
   reg `EXC   Exception;
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
	Exception = 0;
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
      
   // input mux
   AluIn1 = Op1;
   AluIn2 = (Const`CONSTV) ? {{`Q - `CM+1{Const`CONSTM}}, Const`CONSTN} : Op2;
    //
	 // adder operation
   case(FctCode)
     `INTA_ADDL,
     `INTA_ADDLV:   AluOut[31:0] = AluIn1`DATAL + AluIn2`DATAL;
     // `INTA_S4ADDL:  AluOut`DATAL = (AluIn1`DATAL<<2) + AluIn2`DATAL;
     // `INTA_S8ADDL:  AluOut`DATAL = (AluIn1`DATAL<<3) + AluIn2`DATAL;
     `INTA_ADDQ,
       `INTA_ADDQV:   AluOut[31:0] = AluIn1`DATAQ + AluIn2`DATAQ;
     // `INTA_S4ADDQ:  AluOut`DATAQ = (AluIn1`DATAQ<<2) + AluIn2`DATAQ;
     // `INTA_S8ADDQ:  AluOut`DATAQ = (AluIn1`DATAQ<<3) + AluIn2`DATAQ;
     /* `INTA_CMPBGE:  CondIn`DATAB = (
      ((({1'b0, AluIn1[63:56]} +
      {1'b0, ~AluIn2[63:56]} + 1) & 9'h100) >> 1) |
      ((({1'b0, AluIn1[55:48]} +// 
      {1'b0, ~AluIn2[55:48]} + 1) & 9'h100) >> 2) |
      ((({1'b0, AluIn1[47:40]} +
      {1'b0, ~AluIn2[47:40]} + 1) & 9'h100) >> 3) |
      ((({1'b0, AluIn1[39:32]} +// 
      {1'b0, ~AluIn2[39:32]} + 1) & 9'h100) >> 4) |
      ((({1'b0, AluIn1[31:24]} +// 
      {1'b0, ~AluIn2[31:24]} + 1) & 9'h100) >> 5) |
      ((({1'b0, AluIn1[23:16]} +// 
      {1'b0, ~AluIn2[23:16]} + 1) & 9'h100) >> 6) |
      ((({1'b0, AluIn1[15:8]} +// 
      {1'b0, ~AluIn2[15:8]}  + 1) & 9'h100) >> 7) |
      ((({1'b0, AluIn1[7:0]} +// 
      {1'b0, ~AluIn2[7:0]}   + 1) & 9'h100) >> 8));
      */			// 
     `INTA_CMPEQ,
       `INTA_CMPLT,
       `INTA_CMPLE:   CondIn[31:0] = {AluIn1[`QM], AluIn1`DATAQ} +
				     {~AluIn2[`QM], ~AluIn2`DATAQ} + 1;
     `INTA_CMPULT,		// 
       `INTA_CMPULE:  CondIn[31:0] = {1'b0, AluIn1`DATAQ} +
				     {1'b1, ~AluIn2`DATAQ} + 1;
     `INTA_SUBL,		// 
       `INTA_SUBLV:   AluOut[31:0] = AluIn1`DATAL + ~AluIn2`DATAL + 1;
     // `INTA_S4SUBL:  AluOut`DATAL = (AluIn1`DATAL<<2) + ~AluIn2`DATAL + 1;
     // `INTA_S8SUBL:  AluOut`DATAL = (AluIn1`DATAL<<3) + ~AluIn2`DATAL + 1;
     `INTA_SUBQ,		// 
       `INTA_SUBQV:   AluOut[31:0] = AluIn1`DATAQ + ~AluIn2`DATAQ + 1;
     // `INTA_S4SUBQ:  AluOut`DATAQ = (AluIn1`DATAQ<<2) + ~AluIn2`DATAQ + 1;
     // `INTA_S8SUBQ:  AluOut`DATAQ = (AluIn1`DATAQ<<3) + ~AluIn2`DATAQ + 1;
     default:       AluOut = 0;	// 
   endcase			// case(FctCode)
   
   //
    // sign extension 32 -> 64 bits
   /* case(FctCode)
    `INTA_ADDL,// 
    `INTA_ADDLV,// 
    `INTA_S4ADDL,// 
    `INTA_S8ADDL,// 
    `INTA_SUBL,
    `INTA_SUBLV,// 
    `INTA_S4SUBL,// 
    `INTA_S8SUBL:  AluOut[`QM:`L] = {`Q-`L{AluOut[`LM]}};
    endcase
    *///
   // condition computation
   case(FctCode)		// 
     `INTA_CMPBGE:  AluOut[31:0] = {{`Q-`B{1'b0}}, CondIn`DATAB};
     `INTA_CMPEQ:   AluOut[31:0] = {{`QM{1'b0}},(~|CondIn)};
     `INTA_CMPULT,		// 
       `INTA_CMPLT:   AluOut[31:0] = {{`QM{1'b0}},(|CondIn & CondIn[`Q])};
     `INTA_CMPULE,		// 
       `INTA_CMPLE:   AluOut[31:0] = {{`QM{1'b0}},
				      (~|CondIn) | (|CondIn & CondIn[`Q])};
   endcase			// case(FctCode)
   
   //
   // overflow checking
   case(FctCode)
     // regenerate carry into MSB and detemine MSBs of inputs
     `INTA_ADDLV:   begin
	{C, AluOut[30:0]} = AluIn1[30:0] + AluIn2[30:0];
	Msb1 = AluIn1[31];
	Msb2 = AluIn2[31];
     end // case: `INTA_ADDLV
     
     /*`INTA_ADDQV:   begin
      {C, AluOut[62:0]} = AluIn1[62:0] + AluIn2[62:0];
      Msb1 = AluIn1[63];
      Msb2 = AluIn2[63];
                     end */
     `INTA_SUBLV:   begin
	{C, AluOut[30:0]} = AluIn1[30:0] +
			    {1'b0, ~AluIn2[30:0]} + 1;
	Msb1 = AluIn1[31];	// 
	Msb2 = ~AluIn2[31];	// 
     end			// 
				/*`INTA_SUBQV:   begin
				 {C, AluOut[62:0]} = AluIn1[62:0] +
				 {1'b0, ~AluIn2[62:0]} + 1;// 
				 Msb1 = AluIn1[63];// 
				 Msb2 = ~AluIn2[63];// 
                     end	 // 
				 */// 
   endcase			// case(FctCode)
   
   case(FctCode)
     // check overflow
     `INTA_ADDLV,
     `INTA_ADDQV,
     `INTA_SUBLV,
     `INTA_SUBQV:   if ((C & ~Msb1 & ~Msb2) || (~C & Msb1 & Msb2)) begin
	Exception = `EXC_OVFL;
     end else begin
	Exception = `EXC_NONE;
     end
     default:       Exception = `EXC_NONE;
   endcase
   
   // output mux
   ResData = AluOut;
   ResAdr  = 0;
   end // always @ (FctCode)
endmodule // IntArith

	
   



