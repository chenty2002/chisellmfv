 `define address_size 0       // nb of bits - 1 of address of blocs in memory
 `define mem_size 1        // number of blocks in memory - 1

 typedef enum {INVALID, SHARED, EXCLUSIVE} Block_status;
 typedef enum { Ready, Rwait, Wwait, Rgrant, Wgrant} 
    Cache_status;
 typedef enum { COMPUTE, READ_WORD, WRITE_WORD } Instruction_type;
 typedef enum { IDLE, READING, WRITING } Processor_state;
 typedef enum { ONE, ONEWAIT,TWO, TWOWAIT, ONESERVE, TWOSERVE} Arbiter_status;
// typedef enum { HIT,MISS,NOP} Cache_rwtype;
 typedef enum {ok, blk_rreq, blk_excl, noop} Cache_reqstatus;

// This is the main module
// It has 3 types of sub,modules

module COHERANCE(clk,
  any_address1, any_value1,inst1,  //address, data, instruction for 1
  any_address2, any_value2,inst2);


input [`address_size:0] any_address1;  
input [`address_size:0] any_address2;  
input any_value1;
input any_value2;
input inst1;
input inst2;
input clk;


wire acknowledge1;                        // output: to processor
wire read_req1, write_req1, data1;           // input: requests from processor 
wire [`address_size:0] address1;           // input: requests from processor 
Instruction_type wire inst1;
wire acknowledge2;                        // output: to processor
wire read_req2, write_req2, data2;           // input: requests from processor 
wire [`address_size:0] address2;           // input: requests from processor 
Instruction_type wire inst2;
wire back_data;
 wire  blk_add1;
 wire  blk_add2;
 wire  blk_data;         
 wire write_back_req1;
 wire write_back_req2;
 wire blk_ok1;
 wire blk_ok2;
 wire inval1;
 wire inval2;
 wire inval3;
 wire   [`address_size:0] blocknum;         
 wire wbr1;
 wire wbr2; 
 Cache_reqstatus wire cache_req1;          
 Cache_reqstatus wire cache_req2;          


PROC proc1(clk, read_req1, write_req1, data1, address1, acknowledge1, any_address1, any_value1, inst1);
CACHE_CTRLER cc1(clk,read_req1, write_req1, data1, address1,acknowledge1,write_back_req1, inval1, blocknum,blk_ok1, blk_data,back_data1, cache_req1,blk_add1);

PROC proc2(clk, read_req2, write_req2, data2, address2, acknowledge2, any_address2, any_value2, inst2);
CACHE_CTRLER cc2(clk,read_req2, write_req2, data2, address2,acknowledge2,write_back_req2, inval2, blocknum,blk_ok2, blk_data,back_data2, cache_req2,blk_add2);

DIRECTORY direc(clk,write_back_req1, inval1,write_back_req2, inval2,blocknum, blk_ok1, blk_data,blk_ok2, back_data1, cache_req1, blk_add1,back_data2, cache_req2, blk_add2);

endmodule
`include "cache_ctrl.v"
`include "processor.v"
`include "directory.v"
