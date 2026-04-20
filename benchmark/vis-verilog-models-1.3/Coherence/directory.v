module DIRECTORY(
   clk,
   write_back_req1, inval1,                   // output: requests from  directory
   write_back_req2, inval2,                  // output: requests from  directory
   blocknum,         // output: requests from  directory
   blk_ok1, blk_data,                        // output: answers from  directory
   blk_ok2,                        // output: answers from  directory
   back_data1, cache_req1, blk_add1,           // input: to directory
   back_data2, cache_req2, blk_add2);           // input: to directory
  

 input clk;
 output [`address_size:0] blocknum;          // output: requests from directory
 output  write_back_req1, inval1;              // output: requests from directory
 output blk_ok1;                    // output: answers from directory
 output blk_data;
 input  back_data1;                           // input: to directory
 output  write_back_req2, inval2;              // output: requests from directory
 output blk_ok2;                    // output: answers from directory
 input  back_data2;                           // input: to directory
 input  [`address_size:0] blk_add1;           // input: to directory
 input  [`address_size:0] blk_add2;           // input: to directory
 input cache_req1;               // input to directory 
 input cache_req2;               // input to directory 

 reg  main_mem   [`mem_size:0];         
 reg  cache_Rlist1 [`mem_size:0];
 reg  cache_Rlist2 [`mem_size:0];

 reg  cache_Wlist1 [`mem_size:0];
 reg  cache_Wlist2 [`mem_size:0];

 wire  blk_data;         
 wire  write_back_req1;
 wire  write_back_req2;

 wire blk_ok1;
 wire blk_ok2;

 wire inval1;
 wire inval2;

 wire   [`address_size:0] blocknum;         
 
 Cache_reqstatus wire cache_req1;          
 Cache_reqstatus wire cache_req2;          


 Arbiter_status reg arbiter_state;

   initial begin
    arbiter_state = ONE;
// initialize all memory
   for (i=0; i<=`mem_size; i =i+1) 
     begin         
      main_mem[i] =0;
      cache_Rlist1[i]=0;
      cache_Rlist2[i]=0;
      cache_Wlist1[i]=0;
      cache_Wlist2[i]=0;
    end
   end

 assign inval1 = ((arbiter_state==TWO)&&(cache_req2==blk_excl))?1:0;
 assign inval2 = ((arbiter_state==ONE)&&(cache_req1==blk_excl))?1:0;
 assign write_back_req1 = ((arbiter_state==TWOWAIT)&&(cache_req1 != ok))?1:0;
 assign write_back_req2 = ((arbiter_state==ONEWAIT)&&(cache_req2 != ok))?1:0;
 assign blk_data = (arbiter_state==ONESERVE) ? main_mem[blk_add1]:
                      (arbiter_state==TWOSERVE) ? main_mem[blk_add2]: 0;
 assign blk_ok1 = (arbiter_state==ONESERVE) ? 1:0;
 assign blk_ok2 = (arbiter_state==TWOSERVE) ? 1:0;
 assign blocknum = ((arbiter_state==ONE)&&(cache_req1==blk_excl))?
                      blk_add1:
                      ((arbiter_state==TWO)&&(cache_req2==blk_excl))?
                      blk_add2:0;

 always @(posedge clk) begin

 // Ok now I put in the arbiter
 // a one clock cycle delay
 // asumption a cc will not put down its request until serviced

case ( arbiter_state )
   ONE: begin 
	if (cache_req1 == blk_rreq)
	begin 
	  if (cache_Wlist2[blk_add1]==1)
		// one requests a read and someone has that address in wx
	     begin 
	  cache_Rlist1[blk_add1] = 1;
		// The processor that returned the data is no longer in write X
	  cache_Wlist2[blk_add1] = 0;
	  cache_Rlist2[blk_add1] = 1;
	       arbiter_state = ONEWAIT;
	     end
          else 
             begin
		//one request a read and non-one has the address  in wx 
		// any block in write access overwritten in cache1
		// remove all address from Wlist1
   	  for (i=0; i<=`mem_size; i =i+1) 
     		begin         
      		cache_Wlist1[i]=0;
    		end
               cache_Rlist1[blk_add1] = 1;
               arbiter_state = ONESERVE;
             end
        end 
		// one requests a write
	else if (cache_req1 == blk_excl)
	begin
   	  cache_Wlist1[blk_add1] = 1;
   	  cache_Rlist1[blk_add1] = 0;
   	  cache_Rlist2[blk_add1] = 0;
   	  cache_Wlist2[blk_add1] = 0;
	  arbiter_state = ONESERVE;
	end
        else 
         begin 
	  arbiter_state = TWO;
         end
        end

		//  servicing a request from cache 1

   ONESERVE: 	  arbiter_state = TWO;

		//  waiting for write_back from cache 2

 
    ONEWAIT: begin
	if (cache_req2 == ok)
		// if the processor has just returned data which it was asked for 
		// update memory if write ex clcache
       	begin
	  main_mem[blk_add1] = back_data2;
	  arbiter_state = ONESERVE;
	end
	end
//
// Symmetric statements for TWO 
// 
	TWO: begin 
	if (cache_req2 == blk_rreq)
	begin 
	  if (cache_Wlist1[blk_add2]==1)
// TWO requests a read and someone has that address in wx
	     begin 
	  cache_Rlist2[blk_add2] = 1;
// The processor that returned the data is no longer in write X
	  cache_Wlist1[blk_add2] = 0;
	  cache_Rlist1[blk_add2] = 1;
	       arbiter_state = TWOWAIT;
	     end
          else 
             begin
//TWO request a read and non-one has the address  in wx 
// any block in write access overwritten in cache1
// remove all address from Wlist2
   		for (i=0; i<=`mem_size; i =i+1) 
     		begin         
      		cache_Wlist2[i]=0;
    		end
               cache_Rlist2[blk_add2] = 1;
               arbiter_state = TWOSERVE;
             end
        end 
// two  requests a write
	else if (cache_req2 == blk_excl)
	begin
 //       blocknum = blk_add2;
   	  cache_Wlist1[blk_add2] = 0;
   	  cache_Rlist1[blk_add2] = 0;
   	  cache_Rlist2[blk_add2] = 0;
   	  cache_Wlist2[blk_add2] = 1;
	  arbiter_state = TWOSERVE;
	end
        else 
         begin 
	  arbiter_state = ONE;
         end
        end

//  servicing a request from cache 2

   TWOSERVE: 	  arbiter_state = ONE;

// if waiting for write_back 

 
    TWOWAIT: begin
// if the processor has just returned data which it was asked for 
// update memory if write ex clcache
	if (cache_req1 == ok)
       	begin
	  main_mem[blk_add2] = back_data1;
	  arbiter_state = TWOSERVE;
	end
	end
        default:;
    endcase;
 end
endmodule




