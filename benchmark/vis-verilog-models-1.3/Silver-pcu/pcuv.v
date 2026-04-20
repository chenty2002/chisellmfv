/*
// ----------------- // Pipeline Control Unit // -----------------
// Authors:
//   Shawn Morrison
//   Vipul Gandhi
//
// The PCU has the following responsibilities:
//
// - instruction cache miss pipeline stall
// - read data cache miss pipeline stall
// - store buffer full pipeline stall
//
// - IDU exception handling
// - EXU exception handling
//
// - force PC in IFU on exception
//
// - pipeling bubble insertion, on exceptions and load followed by ALU
//   with data dependency
//
//-----------------------------------------------------------------------*/

//`include "../includes/const.v"
//`include "../includes/decode.v"
//`include "../pcu/print_status.v"

//`define PCADR [31:0]
`define EXC [3:0]
`define FALSE  1'b0  // boolean true
`define TRUE   1'b1  // boolean false


module pcu(StepIDU, StepEXU, StepMAU, StepWB,
           WorkIDU, WorkEXU, WorkMAU, WorkWB, WorkIFU, WkMAU,
           /*NewPC, nUsePC,*/ clk, nReset, nFlushPipe, nIFUNotReady,
           EXUMultiply, nMAUNotReady, ExceptIDU, ExceptEXU,
           EXUMemory, DataDep);

// Step??? signals:
// Every stage in the pipeling gets a unique step signal.  This signal is
// active high.  Thus whenever the step signal is high to a particular stage
// in the pipeline, that pipeline stage SHOULD latch inputs.  If step is
// low, that pipeline stage should NOT latch inputs.
output StepIDU;
output StepEXU;
output StepMAU;
output StepWB;

// Work??? signals:
// These signals are used to insert a bubble into the pipeline.  Every stage
// in the pipeline gets a unique Work signal.  This signal is active
// high.  Thus whenever the Work signal is high to a particular stage in the
// pipeline, that pipeline stage SHOULD process any information it currently
// has.  If Work is low, that pipeline stage should NOT process the
// information it currently has.

output WorkIDU;
output WorkEXU;
output WorkMAU;
output WorkWB;

output WkMAU;    // Work signal that is used by the FRU unit. Not the MAU unit.

// The IFU case is a little different from the rest.  The IFU does not get
// a Step signal, just an Work signal.  When WorkIFU is NOT asserted, the IFU
// must NOT fetch a new instruction and must hang onto the current
// instruction for when WorkIFU is asserted again.

output WorkIFU;

// Step???  Work???
//   0        0             Stage is stalled and not working.
//                          - Don't clock in from previous stage.
//                          - Don't process current data.
//                          Example: Bubble insertion.  I-cache miss,
//                          IDU gets bubble insertion.
//
//   0        1             Stage is stalled, but it is working.
//                          - Don't clock in from previous stage.
//                          - Do process current data.
//                          Example: EXU is doing multiple cycle ALU
//                          operation.
//
//   1        0             Stage is not stalled, but it is not working.
//                          - Do clock in from previous stage.
//                          - Do not process data.
//                          Exapmle: Flushing stage.
//
//   1        1             Default case:
//                          Stage is not stalled and is working.

//output `PCADR NewPC;    // to IFU - PC address forced on exceptions
//output nUsePC;          // to IFU - tells IFU to use our PC on exceptions


input  clk;             // global clock
input  nReset;          // global reset

input  nFlushPipe;      // from IFU - invalidate stages EXU and MAU 
input  nIFUNotReady;    // from IFU - instruction cache miss, must wait

input  EXUMultiply;     // asserted on a Multiply instruction

input  nMAUNotReady;    // from MAU - MAU asserts on data cache miss or
                        // store buffer full so we halt the pipe

input  `EXC ExceptIDU;  // from IDU - exception request from IDU
input  `EXC ExceptEXU;  // from EXU - exception reguest from EXU

input   EXUMemory;      // Checking the decode bit to see if memory instr in EXU
input   DataDep;        // From FRU telling us to insert a bubble

// Wires for input signals
reg  I_nReset,
     I_nFlushPipe,
     I_nIFUNotReady,
     I_EXUMultiply,
     I_nMAUNotReady,
     I_EXUMemory,
     I_DataDep;

reg `EXC I_ExceptIDU,
          I_ExceptEXU;
     
// Wires for output signals
wire I_StepIDU,
     I_StepEXU,
     I_StepMAU,
     I_StepWB,
     I_WorkIFU,
     I_WorkIDU,
     I_WorkEXU,
     I_WorkMAU,
     I_WorkWB;
   
     //I_nUsePC;

//wire `PCADR I_NewPC;


// Parameter Declaration for Delays of ports
//parameter inpdel = 1;
//parameter outdel = 2;


