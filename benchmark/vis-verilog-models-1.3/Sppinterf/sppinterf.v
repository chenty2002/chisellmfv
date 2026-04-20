// SRAM Parallel Port Interface for XESS XS40 Boards.
// Translated to Verilog from the original VHDL of
// Miguel A. Aguirre Echanove. University of Sevilla (SPAIN)
// Dpt. of Ingenieria Electronica. aguirre@gte.esi.us.es

module sppinterf (clk, rst, din, wrextb, rdextb, dout, a, b, c, d, e, f, g);
    input        clk;
    input 	 rst;
    input [7:0]  din;
    input 	 wrextb;
    input 	 rdextb; 
    output [3:0] dout;
    output 	 a;
    output 	 b;
    output 	 c;
    output 	 d;
    output 	 e;
    output 	 f;
    output 	 g;

    wire 	 writeb, wrint, readb, aleint, rdint, nibble;
    wire [7:0] 	 ddisp, data, dw1, dw0, dw2, dw3;
    wire [4:0] 	 drd, daux;
    wire [3:0] 	 pw,pr;
    wire [7:0] 	 ad;
    wire 	 rdextb, rdextbp;

    assign 	 data = din;

    fsmrdwr fsm1
      (.clk (clk), .rst (rst), .wrexb (writeb), .rdexb (readb),
       .wrint (wrint), .aleint (aleint), .rdint (rdint), .nibble (nibble));
    
    // Four Registers for RAM emulation
    RegSinc #(8) reg0
      (.clk (clk), .reset (rst), .load (pw[0]), .din (data), .dout (dw0));
    RegSinc #(8) reg1
      (.clk (clk), .reset (rst), .load (pw[1]), .din (data), .dout (dw1));
    RegSinc #(8) reg2
      (.clk (clk), .reset (rst), .load (pw[2]), .din (data), .dout (dw2));
    RegSinc #(8) reg3
      (.clk (clk), .reset (rst), .load (pw[3]), .din (data), .dout (dw3));

    // Decoder for write Address
    decod decaw
      (.enable (wrint), .address (ad[1:0]), .pointers (pw[3:0]));

    // Decoder for Read Address
    decod decar
      (.enable (rdint), .address (ad[1:0]), .pointers (pr[3:0]));

    // Multiplexer for Read Selection
    Mux4 #(8) selrd
      (.r0 (dw0), .r1 (dw1), .r2 (dw2), .r3 (dw3), .paddr (pr), .rd (ddisp));

    // Address Register
    RegSinc #(8) rega
      (.clk (clk), .reset (rst), .load (aleint), .din (data), .dout (ad));

    // Store reading Data in an output register
    RegSinc #(5) regr
      (.clk (clk), .reset (rst), .load (rdint), .din (drd), .dout (daux));
    assign 	 dout = daux[3:0];

    // For debugging purpose I use the display
    segment7kk disp
      (.in (ad[3:0]),
       .A (a), .B (b), .C (c), .D (d), .E (e), .F (f), .G (g));

    // This can be included in the input Pad
    FFcleaner ffcln1
      (.D (wrextb), .CLK (clk), .RST (rst), .Q (writeb));
    FFcleaner ffcln2
      (.D (rdextb), .CLK (clk), .RST (rst), .Q (readb));

    // Multiplexer for Nibble selection
    Muxsal muxrd
      (.I (ddisp), .Nibble (nibble), .datard (drd));

endmodule // sppinterf


// FSM For Access Protocol

typedef enum {reposo,espescr,escribe,espdir,capdir,esplee0,lee0,esplee1,lee1}
  state;

module fsmrdwr (clk, rst, wrexb, rdexb, wrint, aleint, rdint, nibble);
    input  clk;
    input  rst;
    input  wrexb;
    input  rdexb;
    output wrint;
    output aleint;
    output rdint;
    output nibble;

    state reg fsmstate;
    reg    wrint, aleint, rdint, nibble;

    initial begin
	fsmstate = reposo;
	wrint = 0; aleint = 0; rdint = 0; nibble = 0;
    end

    always @ (posedge clk) begin
	if (rst) begin
	    fsmstate = reposo;
	end else begin
	    case (fsmstate)
	      reposo: begin // idle
		  wrint = 0; rdint = 0; aleint = 0; nibble = 0;
		  if (wrexb && rdexb)
		    fsmstate = reposo;
		  else if (!wrexb && rdexb)
		    fsmstate = espescr;
		  else if (!wrexb && !rdexb)
		    fsmstate = espdir;
		  else
		    fsmstate = esplee0;
	      end
	      espescr: begin // waiting to write
		  wrint = 0; rdint = 0; aleint = 0; nibble = 0;
		  if (!wrexb && rdexb)
		    fsmstate = espescr;
		  else if (!wrexb && !rdexb)
		    fsmstate = espdir;
		  else
		    fsmstate = escribe;
	      end
	      escribe: begin // writing
		  wrint = 1; rdint = 0; aleint = 0; nibble = 0;		
		  if (wrexb && rdexb)
		    fsmstate = reposo;
		  else
		    fsmstate = escribe;
	      end
	      espdir: begin // waiting for the address
		  wrint = 0; rdint = 0; aleint = 0; nibble =0;		
		  if (!wrexb && !rdexb)
		    fsmstate = espdir;
		  else
		    fsmstate = capdir;
	      end
	      capdir: begin // reading the address
		  wrint = 0; rdint = 0; aleint = 1; nibble = 0;		
		  if (wrexb && rdexb)
		    fsmstate = reposo;
		  else
		    fsmstate = capdir;
	      end
	      esplee0: begin // waiting to read
		  wrint = 0; rdint = 0; aleint = 0; nibble = 0;		
		  if (wrexb && !rdexb)
		    fsmstate = esplee0;
		  else if (!wrexb && !rdexb)
		    fsmstate = espdir;
		  else
		    fsmstate = lee0;
	      end
	      lee0: begin // read least significant nibble
		  wrint = 0; rdint = 1; aleint = 0; nibble = 0;
		  if (rdexb)
		    fsmstate = lee0;
		  else
		    fsmstate = esplee1;
	      end
	      esplee1: begin // waiting to read
		  wrint = 0; rdint = 0; aleint = 0; nibble = 0;
		  if (wrexb && !rdexb)
		    fsmstate = esplee1;
		  else if (!wrexb && !rdexb)
		    fsmstate = espdir;
		  else
		    fsmstate = lee1;
	      end
	      lee1: begin // read most significant nibble
		  wrint = 0; aleint = 0;
		  rdint = 1;
		  nibble = 1; // capture also the nibble bit
		  if (wrexb && rdexb)
		    fsmstate = reposo;
		  else
		    fsmstate = lee1;
	      end
	    endcase // case(fsmstate)
	end
    end

