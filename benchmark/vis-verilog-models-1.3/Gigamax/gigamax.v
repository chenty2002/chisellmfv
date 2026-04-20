/*
 * gigamax.v
 *
 * author: Szu-Tsung Cheng (stcheng@ic.berkeley.edu)
 * date: 7/26/93
 *
 *     A verilog program using symbolic extension of vl2mv for
 *     gigamax multiprocessor distributed, shared memory architecture
 *
 *     from the example of gigamax.smv example in SMV system by 
 *     K. L. McMillan, et. al.
 */

typedef enum { idle, read_shared, read_owned, write_invalid, write_shared, 
               write_resp_invalid, write_resp_shared, invalidate, response } 
        command;
typedef enum { invalid, shared, owned } status;
typedef enum {r0,r1,r2,r3,r4,r5,r6,r7,r8} nine;


module main (clk);

input clk;

command wire CMD;
wire REPLY_OWNED, REPLY_WAITING, REPLY_STALL;
command wire p0_cmd, p1_cmd, p2_cmd, m_cmd;
wire p0_master, p0_reply_owned, p0_reply_waiting, p0_reply_stall,
     p1_master, p1_reply_owned, p1_reply_waiting, p1_reply_stall,
     p2_master, p2_reply_owned, p2_reply_waiting, p2_reply_stall,
     m_master, m_reply_owned, m_reply_waiting, m_reply_stall;
command wire nond_CMD;
wire nond0_master;
wire nond1_master;
wire nond2_master;
wire nondm_master;

processor p0(clk, CMD, p0_master, REPLY_OWNED, REPLY_WAITING, REPLY_STALL,
             p0_cmd, p0_reply_owned, p0_reply_waiting, p0_reply_stall);
processor p1(clk, CMD, p1_master, REPLY_OWNED, REPLY_WAITING, REPLY_STALL,
             p1_cmd, p1_reply_owned, p1_reply_waiting, p1_reply_stall);
processor p2(clk, CMD, p2_master, REPLY_OWNED, REPLY_WAITING, REPLY_STALL,
             p2_cmd, p2_reply_owned, p2_reply_waiting, p2_reply_stall);
memory m(clk, CMD, m_master, REPLY_OWNED, REPLY_WAITING, REPLY_STALL,
         m_cmd, m_reply_owned, m_reply_waiting, m_reply_stall);

assign REPLY_OWNED = p0_reply_owned | p1_reply_owned | p2_reply_owned,
       REPLY_WAITING = p0_reply_waiting | p1_reply_waiting | p2_reply_waiting,
       REPLY_STALL = p0_reply_stall | p1_reply_stall | p2_reply_stall |
                     m_reply_stall;

assign CMD = (p1_cmd==idle && p2_cmd==idle && m_cmd==idle) ? p0_cmd :
             (p0_cmd==idle && p2_cmd==idle && m_cmd==idle) ? p1_cmd :
             (p0_cmd==idle && p1_cmd==idle && m_cmd==idle) ? p2_cmd :
             (p0_cmd==idle && p1_cmd==idle && p2_cmd==idle) ? m_cmd : nond_CMD;

assign p0_master = nond0_master;
assign p1_master = (p0_master) ? 0 : nond1_master;
assign p2_master = (p0_master || p1_master) ? 0 : nond2_master;
assign m_master  = ( (p0_master || p1_master) || p2_master) ? 0 : nondm_master;

assign nond0_master = $ND(0,1);
assign nond1_master = $ND(0,1);
assign nond2_master = $ND(0,1);
assign nondm_master = $ND(0,1);

assign nond_CMD = $ND(idle, read_shared, read_owned, write_invalid, 
                      write_shared, write_resp_invalid, write_resp_shared, invalidate, response);

endmodule



module cache_device (clk, CMD, master, abort, waiting, state,snoop, reply_owned,
                     readable, writable);
input clk;
input CMD;
input master;
input abort;
input waiting;
output state;
output snoop;
output reply_owned;
output readable, writable;

command wire CMD;
wire master;
wire abort;
wire waiting;
status reg state, snoop;
wire readable, writable, reply_owned;

status wire nond_snoop;
status wire nond_state;

assign readable = ((state == shared) || (state == owned)) && !waiting;
assign writable = (state == owned) && (!waiting);
assign reply_owned = (!master) && (state == owned);
assign nond_snoop = $ND(shared,owned);
assign nond_state = $ND(shared,invalid);

initial begin state = invalid; end
initial begin snoop = invalid; end

