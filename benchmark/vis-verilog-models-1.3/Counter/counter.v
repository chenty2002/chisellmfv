/* translation of counter.smv to verilog

   Sriram Krishnan 7/93. 
   


*/ 



module counter(clk);
input clk;

wire out0, out1, out2; 

counter_cell bit0 (clk, 1, out0);
counter_cell bit1 (clk, out0, out1);
counter_cell bit2 (clk, out1, out2); 

endmodule

module counter_cell(clk, carry_in, carry_out); 
input clk; 
input carry_in; 
output carry_out; 
reg value; 

assign carry_out = value & carry_in;

initial value = 0;

always @(posedge clk) begin
// value = (value + carry_in) % 2;  
	case(value)          
		0: value = carry_in; 
		1: if (carry_in ==0) 
			value = 1;
		else value = 0;
	endcase 
end 
endmodule

