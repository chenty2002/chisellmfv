// Elementary pipeline plus monitor.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>
//
// This pipeline consists of an ALU and a register file.
// At each clock cycle the pipeline starts the execution of a instruction,
// which completes in three cycles unless stalled:
//  1. Read the operands from the register file.
//  2. Perform the ALU operation.
//  3. Write result back to the register file.
//
// The pipeline supports bypass of the write-back stage. Therefore, if an
// instruction depends on the result of the one immediately preceeding it,
// the pipeline needs to stall for just one cycle.
//
// This is a highly artificial example. Notice in particular that the only
// load operations are those that set a register to either 0 or 1.

module palu(clock,stall,opcode,src1,src2,dest,aluOut,fair);
    parameter	 MSB = 7;
    input	 clock;
    input	 stall;
    input [2:0]	 opcode;
    input [2:0]	 src1;
    input [2:0]	 src2;
    input [2:0]	 dest;
    output [MSB:0] aluOut;
    output 	   fair;

    reg		 bubbleEx;
    reg		 bubbleWb;
    reg [2:0]	 destEx;
    reg [2:0]	 destWb;
    reg [2:0]	 opcodeEx;

    reg [MSB:0]	 regFile[0:7];
    reg [MSB:0]	 op1;
    reg [MSB:0]	 op2;
    reg [MSB:0]	 aluOut;

    integer	 i;

    parameter	 ZERO = 3'd0,
		 ONE = 3'd1,
		 ADD = 3'd2,
		 SUB = 3'd3,
		 NAND = 3'd4,
		 SRL = 3'd5,
		 CPA = 3'd6,
		 NOT = 3'd7;

    function [MSB:0] ALU;
	input [2:0] opc;
	input [MSB:0] o1;
	input [MSB:0] o2;
	begin: _ALU
	    case (opc)
	      ZERO: ALU = 0;
	      ONE:  ALU = 1;
	      ADD:  ALU = o1 + o2;
	      SUB:  ALU = o1 - o2;
	      NAND: ALU = ~(o1 & o2);
	      SRL:  begin
		  ALU[MSB-1:0] = o1[MSB:1];
		  ALU[MSB] = 0;
	      end
	      CPA:  ALU = o1;
	      NOT:  ALU = ~o1;
	endcase // case (opc)
    end // block: _ALU
    endfunction // ALU

    initial begin
	for (i = 0; i < 8; i = i + 1)
	    regFile[i] = 0;
	op1 = 0;
	op2 = 0;
	aluOut = 0;
	bubbleEx = 0;
	bubbleWb = 0;
	destEx = 0;
	destWb = 0;
	opcodeEx = 0;
    end // initial begin

    always @ (posedge clock) begin
	if (~bubbleWb) begin
	    regFile[destWb] = aluOut;
	end // if (~bubbleWb)
	if (~bubbleEx) begin
	    aluOut = ALU(opcodeEx,op1,op2);
	    destWb = destEx;
	end // if (~bubbleEx)
	if (~stall) begin
	    if (src1 == destWb)		// bypass?
		op1 = aluOut;
	    else
		op1 = regFile[src1];
	    if (src2 == destWb)		// bypass?
		op2 = aluOut;
	    else
		op2 = regFile[src2];
	    opcodeEx = opcode;
	    destEx = dest;
	end // if (~stall)
	// Update pipe stall registers.
	bubbleWb = bubbleEx;
	bubbleEx = stall;
    end // always @ (posedge clock)

    monitor #(MSB) mtr(clock,opcodeEx,destEx,bubbleEx,regFile[0],fair);

endmodule // palu



// This monitor model checks an LTL property.
// In this case the property is:
// G((opcodeEx=1 * destEx=0 * bubbleEx=0) -> F(regFile[0]=1))
// The Buechi automaton is for the complement of the property,
// which is F(opcodeEx=1 * destEx=0 * bubbleEx=0 * G !regFile[0]=1).
module monitor(clock,opcodeEx,destEx,bubbleEx,regfile0,fair);
    parameter     MSB=7;
    input         clock;
    input [2:0]   opcodeEx;
    input [2:0]   destEx;
    input 	  bubbleEx;
    input [MSB:0] regfile0;
    output 	  fair;

    reg [1:0] 	  state;
    wire [1:0]    zeroorone;
    wire 	  trigger;

    assign fair = (state == 1);
    assign zeroorone[0] = $ND(0,1);
    assign zeroorone[1] = 0;
    assign trigger = opcodeEx == 1 & destEx == 0 && bubbleEx == 0;

    initial state = 0;

    always @ (posedge clock) begin
	case (state)
	  0: state = trigger ? zeroorone: 0;
	  1: state = regfile0 == 1 ? 2 : 1;
	  2: state = 2;
	endcase // case(state)
    end // always @ (posedge clk)

endmodule // monitor