always @(posedge clk) begin

    if (abort) begin snoop = snoop; end
    else if (!master && state==owned && CMD==read_shared) begin snoop = nond_snoop; end
    else if (master && CMD == write_resp_invalid) begin snoop = invalid; end
    else if (master && CMD == write_resp_shared) begin snoop = invalid; end

    if (abort) begin state = state; end
    else if (master == 1)
	  begin
       case (CMD)
        read_shared: begin state = shared; end
        read_owned: begin  state = owned; end
        write_invalid: begin state = invalid; end
        write_resp_invalid: begin state = invalid; end
        write_shared:begin state = shared; end
        write_resp_shared:begin  state = shared; end
        endcase
	  end
    else if (!master && state == shared && 
             (CMD == read_owned || CMD == invalidate))
        begin
          state = invalid;
		end
    else if (state == shared) 
		begin
		  state = nond_state; 
		end
end

endmodule



module bus_device(clk, CMD, master, REPLY_STALL, REPLY_WAITING, 
                  waiting, reply_waiting, abort);
input clk;
input CMD;
input master;
input REPLY_STALL;
input REPLY_WAITING;
output waiting;
output reply_waiting;
output abort;

command wire CMD;
wire master;
wire REPLY_STALL, REPLY_WAITING;
reg waiting;
wire reply_waiting;
wire abort;

wire reply_waiting, abort;

assign reply_waiting = !master && waiting;
assign abort = REPLY_STALL || 
               ((CMD == read_shared || CMD == read_owned) && REPLY_WAITING);

initial waiting = 0;
always @(posedge clk) begin
    if (abort) begin waiting = waiting; end
    else if (master && CMD == read_shared) begin waiting = 1; end
    else if (master && CMD == read_owned) begin waiting = 1; end
    else if (!master && CMD == response) begin waiting = 0; end
    else if (!master && CMD == write_resp_invalid) begin waiting = 0; end
    else if (!master && CMD == write_resp_shared) begin waiting = 0; end
end

endmodule



module processor(clk, CMD, master, REPLY_OWNED, REPLY_WAITING, REPLY_STALL,
                 cmd, reply_owned, reply_waiting, reply_stall);
input clk;
input CMD;
input master;
input REPLY_OWNED, REPLY_WAITING, REPLY_STALL;
output cmd;
output reply_owned;
output reply_waiting;
output reply_stall;

command wire CMD;
wire master;
wire REPLY_OWNED, REPLY_WAITING, REPLY_STALL;
command wire cmd;
wire reply_owned, reply_waiting, reply_stall;

command wire nond_cmd;
wire abort, waiting;
status wire state, snoop;
wire readable, writable;

bus_device Bdevice(clk, CMD, master, REPLY_STALL, REPLY_WAITING,
                   waiting, reply_waiting, abort);
cache_device Cdevice(clk, CMD, master, abort, waiting, state,snoop, reply_owned, 
                     readable, writable);

assign cmd = (master && state==invalid) ? nond_cmd :
             (master && state==shared) ? read_owned :
             (master && state==owned && snoop==owned) ? write_resp_invalid :
             (master && state==owned && snoop==shared) ? write_resp_shared :
             (master && state==owned && snoop==invalid) ? write_invalid : idle;

assign nond_cmd = $ND(read_shared,read_owned);
assign reply_stall = $ND(0,1);
endmodule



module memory (clk, CMD, master, REPLY_OWNED, REPLY_WAITING, REPLY_STALL,
               cmd, reply_owned, reply_waiting, reply_stall);
input clk;
input CMD;
input master;
input REPLY_OWNED, REPLY_WAITING, REPLY_STALL;
output cmd;
output reply_owned, reply_waiting, reply_stall;

command wire CMD;
wire master;
wire REPLY_OWNED, REPLY_WAITING, REPLY_STALL;
command wire cmd;
wire reply_owned, reply_waiting, reply_stall;

wire abort;
reg busy;
command wire nond_cmd;
wire nond_reply_stall;

assign reply_owned = 0;
assign reply_waiting = 0;
assign abort = REPLY_STALL || 
               (CMD == read_shared || CMD == read_owned) && REPLY_WAITING || 
               (CMD == read_shared || CMD == read_owned) && REPLY_OWNED;

assign cmd = (master && busy) ? nond_cmd : idle;
assign reply_stall = (busy && (CMD == read_shared || CMD == read_owned ||
                      CMD == write_invalid || CMD == write_shared ||
                      CMD == write_resp_invalid || CMD == write_resp_shared)) ?
                          1 : nond_reply_stall;

assign nond_reply_stall = $ND(0,1);
assign nond_cmd = $ND(response,idle);

initial busy = 0;

always @(posedge clk) begin
    if (abort) begin busy = busy; end
    else if (master && CMD == response)begin  busy = 0; end
    else if (!master && CMD == read_owned || CMD == read_shared)begin  busy = 1; end
end

endmodule
