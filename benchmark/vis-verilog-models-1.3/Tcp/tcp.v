/* Our TCP model contains 3 modules, 
 *     1) TCP_SND to model the transmitting user process and its TCP interface
 *     2) DUAL_NET to model the network
 *     3) TCP_RCV to model the receiving user process and its TCP interface
 * 
 * This model represents an established TCP connection through which a
 * data SEQUENCE is transmitted as SEGMENTs using a windowing scheme.
 * The receiver promises to hold a window size of data and notifies
 * the sender of available window size with ACKs.  In order to handle
 * out-of-order transmission and to avoid a round-trip needed for each
 * window sent, the receiver need not ACK on each receipt (but may ACK
 * for instance when the available buffer size changes due to the user
 * receiving data.)  
*/

// Top Level TCP Connection Model
//
module tcptest(clk);
input clk;

// signals labeled "_b" are delayed or buffered by the network

wire clk, data_snd, buf_full, data_rcv, buf_empty, rcv_ack, seg_val,
     rcv_ack_b, seg_val_b;
wire [0:3] rcv_seq, rcv_buff, seg_seq, seg_len, rcv_seq_b, 
           rcv_buff_b, seg_seq_b, seg_len_b;

tcp_snd tcp_snd(clk, rcv_ack_b, rcv_seq_b, rcv_buff_b, seg_val, seg_seq, 
	        seg_len);
// The network sends data and acks bidirectionally
//
dual_net dual_net(clk, seg_val, seg_seq, seg_len, seg_val_b, seg_seq_b, 
		  seg_len_b, rcv_ack, rcv_seq, rcv_buff, rcv_ack_b, rcv_seq_b, 
	          rcv_buff_b);
tcp_rcv tcp_rcv(clk, rcv_ack, rcv_seq, rcv_buff, seg_val_b, seg_seq_b, 
	        seg_len_b);
endmodule

`include snd.v
`include rcv.v

// TCP Network model 
//  -drops packets in both directions
//  -out of order delivery
//
module dual_net(clk, F_val, F_data1, F_data2, F_val_b, F_data1_b, F_data2_b,
	        R_val, R_data1, R_data2, R_val_b, R_data1_b, R_data2_b);
input clk, F_val, R_val;
input [0:3] F_data1, F_data2, R_data1, R_data2;
output F_val_b, R_val_b;
output [0:3] F_data1_b, F_data2_b, R_data1_b, R_data2_b;

network forward(clk, F_val, F_data1, F_data2, F_val_b, F_data1_b, F_data2_b);
network reverse(clk, R_val, R_data1, R_data2, R_val_b, R_data1_b, R_data2_b);
endmodule

`include network.v
