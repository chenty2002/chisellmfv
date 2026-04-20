// Branch Prediction Buffer
//
// Author: Bobbie Manne
//
// Modified by Fabio Somenzi to use single-phase clocking.

module branchPredictionBuffer(clock,stall,inst_addr,update,branch_result,
			      buffer_addr,buffer_offset,prediction);
    parameter	 PRED_BUFFER_SIZE = 4; // 128 lines with 4 entries/line.
    input [1:0]  inst_addr; // We need to decode up to 4 instructions.
    input 	 branch_result; // Result of a branch calculation.
    input [1:0]  buffer_addr;
    input [1:0]  buffer_offset;
    input 	 clock;
    input 	 stall;      // Stalls when instructions are not available.
    input 	 update;     // Tells the buffer that an update is ready.
    output [3:0] prediction; // Prediction bits sent out to decoder stage.

    reg [3:0] 	 prediction;
    reg [1:0] 	 state_bank0 [PRED_BUFFER_SIZE-1:0];
    reg [1:0] 	 state_bank1 [PRED_BUFFER_SIZE-1:0];
    reg [1:0] 	 state_bank2 [PRED_BUFFER_SIZE-1:0];
    reg [1:0] 	 state_bank3 [PRED_BUFFER_SIZE-1:0];

    integer 	 i;

    // The encoding is the following:
    //  0: strong not taken
    //  1: weak not taken
    //  2: weak taken
    //  3: strong taken
    // A branch result of "taken" causes the appropriate entry to be
    // incremented. A branch result of "not taken" causes it to be
    // decremented.

    // synopsys translate_off 
    // Always begin in a weak, not taken state
    initial begin
	for (i=0; i<PRED_BUFFER_SIZE; i=i+1) begin
	    state_bank0[i] = 2'b01;
	    state_bank1[i] = 2'b01;
	    state_bank2[i] = 2'b01;
	    state_bank3[i] = 2'b01;
	end // for (i=0; i<PRED_BUFFER_SIZE; i=i+1)
	prediction = 4'b0000;
    end // initial begin
    // synopsys translate_on

    always @(posedge clock) begin
	if (!stall) begin
	    if (state_bank3[inst_addr] > 1)
		prediction[3] = 1;
	    else
		prediction[3] = 0;
	    if (state_bank2[inst_addr] > 1)
		prediction[2] = 1;
	    else
		prediction[2] = 0;
	    if (state_bank1[inst_addr] > 1)
		prediction[1] = 1;
	    else
		prediction[1] = 0;
	    if (state_bank0[inst_addr] > 1)
		prediction[0] = 1;
	    else
		prediction[0] = 0;
	end // if (!stall)

	// We only need to update one location at a time.
	if (update) begin
	    if (branch_result) begin // The branch was taken.
		if (buffer_offset == 0) begin
		    if (state_bank0[buffer_addr] != 2'b11)
		      state_bank0[buffer_addr] = state_bank0[buffer_addr] + 1;
		end
		else if (buffer_offset == 1) begin
		    if (state_bank1[buffer_addr] != 2'b11)
		      state_bank1[buffer_addr] = state_bank1[buffer_addr] + 1;
		end
		else if (buffer_offset == 2) begin
		    if (state_bank2[buffer_addr] != 2'b11)
		      state_bank2[buffer_addr] = state_bank2[buffer_addr] + 1;
		end
		else begin
		    if (state_bank3[buffer_addr] != 2'b11)
		      state_bank3[buffer_addr] = state_bank3[buffer_addr] + 1;
		end
	    end // if (branch_result)
	    else begin
		if (buffer_offset == 0) begin
		    if (state_bank0[buffer_addr] != 2'b00)
		      state_bank0[buffer_addr] = state_bank0[buffer_addr] - 1;
		end
		else if (buffer_offset == 1) begin
		    if (state_bank1[buffer_addr] != 2'b00)
		      state_bank1[buffer_addr] = state_bank1[buffer_addr] - 1;
		end
		else if (buffer_offset == 2) begin
		    if (state_bank2[buffer_addr] != 2'b00)
		      state_bank2[buffer_addr] = state_bank2[buffer_addr] - 1;
		end
		else begin
		    if (state_bank3[buffer_addr] != 2'b00)
		      state_bank3[buffer_addr] = state_bank3[buffer_addr] - 1;
		end
	    end // else: !if(branch_result)
	end // if (update)
    end // always @ (posedge clock)

endmodule // branchPredictionBuffer