// Internal register to count clocks of multiple-cycle multiply
reg  [4:0]  MultiplyReg;
reg  [4:0]  MultiplyRegtemp;
   

// Internal registers for the step fuction, Work Fuction, exceptions
reg  R_WorkIFU,
     R_WorkIDU,
     R_WorkEXU,
     R_WorkMAU,
     R_StepIDU,
     R_StepEXU,
     R_EXUMultiply,
     R_StepMAU;

reg `EXC  R_ExceptIDU,
          R_ExceptEXU;

// Internal Wires for the inputs to the Work output signals
reg  I_WWorkIFU,
     I_WWorkIDU,
     I_WWorkEXU,
     I_WWorkMAU,
     I_WWorkWB,
     I_SStepIDU,
     I_SStepEXU,
     I_SStepMAU,
     I_SStepWB,
     shutting_down;
   

wire `EXC I_EExceptMAU,
          I_EExceptIDU,
          I_EExceptEXU;

wire `EXC I_ExceptMAU;

   initial begin
      
      I_SStepMAU      = 1'b0;
      R_ExceptIDU     = 4'b0000;
      I_SStepEXU      = 1'b0;
      R_StepEXU       = 1'b0;
      R_ExceptEXU     = 4'b0000;
      I_WWorkIFU      = 1'b0;
      R_StepMAU       = 1'b0;
      R_StepIDU       = 1'b0;
      R_WorkMAU       = 1'b0;
      R_WorkEXU       = 1'b0;
      R_WorkIDU       = 1'b0;
      I_WWorkIDU      = 1'b0;
      I_WWorkEXU      = 1'b0;
      I_WWorkMAU      = 1'b0;
      MultiplyReg     = 5'b00000;
      R_WorkIFU       = 1'b0;
      MultiplyRegtemp = 5'b00000;
      I_SStepIDU      = 1'b0;
      I_SStepWB       = 1'b0;
      I_WWorkWB       = 1'b0;
      R_EXUMultiply   = 1'b0;
      shutting_down   = 1'b0;
      I_nReset = 0;      
      I_nFlushPipe=0;
      I_nIFUNotReady=0;
      I_EXUMultiply=0;
      I_nMAUNotReady=0;
      I_EXUMemory=0;
      I_DataDep=0;
      I_ExceptIDU= 0;
      I_ExceptEXU = 0;
      
      
   end // initial begin
// assigning input signal to internal input signal
   always @(posedge clk) begin
      I_nReset =nReset;
      I_nFlushPipe = nFlushPipe;
      I_nIFUNotReady = nIFUNotReady;
      I_EXUMultiply = EXUMultiply;
      I_nMAUNotReady =nMAUNotReady;
      I_EXUMemory = EXUMemory;
      I_DataDep   = DataDep;
      I_ExceptIDU = ExceptIDU;
      I_ExceptEXU = ExceptEXU;
   end // always @ (posedge Clk)
   
// assigning output signals 
// Internal Registers -Output Signal

assign StepIDU = I_StepIDU;
assign StepEXU = I_StepEXU;
assign StepMAU = I_StepMAU;
assign StepWB  = I_StepWB;
assign WorkIFU  = I_WorkIFU;
assign WorkIDU  = I_WorkIDU;
assign WorkEXU  = I_WorkEXU;
assign WorkMAU  = I_WorkMAU;
assign WorkWB   = I_WorkWB;
assign WkMAU    =  I_WWorkMAU;
//assign nUsePC   = I_nUsePC;
//assign NewPC    = I_NewPC;

// assigning inputs to outputs
assign I_StepIDU = I_SStepIDU;
assign I_StepEXU = I_SStepEXU;
assign I_StepMAU = I_SStepMAU;
assign I_StepWB  = I_SStepWB;
assign I_WorkIFU = I_WWorkIFU;
assign I_WorkIDU = I_WWorkIDU;
assign I_WorkEXU = I_WWorkEXU;
assign I_WorkMAU = I_WWorkMAU & EXUMemory;
assign I_WorkWB  = I_WWorkWB;

//assignment for exception

assign I_EExceptMAU[3] = R_ExceptEXU[3] & I_nFlushPipe;
assign I_EExceptMAU[2:0] = R_ExceptEXU[2:0];

assign I_EExceptIDU[3] = I_ExceptIDU[3] & I_nFlushPipe;
assign I_EExceptEXU[3] = (I_ExceptEXU[3] & I_nFlushPipe) | R_ExceptIDU[3];
assign I_EExceptIDU[2:0] = I_ExceptIDU[2:0];
assign I_EExceptEXU[2] = (I_ExceptEXU[3] & I_ExceptEXU[2]) | (~I_ExceptEXU[3] & R_ExceptIDU[2]);
assign I_EExceptEXU[1] = (I_ExceptEXU[3] & I_ExceptEXU[1]) | (~I_ExceptEXU[3] & R_ExceptIDU[1]);
assign I_EExceptEXU[0] = (I_ExceptEXU[3] & I_ExceptEXU[0]) | (~I_ExceptEXU[3] & R_ExceptIDU[0]);


always @(I_nFlushPipe or I_nIFUNotReady or I_nMAUNotReady
         or I_EXUMemory or I_DataDep or R_WorkIFU
         or R_WorkIDU or R_WorkEXU or R_WorkMAU or R_StepIDU 
         or R_StepEXU or R_StepMAU or MultiplyReg or I_EExceptMAU[3] 
         or I_EExceptIDU[3] or I_EExceptEXU[3] or R_EXUMultiply)
   begin
      case ({I_EExceptIDU[3],I_EExceptEXU[3], I_nMAUNotReady, (R_EXUMultiply & ~MultiplyReg[4] & I_WWorkEXU)})
            4'b0010:               //IFU Not Ready
                                   //No Stalls Default
                                   //DataDependenci
                                   //PipeFlush
            begin
               I_WWorkIFU = (~I_DataDep)  & ~(I_EExceptMAU[3] | I_EExceptIDU[3] | I_EExceptEXU[3]); 
               I_WWorkIDU = I_DataDep | (I_nIFUNotReady & R_WorkIFU);
               I_WWorkEXU = I_nFlushPipe & (~I_DataDep) & R_WorkIDU;
               I_WWorkMAU = I_nFlushPipe & R_WorkEXU;
               I_WWorkWB  = R_WorkMAU;
               I_SStepIDU = ~I_DataDep & I_nIFUNotReady & R_WorkIFU;
               I_SStepEXU = ~I_DataDep & R_StepIDU;
               I_SStepMAU = R_StepEXU;
               I_SStepWB  = R_StepMAU;
            end
         4'b1001,
         4'b1000,
         4'b1100,
         4'b1101,
         4'b0100,
         4'b0101,                          //MAU not ready with exception
         4'b0000,
         4'b0001:                          //MAU not ready
            begin
               I_WWorkIFU = `FALSE;
               I_WWorkIDU = R_WorkIDU;
               I_WWorkEXU = R_WorkEXU;
               I_WWorkMAU = R_WorkMAU;
               I_WWorkWB  = `FALSE;
               I_SStepIDU = `FALSE;
               I_SStepEXU = `FALSE;
               I_SStepMAU = `FALSE;
               I_SStepWB  = `FALSE;
            end
         4'b1011,                          // EXU Multiply & IDU Exception
         4'b0011:                         
            begin
               I_WWorkIFU = `FALSE;
               I_WWorkIDU = R_WorkIDU;
               I_WWorkEXU = R_WorkEXU;
               I_WWorkMAU = `FALSE;
               I_WWorkWB  = R_WorkMAU;
               I_SStepIDU = `FALSE;
               I_SStepEXU = `FALSE;
               I_SStepMAU = `FALSE;
               I_SStepWB  = R_StepMAU;
            end
          4'b0110,
          4'b1110,
          4'b1111,
          4'b0111:                              //EXU Exception
            begin
               I_WWorkIFU = `FALSE;
               I_WWorkIDU = `FALSE;
               I_WWorkEXU = `FALSE;
               I_WWorkMAU = `FALSE;
               I_WWorkWB  = R_WorkMAU;
               I_SStepIDU = ~I_DataDep & I_nIFUNotReady & R_WorkIFU;
               I_SStepEXU = ~I_DataDep & R_StepIDU;
               I_SStepMAU = R_StepEXU;
               I_SStepWB  = R_StepMAU;
            end
         4'b1010:                               // IDU exception and MAU is ready and No Multiply
            begin  
               I_WWorkIFU = `FALSE;
               I_WWorkIDU = `FALSE;
               I_WWorkEXU = `FALSE;
               I_WWorkMAU = R_WorkEXU;
               I_WWorkWB  = R_WorkMAU;
               I_SStepIDU = ~I_DataDep & I_nIFUNotReady & R_WorkIFU;
               I_SStepEXU = ~I_DataDep & R_StepIDU;
               I_SStepMAU = R_StepEXU;
               I_SStepWB  = R_StepMAU;
            end
      endcase 
   end //always combo block

// Latching in Work Signals except IFU
always @(posedge clk)
   begin
      R_WorkIDU = I_WWorkIDU;
      R_WorkEXU = I_WWorkEXU;
      R_WorkMAU = I_WWorkMAU;


      // Slight behavioral hack for multiple-cycle multiply.
      if (I_nMAUNotReady & (MultiplyReg[4] | I_EExceptEXU[3])) 
         MultiplyReg[4:0] = 5'b0;
      else if (R_EXUMultiply)
         begin
            MultiplyReg = fun_shift(MultiplyReg);
	    MultiplyReg = MultiplyRegtemp;
	    MultiplyReg[0] = I_WWorkEXU;
         end
   end
   
   function [4:0] fun_shift;
      input [4:0] A;
      case (A)
	5'b00000: fun_shift = 5'b00000;
	5'b00001: fun_shift = 5'b00010;
	5'b00010: fun_shift = 5'b00100;
	5'b00011: fun_shift = 5'b00110;
	5'b00100: fun_shift = 5'b01000;
	5'b00101: fun_shift = 5'b01010;
	5'b00110: fun_shift = 5'b01100;
	5'b00111: fun_shift = 5'b01110;
	5'b01000: fun_shift = 5'b10000;
	5'b01001: fun_shift = 5'b10010;
	5'b01010: fun_shift = 5'b10100;
	5'b01011: fun_shift = 5'b10110;
	5'b01100: fun_shift = 5'b11000;
	5'b01101: fun_shift = 5'b11010;
	5'b01110: fun_shift = 5'b11100;
	5'b01111: fun_shift = 5'b11110;
	5'b10000: fun_shift = 5'b00000;
	5'b10001: fun_shift = 5'b00010;
	5'b10010: fun_shift = 5'b00100;
	5'b10011: fun_shift = 5'b00110;
	5'b10100: fun_shift = 5'b01000;
	5'b10101: fun_shift = 5'b01010;
	5'b10110: fun_shift = 5'b01100;
	5'b10111: fun_shift = 5'b01110;
	5'b11000: fun_shift = 5'b10000;
	5'b11001: fun_shift = 5'b10010;
	5'b11010: fun_shift = 5'b10100;
	5'b11011: fun_shift = 5'b10110;
	5'b11100: fun_shift = 5'b11000;
	5'b11101: fun_shift = 5'b11010;
	5'b11110: fun_shift = 5'b11100;
	5'b11111: fun_shift = 5'b11110;
      endcase			// case(A)
   endfunction			// fun_shift
   

   
   always @(posedge clk)
     begin
      if(I_nMAUNotReady)
      begin
        if(!(R_EXUMultiply & ~MultiplyReg[4]))
           R_ExceptIDU = I_EExceptIDU;
      R_ExceptEXU = I_EExceptEXU;
      end
   end

always @(posedge clk)   // Latching in Step signals and IFU work signal
   begin
      if(!I_nMAUNotReady)
         begin
         end 
      else if (R_EXUMultiply & ~MultiplyReg[4])
         begin
            R_StepMAU = I_SStepMAU;
         end
      else
         begin
            R_WorkIFU = I_WWorkIFU | I_DataDep;
            R_StepIDU = I_SStepIDU | I_DataDep;
            R_StepEXU = I_SStepEXU;
            R_EXUMultiply = (I_EXUMultiply & ~I_DataDep);
            R_StepMAU = I_SStepMAU;
         end
   end

// On shutdown, we disable all works and steps.
always @(shutting_down)  //use shtting_down as a shutting button internally
  // chip.event_shutting_down-- 
   begin
      assign I_WWorkIFU = `FALSE; 
      assign I_WWorkIDU  = `FALSE;
      assign I_WWorkEXU  = `FALSE;
      assign I_WWorkMAU  = `FALSE;
      assign I_WWorkWB   = `FALSE;
      assign I_SStepIDU  = `FALSE;
      assign I_SStepEXU  = `FALSE;
      assign I_SStepMAU  = `FALSE;
      assign I_SStepWB   = `FALSE;
      //assign R_ExceptIDU = `FALSE;
      //assign R_ExceptEXU = `FALSE;
   end

// Reset Block Resets all Flip Flops
always @(I_nReset) 
   begin
      if (!I_nReset) 
         begin
            assign R_WorkIFU  = `FALSE;
            assign R_WorkIDU  = `FALSE;
            assign R_WorkEXU  = `FALSE;
            assign R_WorkMAU  = `FALSE;
            assign R_StepIDU  = `FALSE;
            assign R_StepEXU  = `FALSE;
            assign R_StepMAU  = `FALSE;
           // assign R_ExceptIDU = `FALSE;
           // assign R_ExceptEXU = `FALSE;
           // assign R_EXUMultiply = `FALSE;
           // assign MultiplyReg = 5'b00000;
         end 
      /*else 
         begin
            deassign R_WorkIFU;
            deassign R_WorkIDU;
            deassign R_WorkEXU;
            deassign R_WorkMAU;
            deassign R_StepIDU;
            deassign R_StepEXU;
            deassign R_StepMAU;
            deassign R_ExceptIDU;
            deassign R_ExceptEXU;
            deassign R_EXUMultiply;
            deassign MultiplyReg;
         end */
   end
//print_status print_status1(clk, I_EExceptMAU[3], I_EExceptMAU);
endmodule // PCU












