// Verilog translation of the original b09 circuit from the ITC99
// benchmark set.

// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {INIT, RECEIVE, EXECUTE, LOAD_OLD} State;

module b09(clock, X, Y);
    input clock;
    input X;
    output Y;

    parameter Bit_start = 1;
    parameter Bit_stop = 0;
    parameter Bit_idle = 0;

    reg       Y;
    State reg stato;
    reg [8:0] d_in;
    reg [7:0] d_out;
    reg [7:0] old;

    initial begin
	stato = INIT;
	d_in = 0;   
	d_out = 0;  
	old = 0;  
	Y = Bit_idle;
    end

    always @ (posedge clock) begin
	case (stato)
	  INIT: begin
	      stato = RECEIVE;
	      d_in = 0;
	      d_out = 0;
	      old = 0;
	      Y = Bit_idle;
	  end

	  RECEIVE: begin
	      if (d_in[0] == Bit_start) begin
		  old = d_in[8:1];  
		  Y = Bit_start;
		  d_out = d_in[8:1];
		  d_in = {Bit_start, 8'b0};
		  stato = EXECUTE;
	      end else begin
		  d_in = {X, d_in[8:1]};
		  stato = RECEIVE;
	      end
	  end

	  EXECUTE: begin
	      if (d_in[0] == Bit_start) begin
		  Y = Bit_stop;
		  stato = LOAD_OLD;
	      end else begin
		  Y = d_out[0];
		  d_out = {Bit_idle, d_out[7:1]};
		  stato = EXECUTE;
	      end
	      d_in = {X, d_in[8:1]};
	  end

	  LOAD_OLD: begin
	      if (d_in[0] == Bit_start) begin
		  if (d_in[8:1] == old) begin
		      old = d_in[8:1];
		      d_in = 0;
		      Y = Bit_idle;
		      stato = LOAD_OLD;
		  end else begin
		      old = d_in[8:1];
		      Y = Bit_start;
		      d_out = d_in[8:1];
		      d_in = {Bit_start, 8'b0};
		      stato = EXECUTE;
		  end
	      end else begin
		  d_in = {X, d_in[8:1]};
		  Y = Bit_idle;   
		  stato = LOAD_OLD;
	      end
	  end
	endcase
    end

endmodule // b09
