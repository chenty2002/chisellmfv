// TCP Sender model
//   - contains user process which fills buffer
//   - windows the data sent according to current receiver buffer size
//
module tcp_snd(clk, rcv_ack, rcv_seq, rcv_buff, seg_val, seg_seq, seg_len);
input clk, rcv_ack;
output seg_val;
input [0:3] rcv_seq, rcv_buff;
output [0:3] seg_seq, seg_len;

wire clk, rcv_ack, seg_val, buf_full, data_snd;
wire [0:3] rcv_seq, rcv_buff, seg_seq, seg_len;

// User Process
snd_user snd_user(clk, buf_full, data_snd);
// TCP Send Process
sender sender(clk, buf_full, data_snd, rcv_ack, rcv_seq, rcv_buff,
               seg_val, seg_seq, seg_len);
endmodule


// User Process
//  - fills buffer one slot at a time non-determistically
//
typedef enum {IDLE, SEND} user_snd_status;

module snd_user(clk, buf_full, data_snd); 
input clk, buf_full;
output data_snd;

wire buf_full, clk, data_snd;
user_snd_status reg state;
user_snd_status wire r_state;

initial state = IDLE;

assign data_snd = ((state == IDLE) ? 0 : 1);
assign r_state = $ND(IDLE,SEND);

always @(posedge clk) begin
    case(state)
        IDLE: begin
	    state = r_state;
	end
	SEND: begin
	    if (buf_full == 0) begin
	    state = r_state;
	    end else if (buf_full != 0) begin
	        state = IDLE;
	    end
        end
    endcase;
end
endmodule

// TCP Send Module

`define MAX_SND 15

typedef enum { SND_BUSY, SND_IDLE } send_state_t;

module sender(clk, buf_full, data_snd, rcv_ack, rcv_seq, rcv_buff, 
              seg_val, seg_seq, seg_len);
input clk, data_snd, rcv_ack;
output buf_full, seg_val;
input [0:3] rcv_seq, rcv_buff;
output [0:3] seg_seq;
output [0:3] seg_len;

wire buf_full, rcv_ack, seg_val, try_snd, try_rcv,r_seg_val;
wire[0:3] rcv_seq, rcv_buff, seg_seq, seg_len, r_seg_seq;
reg[0:3] snd_una, snd_nxt, snd_wnd, rcv_wnd;
wire [0:3] map_nxt;
send_state_t reg send_state;

initial
	begin
	rcv_wnd = 7;
	snd_una = $ND(0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15);
	snd_nxt = $ND(0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15);
	snd_wnd = $ND(0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15);
	send_state = $ND(SND_BUSY, SND_IDLE);
	end

assign buf_full = (snd_wnd == 7);

always @(posedge clk) begin
    if (data_snd && (! buf_full)) 
	snd_wnd = snd_wnd + 1;
end

assign map_nxt = (snd_nxt < snd_una) ? (snd_nxt + 8) : snd_nxt;
assign seg_len = (rcv_wnd < ((snd_una + rcv_wnd) - map_nxt)) ?
		 rcv_wnd : ((snd_una + rcv_wnd) - map_nxt);

assign r_seg_val = $ND(0,1);
assign r_seg_seq = $ND(0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15);
assign try_snd = $NDset(@(posedge clk), 1, 0);
assign try_rcv = $NDset(@(posedge clk), 1, 0);
assign seg_val = send_state == SND_BUSY?0:((try_snd && (seg_len > 0))?1:r_seg_val);
assign seg_seq = send_state == SND_BUSY?r_seg_seq:((try_snd && (seg_len > 0))?snd_nxt:r_seg_seq);

// Send a segment to receiver (hold seg_val high for one cycle)
// 
always @(posedge clk) begin
    case (send_state)
	SND_BUSY: begin
//	    seg_val <= 0;
	    send_state = SND_IDLE;
        end
	SND_IDLE: begin
	    if (try_snd && (seg_len > 0)) begin
//		seg_val <= 1;
//		seg_seq <= snd_nxt;
		snd_nxt = (snd_nxt + seg_len) & 7; 
		send_state = SND_BUSY;
	    end
	end
    endcase
end

// Process any acknowledgements
//
always @(posedge clk) begin
    if (try_rcv && rcv_ack) begin
        if (rcv_seq > snd_una)
            snd_una = rcv_seq;
        rcv_wnd = rcv_buff;
    end;
end
endmodule
