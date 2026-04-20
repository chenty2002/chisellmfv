// This is a model of the VIPER (Verifiable Integrated Processor for
// Enhanced Reliability) derived from the synthesizable VHDL description by
// P. Subbarao, B. Subramanyam, and J. Roy of the University of Cincinnati.
// This is in turn is based on
//
//   W. J. Cullyer, "Implementing Safety Critical Systems: The VIPER
//   Microprocessor", pp. 1-26, in G. Birtwistle and P. A. Subrahmanyam
//   (eds.), "VLSI Specification, Verification, and Synthesis",
//   Kluwer Academic Publishers, 1988.
//
// The translation into Verilog is by Fabio Somenzi <Fabio@Colorado.EDU>.
//
// This model does not include the memory interface (MAR, MBR, read and write
// signals) and may contain bugs.

// The VIPER microprocessor is a simple microprocessor with three 32-bit
// registers: A (accumulator), X and Y (index registers), and a
// 20-bit program counter, P.  There is a single-bit register, B, which holds
// the results of comparisons and can be concatenated with registers in shift
// operations.  Unique to VIPER is the 'stop' signal from the ALU.  Any illegal
// operation, arithmetic overflow or computation of an illegal address causes
// the device to stop and raise an exception.
// All instructions have an identical format and comparisons subsume all other
// operations.
// VIPER has a memory size of 1M words, each 32 bits wide.

typedef enum {FETCH, EXEC} State;

