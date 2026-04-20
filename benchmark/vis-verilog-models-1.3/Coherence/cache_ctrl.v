module CACHE_CTRLER (
  clk,
  read_req, write_req, data, address,      // input: requests from processor 
  acknowledge,                             // output: to processor
  write_back_req, inval, blocknum,         // input: requests from directory
  blk_ok, blk_data,                        // input: answers from directory
  back_data, cache_req,blk_add,           // output: to directory
  );    
             
input clk;
input read_req, write_req, data;           // input: requests from processor 
input [`address_size:0] address;           // input: requests from processor 
input [`address_size:0] blocknum;          // input: requests from directory
input  write_back_req, inval;              // input: requests from directory
input blk_ok, blk_data;                    // input: answers from directory
output acknowledge;                        // output: to processor
output back_data;                          // output: to directory
output cache_req;                          // output: to directory
output [`address_size:0] blk_add;          // output: to directory

Cache_reqstatus reg  cache_req;          
wire  back_data;
wire  acknowledge;
reg blk_add;


//  Registers local to the cache controler
Block_status reg block_state;
Cache_status reg cache_state;
reg [`address_size:0] block_add;           // memory address of the block
reg block_val;                             // value of the block

initial begin
  cache_state = Ready;
  block_state = INVALID;
  block_add = 0;
  block_val = 0;
  blk_add = 0;
  cache_req = noop;
end

assign back_data =(cache_req==ok)?block_val:0;
assign acknowledge = ((cache_state == Rgrant)||(cache_state == Wgrant))?1:0;

always @(posedge clk) begin


case ( cache_state )
   Ready: begin            //ready to service a directory request
      if ((inval)&&(block_add ==blocknum))         // block invalidation request
         begin
            block_state = INVALID ;
            cache_req = ok;
            cache_state = Ready;
         end
      else if  (write_back_req)
         begin
            block_state = SHARED ;
            cache_req = ok;
            cache_state = Ready;
         end
      else if  (read_req)
         begin
            if ((block_add != address) || (block_state == INVALID)) 
            begin                         // read miss
               cache_req = blk_rreq;             // ask to read block from memory
               blk_add = address;
               cache_state = Rwait;
               block_state = INVALID;          //invalidates if replacement
            end
            else
             begin                       // read hit
               cache_state = Rgrant;
               cache_req = noop;
             end
          end
      else  if  (write_req)
         begin
            if ((block_add != address) || (block_state != EXCLUSIVE)) 
            begin                        // write miss
               cache_req = blk_excl;             // ask exclusive block from memory
               blk_add = address;
               cache_state = Wwait;
               block_state = INVALID;          //invalidates if replacement
            end
            else
             begin                       // write hit
               cache_state = Wgrant;
               cache_req = noop;
             end
          end
      else 
        begin
         cache_req = noop;
         blk_add = 0;
        end 
      end

      Rgrant: begin               // read acknowledge to processor
         if ((inval)&&(block_add == blocknum)) 
          begin
            block_state = INVALID;
            cache_req = ok;
            cache_state = Ready;
          end             
         else
         begin
         cache_state = Ready;
         end
      end

      Wgrant: begin
         if ((inval)&&(block_add == blocknum)) 
          begin
            block_state = INVALID;
            cache_req = ok;
            cache_state = Ready;
          end             
         else
         begin
         block_val = data;
         cache_state = Ready;
         end 
      end

      Rwait: begin
         if ((inval)&&(block_add == blocknum)) 
          begin
            block_state = INVALID;
            cache_req = ok;
            cache_state = Ready;
          end             
         else if (write_back_req)
          begin
               cache_state = Ready;
               //cache_req = ok;
          end
         else if ( blk_ok )
         begin
            block_val = blk_data;
            block_add = blk_add;
            block_state = SHARED;
            cache_req = noop;
            cache_state = Rgrant;
         end
         else cache_state = Rwait;
      end

      Wwait: begin
         if ((inval)&&(block_add == blocknum)) 
          begin
            block_state = INVALID;
            cache_req = ok;
            cache_state = Ready;
          end             
         else  if (write_back_req)
          begin
               cache_state = Ready;
               //cache_req = ok;               
         end
         else if ( blk_ok )
         begin
            block_val = blk_data;
            block_add = blk_add;
            block_state = EXCLUSIVE;
            cache_req = noop;
            cache_state = Wgrant;
         end
         else cache_state = Wwait;
      end
      
      default: cache_req = noop;
   endcase;
end // end of always statement describing cache controller automaton
endmodule
