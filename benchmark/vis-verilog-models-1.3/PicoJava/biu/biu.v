/****************************************************************
 ---------------------------------------------------------------
     Copyright 1999 Sun Microsystems, Inc., 901 San Antonio
     Road, Palo Alto, CA 94303, U.S.A.  All Rights Reserved.
     The contents of this file are subject to the current
     version of the Sun Community Source License, picoJava-II
     Core ("the License").  You may not use this file except
     in compliance with the License.  You may obtain a copy
     of the License by searching for "Sun Community Source
     License" on the World Wide Web at http://www.sun.com.
     See the License for the rights, obligations, and
     limitations governing use of the contents of this file.

     Sun, Sun Microsystems, the Sun logo, and all Sun-based
     trademarks and logos, Java, picoJava, and all Java-based
     trademarks and logos are trademarks or registered trademarks 
     of Sun Microsystems, Inc. in the United States and other
     countries.
 ----------------------------------------------------------------
******************************************************************/



module	biu (
	icu_req,
	icu_addr,
	icu_type,
	icu_size,
	biu_icu_ack,
	biu_data,
	dcu_req,
	dcu_addr,
	dcu_type,
	dcu_size,
	dcu_dataout,
	biu_dcu_ack,
	clk,
	reset_l,
	pj_addr,
	pj_data_out,
	pj_data_in,
	pj_tv,
	pj_size,
	pj_type,
	pj_ack,
	pj_ale
	);

input		icu_req;
input	[31:0]	icu_addr;			
input	[3:0]	icu_type;			
input	[1:0]	icu_size;			
output	[1:0]	biu_icu_ack;		
output	[31:0]	biu_data;	
input		dcu_req;
input	[31:0]	dcu_addr;			
input	[3:0]	dcu_type;			
input	[1:0]	dcu_size;			
input	[31:0]	dcu_dataout;		
output	[1:0]	biu_dcu_ack;		
input		clk;
input		reset_l;
output	[29:0]	pj_addr;		
output	[31:0]	pj_data_out;	
input	[31:0]	pj_data_in;		
output		pj_tv;
output	[1:0]	pj_size;		
output	[3:0]	pj_type;		
input	[1:0]	pj_ack;
output		pj_ale;

wire		arb_select;
wire	[31:0]	pj_addr_int;
wire	[29:0]	pj_addr;

assign pj_addr = pj_addr_int[29:0];

biu_ctl	biu_ctl (
     .icu_req		(icu_req),
     .icu_type		(icu_type[3:0]),
     .icu_size		(icu_size[1:0]),
     .biu_icu_ack	(biu_icu_ack[1:0]),
     .dcu_req		(dcu_req),
     .dcu_type		(dcu_type[3:0]),
     .dcu_size		(dcu_size[1:0]),
     .biu_dcu_ack	(biu_dcu_ack[1:0]),
     .clk		(clk),
     .reset_l		(reset_l),
     .pj_tv		(pj_tv),
     .pj_type		(pj_type[3:0]),
     .pj_size		(pj_size[1:0]),
     .pj_ack		(pj_ack[1:0]),
     .arb_select	(arb_select),
     .pj_ale		(pj_ale)
);

biu_dpath	biu_dpath (
     .icu_addr		(icu_addr[31:0]),
     .dcu_addr		(dcu_addr[31:0]),
     .dcu_dataout	(dcu_dataout[31:0]),
     .biu_data		(biu_data[31:0]),
     .pj_addr		(pj_addr_int[31:0]),
     .pj_data_in	(pj_data_in[31:0]),
     .pj_data_out	(pj_data_out[31:0]),
     .arb_select	(arb_select)
);

endmodule // biu


module biu_dpath (
	icu_addr,
	dcu_addr,
	dcu_dataout,
	biu_data,
	pj_addr,
	pj_data_in,
	pj_data_out,
	arb_select
	);

input   [31:0]  icu_addr;
input   [31:0]  dcu_addr;
input   [31:0]  dcu_dataout;
output  [31:0]  biu_data;
output  [31:0]  pj_addr;
input   [31:0]  pj_data_in;
output  [31:0]  pj_data_out;
input		arb_select;

assign    pj_addr = arb_select ? icu_addr : dcu_addr; 
assign    pj_data_out = dcu_dataout;
assign    biu_data  = pj_data_in ;

endmodule // biu_dpath


module	biu_ctl (
	icu_req,
	icu_type,
	icu_size,
	biu_icu_ack,
	dcu_req,
	dcu_type,
	dcu_size,
	biu_dcu_ack,
	clk,
	reset_l,
	pj_tv,
	pj_type,
	pj_size,
	pj_ack,
	arb_select,
	pj_ale
	);


input		icu_req;
input   [3:0]   icu_type; 
input   [1:0]   icu_size;
output	[1:0]	biu_icu_ack;		
input		dcu_req;
input	[3:0]	dcu_type;
input	[1:0]	dcu_size;
output	[1:0]	biu_dcu_ack;		
input		clk;
input		reset_l;
output		pj_tv;
input	[1:0]	pj_ack;
output	[3:0]	pj_type;
output  [1:0]   pj_size;
output		arb_select;
output		pj_ale;


wire		arbiter_sel;
wire		temp_arb_sel;
wire		arb_select;
wire		arb_idle;
wire	[4:0]	arb_next_state;
reg	[4:0]	arb_state;
wire		icu_tx;
wire		dcu_tx;
wire	[2:0]	num_acks;
wire	[3:0]	type_state;


