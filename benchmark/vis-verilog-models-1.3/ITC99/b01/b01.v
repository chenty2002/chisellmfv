// Verilog translation of the original b01 circuit from the ITC99
// benchmark set.

// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {a, b, c, e, f, g, wf0, wf1} State;

module b01(LINE1, LINE2, OUTP, OVERFLW, clock);
    input  LINE1, LINE2;
    output OUTP, OVERFLW;
    input  clock;

    reg    OUTP, OVERFLW;
    State reg stato;

    initial begin
	stato = a;
	OUTP = 0;
	OVERFLW = 0;
    end

    always @ (posedge clock) begin
	case (stato)
	  a: begin
	      if (LINE1 & LINE2)
		stato = f;
	      else
		stato = b;
	      OUTP = LINE1 ^ LINE2;
	      OVERFLW = 0;
	  end
	  e: begin
	      if (LINE1 & LINE2)
		stato = f;
	      else
		stato = b;
	      OUTP = LINE1 ^ LINE2;
	      OVERFLW = 1;
	  end
          b: begin
              if (LINE1 & LINE2)
                stato = g;
              else 
                stato = c;
	      OUTP = LINE1 ^ LINE2;
	      OVERFLW = 0;
          end
          f: begin
              if (LINE1 | LINE2)
                stato = g;
              else
                stato = c;
	      OUTP = ~(LINE1 ^ LINE2);
	      OVERFLW = 0;
          end
          c: begin
              if (LINE1 & LINE2)
                stato = wf1;
              else
                stato = wf0;
              OUTP = LINE1 ^ LINE2;
	      OVERFLW = 0;
          end
	  g: begin
              if (LINE1 | LINE2)
                stato = wf1;
              else
                stato = wf0;
	      OUTP = ~(LINE1 ^ LINE2);
	      OVERFLW = 0;
          end
          wf0: begin
              if (LINE1 & LINE2)
                stato = e;
              else
                stato = a;
	      OUTP = LINE1 ^ LINE2;
	      OVERFLW = 0;
          end
          wf1: begin
              if (LINE1 | LINE2)
                stato = e;
              else             
                stato = a;
	      OUTP = ~(LINE1 ^ LINE2);
	      OVERFLW = 0;
          end
	endcase
    end

endmodule // b01