endmodule // fsmrdwr


// Generic register

module RegSinc (clk, reset, load, din, dout);
    parameter      N = 8;
    input 	   clk;
    input 	   reset;
    input 	   load;
    input [N-1:0]  din;
    output [N-1:0] dout;

    reg [N-1:0]    dout;

    initial dout = 0;

    always @ (posedge clk) begin
	if (reset)
	  dout = 0;
	else if (load)
	  dout = din;
    end

endmodule // RegSinc


// 7 Segments Driver

module segment7kk (in, A, B, C, D, E, F, G);
    input [3:0] in;
    output 	A;
    output 	B;
    output 	C;
    output 	D;
    output 	E;
    output 	F;
    output 	G;

    //  segment encoding
    //       A
    //      ---  
    //   B |   | F
    //      ---   <- G
    //   C |   | E
    //      ---
    //       D

    assign A = in == 0 || in == 2 || in == 3 || in == 5 || in == 6 ||
	   in == 7 || in == 8 || in == 9 || in == 10 || in == 14 || in == 15;
    assign B = in == 0 || in == 4 || in == 5 || in == 6 || in == 6 ||
	   in == 8 || in == 9 || in == 10 || in == 11 || in == 14 || in == 15;
    assign C = in == 0 || in == 2 || in == 6 || in == 8 || in == 10 ||
	   in == 11 || in == 12 || in == 13 || in == 14 || in == 15;
    assign D = in == 0 || in == 2 || in == 3 || in == 5 || in == 6 ||
	   in == 8 || in == 11 || in == 12 || in == 13 || in == 14;
    assign E = in == 0 || in == 1 || in == 3 || in == 4 || in == 5 ||
	   in == 6 || in == 7 || in == 8 || in == 9 || in == 10 || in == 11 ||
	   in == 13;
    assign F = in == 0 || in == 1 || in == 2 || in == 3 || in == 4 ||
	   in == 7 || in == 8 || in == 9 || in == 10 || in == 13 || in == 14;
    assign G = in == 2 || in == 3 || in == 4 || in == 5 || in == 6 ||
	   in == 8 || in == 9 || in == 10 || in == 11 || in == 12 ||
	   in == 13 || in == 14 || in == 15;

endmodule // segment7kk


// Mux for read byte

module Mux4 (r0, r1, r2, r3, paddr, rd);
    parameter      N = 8;
    input [N-1:0]  r0;
    input [N-1:0]  r1;
    input [N-1:0]  r2;
    input [N-1:0]  r3;
    input [3:0]    paddr;
    output [N-1:0] rd;

    assign rd =
	   paddr[0] ? r0 :
	   paddr[1] ? r1 :
	   paddr[2] ? r2 :
	   paddr[3] ? r3 :
	   0;

endmodule // Mux4


// Read Nibbles Mux

module Muxsal (I, Nibble, datard);
    input [7:0]  I;
    input 	 Nibble;
    output [4:0] datard;

    assign 	 datard[3:0] = Nibble ? I[7:4] : I[3:0];
    assign 	 datard[4] = Nibble;

endmodule // Muxsal


// Decoder for Address Selection

module decod (enable, address, pointers);
    input 	 enable;
    input [1:0]  address;
    output [3:0] pointers;

    function [3:0] sel;
	input       en;
	input [1:0] in;
	begin: _sel
	    if (en) begin
		if (in == 0) sel = 1;
		else if (in == 1) sel = 2;
		else if (in == 2) sel = 4;
		else sel = 8;
	    end else
	      sel = 0;
	end
    endfunction // sel
    
    assign pointers = sel(enable,address);

endmodule // decod


// FF for glitch filtering

module FFcleaner (D, CLK, RST, Q);
    input  D;
    input  CLK;
    input  RST;
    output Q;

    reg    Q;

    initial Q = 1;

    always @ (posedge CLK)
      if (RST)
	Q = 1;
      else
	Q = D;

endmodule // FFcleaner