module viper(clock, addr, datai, datao);
    input clock;
    output [19:0] addr;
    input [31:0]  datai;
    output [31:0] datao;
    // A (regfile[0]) is the accumulator
    // X (regfile[1]) and Y (regfile[2]) are index registers
    // P (regfile[3]) is the program counter
    reg [31:0]	regfile[0:3];
    reg		B;    // holds the results of comparisons
    reg		STOP; // set whenever an illegal operation is executed
    reg [31:0]	IR;   // instruction register
    reg [19:0] 	addr;
    reg [31:0] 	datao;
    State reg   state;

    // Instruction format.
    wire [1:0]	rf =   IR[31:30];	// register select field
    wire [1:0]	mf =   IR[29:28];	// memory select field
    wire [2:0]	df =   IR[27:25];	// destination select field
    wire	cf =   IR[24];		// comparison flag field
    wire [3:0]	ff =   IR[23:20];	// function select field
    wire [19:0]	tail = IR[19:0];	// address or offset field


    // This function selects the the destination register.  An index (d) 
    // is returned by this procedure.  The index can have four values.
    // Depending on the value of d (3, 2, 1, or 0), the destination
    // register is P (regfile[3]), Y (regfile[2]), X (regfile[1]), or A
    // (regfile[0]).
    function [1:0] destination;
	input [2:0] df;
	input B;
	begin: _destination
	    destination = 2'b00;  // this catches the unspecified cases
	    if (df == 3'b101) begin
		if (~B) destination = 2'b11;  // else unspecified
	    end
	    else if (df == 3'b100) begin
		if (B) destination = 2'b11;  // else unspecified
	    end
	    else if (df == 3'b011) destination = 2'b11;
	    else if (df == 3'b010) destination = 2'b10;
	    else if (df == 3'b001) destination = 2'b01;
	    else if (df == 3'b000) destination = 2'b00;
	    // cases for df in {111,110} are missing.
	end // block: _destination
    endfunction // destination

    wire [31:0]	r = regfile[rf];
    wire [31:0] m = mf == 0 ? {12'b0,tail} : datai;
    wire [1:0]	d = destination(df,B);


    // Compute the absolute value.
    function [31:0] abs;
	input [31:0] data;
	begin: _abs
	    abs = data[31] ? data : 32'b0 - data;
	end // block: _abs
    endfunction // abs


    // Write the contents of the selected register to memory. 
    task write_mem; 
	begin: _write_mem
	    case (mf)
	      2'b00: addr = tail;
	      2'b01: addr = tail;
	      2'b10: addr = regfile[1] + tail;
              2'b11: addr = regfile[2] + tail;
	    endcase
	    datao = r;
	end // block: _write_mem
    endtask // write_mem


    // This task handles the compare instructions. There are 16 such 
    // instructions in VIPER.  Each of the compare instructions compares the 
    // contents of a register (r) to a memory element (m); the B register
    // is set or reset depending on the success or the failure of the
    // comparison.
    task comparison;
    begin: _comparison
	case (ff)
	  4'b0000: B = r < m;
	  4'b0001: B = r >= m;
	  4'b0010: B = r == m;
	  4'b0011: B = r != m;
	  4'b0100: B = r <= m;
	  4'b0101: B = r > m;
	  4'b0110: B = abs(r) < m;
	  4'b0111: B = abs(~r) < m;
	  4'b1000: B = B | (r < m);
	  4'b1001: B = B | (r >= m);
	  4'b1010: B = B | (r == m);
	  4'b1011: B = B | (r != m);
	  4'b1100: B = B | (r <= m);
	  4'b1101: B = B | (r > m);
	  4'b1110: B = B | (abs(r) < m);
	  4'b1111: B = B | (abs(~r) < m);
	endcase
    end // block: _comparison
    endtask // comparison

    wire [32:0] rpm = {1'b0,r} + {1'b0,m};
    wire [32:0] rmm = {1'b0,r} - {1'b0,m};

    // This task handles the not-compare instructions. The value of df
    // decides the destination of the results.
    task not_comparison;
    begin: _not_comparison
	if (!((df == 3'b111) || (df == 3'b110))) begin
	    case (ff)
	      4'b0000: begin	// negate m
		  regfile[d] = 32'h0 - m;
	      end
	      4'b0001: begin	// call 
		  regfile[2] = regfile[3]; 
		  regfile[3] = m;
	      end
	      4'b0010: begin	// read from peripheral
		  regfile[d] = m;
	      end
	      4'b0011: begin	// read from memory
		  regfile[d] = m;  
		  // STOP = 1;
	      end
	      4'b0100: begin	// add r and m and store carry in B
		  B = rpm[32];
		  regfile[d] = rpm[31:0];
	      end
	      4'b0101: begin	// add r and m and stop on overflow
		  STOP = rpm[32];
		  regfile[d] = rpm[31:0];
	      end
	      4'b0110: begin	// subtract r and m and store borrow in B
		  B = rmm[32];
		  regfile[d] = rmm[31:0];
	      end
	      4'b0111: begin	// subtract r and m and stop on overflow
		  STOP = rmm[32];
		  regfile[d] = rmm[31:0];
	      end
	      4'b1000: begin	// XOR r and m
		  regfile[d] = r ^ m;
	      end
	      4'b1001: begin	// AND r and m
		  regfile[d] = r & m;
	      end
	      4'b1010: begin	// NOR r and m
		  regfile[d] = ~(r | m);
	      end
	      4'b1011: begin	// AND r and NOT(m)
		  regfile[d] = r & ~m;
	      end
	      4'b1100:
		  case (mf)
		    // Shift right, copy the sign bit.
		    2'b00:  regfile[d] = {r[31],r[31:1]};
		    // Shift right through B.
		    2'b01:  regfile[d] = {B,r[31:1]};
		    2'b10:  begin // Shift left, stop on overflow.
			STOP = r[31];
			regfile[d] = {r[30:0],1'b0};
		    end
		    2'b11: begin // Shift left through B.
			B = r[31];
			regfile[d] = {r[30:0],1'b0};
		    end
		  endcase
	      4'b1101: STOP = 1;		   // illegal instruction
	      4'b1110: STOP = 1;		   // illegal instruction
	      4'b1111: STOP = 1;		   // illegal instruction
	    endcase // case ff
	end
	else if (df == 3'b111) write_mem;
	else if (df == 3'b110) write_mem;	// should be write_io
    end // block: _not_comparison
    endtask // not_comparison


    // This procedure starts the decoding by checking the cf field.
    task decode;
    begin: _decode
	case (cf)
	  1'b1: comparison;
	  1'b0: not_comparison;
	endcase
    end // block: _decode
    endtask // decode


    initial begin
	regfile[0] = 0;
	regfile[1] = 0;
	regfile[2] = 0;
	regfile[3] = 0;
	IR = 0;
	B = 0;
	STOP = 0;
	datao = 0;
	addr = 0;
	state = FETCH;
    end // initial begin

    wire [31:0]	reg_P = regfile[3];

    always @ (posedge clock) begin
	if ((STOP == 0) && !(reg_P > 32'h000f_ffff)) begin
	    case (state)
	      FETCH: begin
		  addr = reg_P[19:0];
		  IR = datai;
		  state = EXEC;
	      end
	      EXEC: begin
		  regfile[3] = 32'h0000_0008 + reg_P;
		  decode;
		  state = FETCH;
	      end
	    endcase
	end
    end

endmodule // viper
