// SRAM with parallel port interface for XESS XS40 Boards.
// Adapted from the original VHDL of
// Miguel A. Aguirre Echanove. University of Sevilla (SPAIN)
// Dpt. of Ingenieria Electronica. aguirre@gte.esi.us.es
//
// This description is less "structural."  It also ignores details that are
// Xilinx-specific like the special pin instantiations.  Also, registers are
// initialized.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

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

    reg 	 writeb, readb;
    wire 	 wrint, aleint, rdint, nibble;
    wire [1:0] 	 memaddr;
    wire [7:0] 	 ddisp;
    reg [7:0] 	 ad;
    reg [3:0] 	 dout;

    reg [7:0] 	 mem [0:3];
    integer 	 i;

    initial begin
	for (i = 0; i < 4; i = i + 1)
	  mem[i] = 0;
	ad = 0;
	dout = 0;
	writeb = 1;	// initialize writeb and readb to passive state
	readb = 1;
    end

    fsmrdwr fsm1
      (.clk (clk), .rst (rst), .wrexb (writeb), .rdexb (readb),
       .wrint (wrint), .aleint (aleint), .rdint (rdint), .nibble (nibble));

    assign memaddr = ad[1:0];

    always @ (posedge clk) begin
	if (rst) begin
	    for (i = 0; i < 4; i = i + 1)
	      mem[i] = 0;
	    ad = 0;
	    dout = 0;
	end else begin
	    if (wrint)
	      mem[memaddr] = din;
	    if (aleint)
	      ad = din;
	    if (rdint)
	      dout = nibble ? ddisp[7:4] : ddisp[3:0];
	end
    end

    assign ddisp = mem[memaddr];

    // For debugging purpose, use the display
    segment7kk disp
      (.in (ad[3:0]),
       .A (a), .B (b), .C (c), .D (d), .E (e), .F (f), .G (g));

    // Make sure inputs are clean
    always @ (posedge clk) begin
	writeb = wrextb | rst;
	readb = rdextb | rst;
    end

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
	      capdir: begin // latching the address
		  wrint = 0; rdint = 0; aleint = 1; nibble = 0;		
		  if (wrexb && rdexb)
		    fsmstate = reposo;
		  else
		    fsmstate = capdir;
	      end
	      esplee0: begin // waiting to read least significant nibble
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
	      esplee1: begin // waiting to read most significant nibble
		  wrint = 0; rdint = 0; aleint = 0; nibble = 0;
		  if (wrexb && !rdexb)
		    fsmstate = esplee1;
		  else if (!wrexb && !rdexb)
		    fsmstate = espdir;
		  else
		    fsmstate = lee1;
	      end
	      lee1: begin // read most significant nibble
		  wrint = 0;  rdint = 1; aleint = 0; nibble = 1;
		  if (wrexb && rdexb)
		    fsmstate = reposo;
		  else
		    fsmstate = lee1;
	      end
	    endcase // case(fsmstate)
	end
    end

endmodule // fsmrdwr


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
