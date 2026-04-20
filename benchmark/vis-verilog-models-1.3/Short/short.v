/* translation of short.smv to verilog

   Sriram Krishnan 9/93.



*/



typedef enum { ready, busy } status;
module short(clk, request);

input clk;
output request;
status reg state;
status wire nond_state;
assign nond_state = $ND(ready,busy);
assign request = $ND(0,1);

initial state = ready;

always @(posedge clk) begin
        case(state)
                ready: if (request == 1)
                        state = busy;
                	else begin
                           state = nond_state;
                     	end
		busy:
			begin
                           state = nond_state;
                     	end
		
        endcase
end
endmodule

