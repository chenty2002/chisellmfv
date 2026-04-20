typedef enum {IDLE} nw_rcv_status;
typedef enum {IDLE, WRITE} nw_snd_status;

module network(clk, val, data1, data2, val_b, data1_b, data2_b);
input clk, val;
input [0:3] data1, data2;
output val_b;
output [0:3] data1_b, data2_b;

reg [0:7] val_A;
reg [0:7] data1_A;
reg [0:7] data2_A;

wire [0:2] in_indx, out_indx;
wire snd_data, rcv_data;
wire r_val_b,r_data1_b,r_data2_b;
nw_snd_status reg snd_state;
nw_rcv_status reg rcv_state;

initial
	begin
	val_A[0] = $ND(0,1);
	val_A[1] = $ND(0,1);
	val_A[2] = $ND(0,1);
	val_A[3] = $ND(0,1);
	val_A[4] = $ND(0,1);
	val_A[5] = $ND(0,1);
	val_A[6] = $ND(0,1);
	val_A[7] = $ND(0,1);
	data1_A[0] = $ND(0,1);
	data1_A[1] = $ND(0,1);
	data1_A[2] = $ND(0,1);
	data1_A[3] = $ND(0,1);
	data1_A[4] = $ND(0,1);
	data1_A[5] = $ND(0,1);
	data1_A[6] = $ND(0,1);
	data1_A[7] = $ND(0,1);
	data2_A[0] = $ND(0,1);
	data2_A[1] = $ND(0,1);
	data2_A[2] = $ND(0,1);
	data2_A[3] = $ND(0,1);
	data2_A[4] = $ND(0,1);
	data2_A[5] = $ND(0,1);
	data2_A[6] = $ND(0,1);
	data2_A[7] = $ND(0,1);
	snd_state = $ND(IDLE, WRITE);
	rcv_state = IDLE;
end

assign in_indx = $NDset(@(posedge clk), 0,1,2,3,4,5,6,7);
assign out_indx = $NDset(@(posedge clk), 0,1,2,3,4,5,6,7);

assign snd_data = $NDset(@(posedge clk), 0,1);
assign rcv_data = $NDset(@(posedge clk), 0,1);
assign r_val_b = $ND(0,1);
assign r_data1_b = $ND(0,1);
assign r_data2_b = $ND(0,1);
assign val_b = ((snd_state == IDLE)&&(snd_data!=0))?0:r_val_b;
assign data1_b = ((snd_state == IDLE)&&(snd_data!=0))?0:r_data1_b;
assign data2_b = ((snd_state == IDLE)&&(snd_data!=0))?0:r_data2_b;
// The reader process in the network

always @(posedge clk) begin
   case (snd_state)
      IDLE:
        begin
	    if (snd_data == 0) begin
	       snd_state = IDLE ;
	       end 
	    else if ( snd_data != 0) begin
		 snd_state = WRITE;
// Bogus assignments
//		 val_b =  0;
//		 data1_b = 0;
//		 data2_b  = 0;
	    end
        end
      WRITE:
        begin
            snd_state = IDLE;
	      val_A = 0;
        end
   endcase
end

// The writer process in the network

always @(posedge clk) begin
   case (rcv_state)
      IDLE:
        begin
	    if (rcv_data == 0) begin
	        rcv_state = IDLE ;
	    end else if ( rcv_data != 0) begin
		if (val == 0) begin
		    rcv_state = IDLE;
		end else if ( val == 0) begin
		    rcv_state = IDLE;
// Bogus assignments
		    data1_A = 0;
		    data2_A = 0;
		end
	    end
        end
   endcase
end

endmodule
