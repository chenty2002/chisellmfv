module SimpleFeistel(Clk, Reset_n, Din, Dout, Encrypt, Decrypt, Loadkey);
    input         Clk;		// system signals
    input 	  Reset_n;
    input [63:0]  Din; 		// data and key appear here
    output [63:0] Dout;		// output here
    input 	  Encrypt;	// control signals
    input 	  Decrypt;
    input 	  Loadkey;

    // These are Major states of the circuit
    parameter 	  IDLE=0;
    parameter 	  BUSY_KEY=1;
    parameter 	  BUSY_ENC=2;
    parameter 	  BUSY_DEC=3;

    // internal state registers
    reg [1:0] 	  State;
    reg 	  Phase;
    reg [1:0] 	  Round;

    reg [31:0] 	  Left, Right, Temp;	// working registers
    reg [63:0] 	  Dout; 		// stores output

    reg [31:0] 	  Key [0:3];

    integer 	  i;

    wire [31:0]   Fval;
    assign Fval = Key[ Round ] + Left;

    initial begin
	Dout = 0;
	State = IDLE;
	Phase = 0;
	Round = 0;
	Temp = 0;
	Left = 0;
	Right = 0;
	for (i=0; i<4; i=i+1) begin Key[i] = 0; end
    end

    always @ (posedge Clk) begin

	if (!Reset_n) begin: resetting
	    Dout = 0;
	    State = IDLE;
	    Phase = 0;
	    Round = 0;
	    Temp = 0;
	    Left = 0;
	    Right = 0;
	    for (i=0; i<4; i=i+1) begin Key[i] = 0; end
	end // resetting

	else begin
	    case (State)
	      IDLE: begin
		  case ( {Encrypt, Decrypt, Loadkey} )
		    3'b 100: begin: start_encr
			State = BUSY_ENC;
			Left = Din[63:32];
			Right = Din[31:0];
			Round = 0;
			Phase = 0;
		    end
		    3'b 010: begin: start_decr
			State = BUSY_DEC;
			Left = Din[63:32];
			Right = Din[31:0];
			Round = 3;
			Phase = 0;
		    end
		    3'b 001: begin: start_loading_key
			State = BUSY_KEY;
			Key[0] = Din[63:32];	// save first half of key
			Key[1] = Din[31:0];
		    end
		  endcase // start command

	      end // IDLE

	      BUSY_KEY: begin
		  State = IDLE;
		  Key[2] = Din[63:32];		// save rest of key
		  Key[3] = Din[31:0];
	      end

	      BUSY_ENC: begin
		  case (Phase)
		    0: begin 
			Temp = Left;
			Left = Fval ^ Right;
		    end
		    1: begin
			if (Round == 3) begin
			    Dout[63:32] = Temp;
			    Dout[31:0] = Left;
			    State = IDLE;
			end
			else begin
			    Round = Round +1;
			    Right = Temp;
			end
		    end
		  endcase // Phase
		  Phase = ~Phase;
	      end

	      BUSY_DEC: begin
		  case (Phase)
		    0: begin
			Temp = Left;
			Left = Fval ^ Right;
		    end
		    1: begin
			if (Round == 0) begin
			    Dout[63:32] = Temp;
			    Dout[31:0] = Left;
			    State = IDLE;
			end
			else begin
			    Round = Round - 1;
			    Right = Temp;
			end
		    end

		  endcase // Phase
		  Phase = ~Phase;
	      end

	    endcase // State

	end // else
    end // always

endmodule // SimpleFeistel

