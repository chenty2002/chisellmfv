// TCP Receive Model
//   - contains user process which reads buffer
//   - acknowledges sender with updated available window size
//
module tcp_rcv(clk, rcv_ack, rcv_seq, rcv_buff, seg_val, seg_seq, seg_len);
input clk, seg_val;
output rcv_ack;
input [0:3] seg_seq, seg_len;
output [0:3] rcv_seq, rcv_buff;

wire clk, seg_val, rcv_ack, buf_empty, data_rcv;
wire [0:3] seg_seq, seg_len, rcv_seq, rcv_buff;

// User Process
rcv_user rcv_user(clk, buf_empty, data_rcv);
// TCP Receive Process
receiver receiver(clk, buf_empty, data_rcv, rcv_ack, rcv_seq, rcv_buff, 
                   seg_val, seg_seq, seg_len);
endmodule

// User Process
//    - reads buffer one slot at a time non-deterministically
//
typedef enum {IDLE, READ} user_rcv_status;

module rcv_user(clk, buf_empty, data_rcv); 
input clk, buf_empty;
output data_rcv;

wire buf_empty, clk, data_rcv;
user_rcv_status reg state;
user_rcv_status wire r_state;

initial state = IDLE;

assign data_rcv =  ((state == IDLE) ? 0 : 1);
assign r_state = $ND(IDLE,READ);

always @(posedge clk) begin
    case(state)
         IDLE:
            begin
            if (buf_empty == 0) begin
		state=r_state;
                end
             else if (buf_empty != 0) begin
                state = IDLE;
                end 
	    end
          READ:
            begin
            if (buf_empty == 0) begin
	      state = r_state;
              end
           else if (buf_empty != 0) begin
              state = IDLE;
              end
	    end
             default:;
        endcase;
end
endmodule

// TCP Receive Module

typedef enum { ACK_BUSY, ACK_IDLE } ack_state_t;

module receiver(clk, buf_empty, data_rcv, rcv_ack, rcv_seq, rcv_buff, 
                seg_val, seg_seq, seg_len);
input clk, data_rcv, seg_val;
output buf_empty, rcv_ack;
output [0:3] rcv_seq, rcv_buff;
input [0:3] seg_seq, seg_len;

wire buf_empty, rcv_ack, seg_val, try_rcv, try_ack;
wire[0:3] rcv_seq, rcv_buff, seg_seq, seg_len, rcv_len;

reg[0:3] rcv_nxt, rcv_wnd;
ack_state_t reg ack_state;

initial
	begin
	rcv_nxt = $ND(0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15);
	rcv_wnd = $ND(0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15);
	ack_state = $ND(ACK_BUSY, ACK_IDLE);
	end

assign buf_empty = (rcv_wnd == 0);


assign try_rcv = $NDset(@(posedge clk), 1, 0);
assign try_ack = $NDset(@(posedge clk), 1, 0);

// Receive Length Formula:
assign rcv_len = (((rcv_nxt + rcv_wnd) < (seg_seq + seg_len)) ? 
		  rcv_wnd : seg_len);

// Receive segment and update window
//   - very conservative receiver: only allows contiguous data
//     which fits in its window (can underlap)
always @(posedge clk) begin
    if (data_rcv && (! buf_empty) && (rcv_wnd < 8))
        rcv_wnd = rcv_wnd + 1;
    if (try_rcv && seg_val && (rcv_len > 0))
	rcv_wnd = rcv_wnd - rcv_len;
end
always @(posedge clk) begin
    if (try_rcv && seg_val && (rcv_len > 0)) begin
        if ((seg_seq <= rcv_nxt) &&
	    ((rcv_nxt <= ((seg_seq + seg_len) & 7)) &&
	     ((seg_seq + seg_len) <= (rcv_nxt + rcv_wnd))))
            rcv_nxt = (rcv_nxt + rcv_len) & 7;
//        rcv_wnd = rcv_wnd - rcv_len;
    end;
end

// Acknowledge the sender
//
wire [0:3] r_rcv_seq, r_rcv_buff;
assign r_rcv_seq = $ND(0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15);
assign r_rcv_buff = $ND(0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15);
assign rcv_ack = ack_state==ACK_BUSY?0:1;
assign rcv_seq = ack_state==ACK_IDLE?rcv_nxt:r_rcv_seq;
assign rcv_buff = ack_state==ACK_IDLE?rcv_wnd:r_rcv_buff;

always @(posedge clk) begin
    case (ack_state)
        ACK_BUSY: begin
//            rcv_ack = 0;
            ack_state = ACK_IDLE;
        end
        ACK_IDLE: begin
	    if (try_ack) begin
//		rcv_ack = 1;
	        ack_state = ACK_BUSY;
//		rcv_seq = rcv_nxt;
//		rcv_buff = rcv_wnd;
	    end
	end
    endcase
end
endmodule
