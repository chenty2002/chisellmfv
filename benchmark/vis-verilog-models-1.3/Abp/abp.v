typedef enum {S_INIT0, S_SEND0, S_WAIT0, S_INIT1, S_SEND1, S_WAIT1} sender_status;
typedef enum {R_INIT0, R_WAIT0, R_ACK0, R_INIT1, R_WAIT1, R_ACK1} receiver_status;
typedef enum {DATA00, DATA01, DATA10, DATA11, DERR} data_status;
typedef enum {AM0, AM1, AERR} ack_status;
typedef enum {ZERO, ONE, X} bool_status;

module abp(clk);
input	clk;

data_status wire message;
ack_status wire ack;
wire o1, o2;
wire sndmsg, rcvmsg;

sender	s (clk,message,ack,o1,sndmsg);
receiver	r (clk,message,ack,o2,rcvmsg);
arbiter a (clk,o1,o2);

endmodule

module sender(clk,message,ack,active,sndmsg);
input	clk, ack, active;
output	message, sndmsg;

sender_status reg	state;
data_status reg message;
reg	exit1;
bool_status reg	smsg;

ack_status wire ack;
wire	sndmsg, r_smsg;
data_status wire r11_message, r10_message, r01_message, r00_message;

initial
	begin
	state = S_INIT0;
	message = DATA11;
	smsg = X;
	exit1 = $ND(0,1);
	end

assign sndmsg = (state==S_INIT0 || state==S_INIT1) ? 1: 0;
assign r_smsg = $ND(0,1);
assign r10_message = $ND(DATA10,DERR);
assign r00_message = $ND(DATA00,DERR);
assign r11_message = $ND(DATA11,DERR);
assign r01_message = $ND(DATA01,DERR);

always @(posedge clk) begin
	if ( active ) begin
	case(state)
		S_INIT0:
			begin
			exit1 = 0;
			case(r_smsg)
			1:smsg = ONE;
			0:smsg = ZERO;
			endcase
			state = S_SEND0;
			end

		S_SEND0:
			begin
			case(smsg)
				ONE: message = r10_message;
				ZERO: message = r00_message;
				X: message = DERR;
			endcase
			state = S_WAIT0;
			end

		S_WAIT0:
			begin
			if ( !exit1 ) begin
				case(ack)
					AM0: exit1 = 1;
					AM1: case(smsg)
                                	        ONE: message = r10_message;
                                	   	ZERO: message = r00_message;
                                	   	X: message = DERR;
                                          	endcase
					AERR: case(smsg)
						ONE: message = r10_message;
						ZERO: message = r00_message;
						X: message = DERR;
						endcase
				endcase
			end
			else
				begin
				state = S_INIT1;
				smsg = X;
				end
			end

		S_INIT1:
			begin
			exit1 = 0;
			case(r_smsg)
			1:smsg = ONE;
			0:smsg = ZERO;
			endcase
			state = S_SEND1;
			end

		S_SEND1:
			begin
			case(smsg)
				ONE: message = r11_message;
				ZERO: message = r01_message;
				X: message = DERR;
			endcase
			state = S_WAIT1;
			end

		S_WAIT1:
			begin
			if ( !exit1 ) begin
				case(ack)
					AM1: exit1 = 1;
					AM0: case(smsg)
						ONE: message = r11_message;
						ZERO: message = r01_message;
                                                X: message = DERR;
						endcase
					AERR: case(smsg)
						ONE: message = r11_message;
						ZERO: message = r01_message;
                                                X: message = DERR;
						endcase
				endcase
			end
			else
				begin
				state = S_INIT0;
				smsg = X;
				end
			end
		
	endcase
end
end
endmodule

module receiver (clk,message,ack,active,rcvmsg);
input	clk, active, message;
output	ack,rcvmsg;

ack_status reg ack;
receiver_status reg	state;
reg	exit2;
bool_status reg	rmsg;

wire	rcvmsg, r_ack;
data_status	wire message;

initial 
	begin
	state = R_INIT0;
	ack = AERR;
	rmsg = X;
	exit2 = $ND(0,1);
	end

assign rcvmsg = (state==R_ACK0 || state==R_ACK1) ? 1: 0;
assign r_ack = $ND(0,1);

always @(posedge clk) begin
	if ( active ) begin
	case( state )
	R_INIT0:
		begin
		exit2 = 0;
		state = R_WAIT0;
		end
	R_WAIT0:
		begin
		if ( !exit2 ) begin
			case(message)
				DATA10:
					begin
					exit2 = 1;
					rmsg = ONE;
					end
				DATA00:
					begin
					exit2 = 1;
					rmsg = ZERO;
					end
				default:
					begin
					case(r_ack)
						1:ack = AM1;
						0:ack = AERR;
					endcase
					end
			endcase
		end
		else
			state = R_ACK0;
		end
	R_ACK0:
		begin
		case(r_ack)
			1:ack = AM0;
			0:ack = AERR;
		endcase
		state = R_INIT1;
		end

	R_INIT1:
		begin
		exit2 = 0;
		state = R_WAIT1;
		end
	R_WAIT1:
		begin
		if ( !exit2 ) begin
			case(message)
				DATA11:
					begin
					exit2 = 1;
					rmsg = ONE;
					end
				DATA01:
					begin
					exit2 = 1;
					rmsg = ZERO;
					end
				default:
					begin
					case(r_ack)
						1:ack = AM0;
						0:ack = AERR;
					endcase
					end
			endcase
		end
		else
			state = R_ACK1;
		end
	R_ACK1:
		begin
		case(r_ack)
			1:ack = AM1;
			0:ack = AERR;
		endcase
		state = R_INIT0;
		end

	endcase
end
end
endmodule


module arbiter(clk,o1,o2);
input clk;
output o1, o2;
reg o1, o2;
wire s;

initial begin
	o1 = 0;
	o2 = 0;
end

assign s = $ND(0,1);

always @(posedge clk) begin
	case (s)
	0: begin o1 = 1; o2 = 0; end
	1: begin o1 = 0; o2 = 1; end
	endcase
end
endmodule