assign	pj_tv = ((icu_req | dcu_req) & arb_state[0]) | arb_state[1] ;
assign	icu_tx = !type_state[2];		
assign	dcu_tx = type_state[2];
assign  biu_dcu_ack = {dcu_tx,dcu_tx} & pj_ack;	//2{dcu_tx}
assign  biu_icu_ack = {icu_tx,icu_tx} & pj_ack;
assign	pj_ale = ~(pj_tv & arb_state[0]);

/*	Muxes for pj_type and pj_size */
assign pj_size = arb_select ? icu_size : dcu_size;
assign pj_type = arb_select ? icu_type : dcu_type;

/*	Generation of select for pj_addr, pj_type, and pj_size muxes */
assign    arbiter_sel = icu_req & !dcu_req;

ff_sre	arb_select_state(.out(temp_arb_sel),
			.din(arbiter_sel),
			.clk(clk),
			.reset_l(reset_l),
			.enable(arb_idle));

assign arb_select = arb_idle ? arbiter_sel : temp_arb_sel;
	

/*	State machine for Arbiter */

assign	arb_next_state[4:0] =	arbiter(arb_state[4:0],
					pj_ack[0],
					pj_ack[1],
					pj_tv,
					num_acks);

always @ (posedge clk)
  if (~reset_l)
    arb_state = 5'b00001;
  else
    arb_state = arb_next_state;

initial arb_state = 5'b00001;

//latch pj_type
ff_se_4	type_state_reg(.out(type_state[3:0]), //change
				.din(pj_type[3:0]),
				.clk(clk),
				.enable(arb_idle));


assign	arb_idle = arb_state[0];
assign	num_acks[0] = type_state[1];
assign  num_acks[1] = 1'b0;
assign  num_acks[2] = (type_state[3] | (type_state[2] & (!type_state[1])))|
                      !(type_state[1] | type_state[2] | type_state[3]);

function	[4:0] arbiter;

input	[4:0]	cur_state;
input		normal_ack;
input		error_ack;
input		pj_tv;
input	[2:0]	num_acks;
		
reg	[4:0]	next_state;
parameter
	IDLE		=	5'b00001,
	REQ_ACTIVE	=	5'b00010,
	FILL3		=	5'b00100,
	FILL2		=	5'b01000,
	FILL1		=	5'b10000;
begin

	case (cur_state)
	IDLE: begin
		if (pj_tv) begin
			next_state = REQ_ACTIVE;
		end
		else	next_state = cur_state;
		end
	REQ_ACTIVE: begin
		if ( error_ack | (normal_ack & num_acks[0]))
			next_state = IDLE;
		else if (normal_ack & num_acks[2])
			next_state = FILL3; 
		else if (normal_ack & num_acks[1])
			next_state = FILL1;
		else next_state = cur_state;
		end
	FILL3:begin	
		if (error_ack )  
               next_state = IDLE;
		else if (normal_ack)
			next_state = FILL2;	
		else next_state = cur_state;
		end
	FILL2: begin
		if (error_ack )    
               next_state = IDLE;
		else if (normal_ack)
			next_state = FILL1;
		else next_state = cur_state;
		end
	FILL1:begin
		if (normal_ack | error_ack)
		 next_state = IDLE;
		else next_state = cur_state;
		end
	default:begin
			next_state = 0;
		end
	endcase
arbiter[4:0] = next_state[4:0];
end	
endfunction

endmodule // biu_ctl


module ff_sre(out, din, enable, reset_l, clk) ;
    output   out;
    input    din;
    input    clk;
    input    reset_l;
    input    enable;
 
mj_s_ff_snre_d  mj_s_ff_snre_d_0 (      .out(out),
                                        .in(din), 
                                        .lenable(enable),
                                        .reset_l(reset_l),
                                        .clk(clk)
                                        );
endmodule // ff_sre


module ff_se_4 (out, din, enable, clk) ;
    output  [3:0]  out;
    input   [3:0]  din;
    input           clk;
    input           enable;

    ff_se    ff_se_0(out[0], din[0], enable, clk);
    ff_se    ff_se_1(out[1], din[1], enable, clk);
    ff_se    ff_se_2(out[2], din[2], enable, clk);
    ff_se    ff_se_3(out[3], din[3], enable, clk);

endmodule // ff_se_4


module ff_se (out, din, enable, clk) ;
    output    out;
    input     din;
    input     clk;
    input     enable;
 
mj_s_ff_se_d  mj_s_ff_se_d_0 (  .out(out),
                                .in(din),
                                .lenable(enable),
                                .clk(clk)
                                );

endmodule // ff_se


module mj_s_ff_se_d(out, in, lenable, clk);
output out;
input clk;
input lenable;
input in;
reg out;

initial out = 0;

always @(posedge clk)
        if (lenable)
            out = in;
        else
            out = out;

endmodule // mj_s_ff_se_d


module mj_s_ff_snre_d(out, in, lenable, reset_l, clk);
output out;
input clk;
input lenable;
input reset_l;
input in;

reg out;

initial out = 0;

always @(posedge clk)
        if (~reset_l) 
	    out = 1'b0;
	else
    	if (lenable)
            out = in;
        else
            out = out;

endmodule // mj_s_ff_snre_d
