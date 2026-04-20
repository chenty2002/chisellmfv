module PROC (
  clk,
  read_req, write_req, data, address,      // output: requests to cache 
  acknowledge,                             // input: answer from cache
  any_address, any_value, inst      // input: for non determinism
  );

input clk;
output read_req, write_req, data;          // output: requests to cache
output [`address_size:0] address;          // output: request to cache 
input acknowledge;                         // input: answer from cache
input [`address_size:0] any_address;  
input any_value;
input inst;

wire read_req, write_req, data;
wire [`address_size:0] address;  
Instruction_type wire inst;
Processor_state reg proc_state;
      // local data of the processor

initial begin
  proc_state = IDLE;
end

assign read_req = ((proc_state==IDLE)?((inst==READ_WORD)?1:0):((proc_state == READING)?1:0));
assign write_req = ((proc_state==IDLE)?((inst==WRITE_WORD)?1:0):((proc_state == WRITING)?1:0));
assign data = any_value;
assign address = any_address;

always @(posedge clk) begin
   case ( proc_state )

      IDLE : 
        begin
         case (inst)

            COMPUTE: begin
               proc_state = IDLE;
            end

            READ_WORD: begin
               proc_state = READING;
            end

            WRITE_WORD: begin
               proc_state = WRITING;
            end

         default: begin
               proc_state = IDLE;
            end
         
         endcase;
        end
      READING : 
         if (acknowledge)       // data arrived from cache
         begin
               proc_state = IDLE;
         end


      WRITING : 
         if (acknowledge)       // data arrived from cache
         begin
               proc_state = IDLE;
         end


   endcase;
end // end of always statement describing processor automaton
endmodule


