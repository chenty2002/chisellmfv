// Verilog translation of the original b02 circuit from the ITC99
// benchmark set.

// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {A, B, C, D, E, F, G} State;

module b02(clock, LINEA, U);
    input  clock;
    input  LINEA;
    output U;

    reg    U;
    State reg stato;

    initial begin
	stato = A;
	U = 0;
    end

    always @ (posedge clock) begin
	case (stato)
          A: begin
              stato = B;
              U = 0;
	  end
          B: begin
              if (LINEA == 0)
                stato = C;
              else
                stato = F;
              U = 0;
	  end
          C: begin
              if (LINEA == 0)
                stato = D;
              else
                stato = G;
              U = 0;
	  end
          D: begin
              stato = E; 
              U = 0;
	  end
          E: begin
              stato = B;
              U = 1;
	  end
	  F: begin
              stato = G;
              U = 0;
	  end
          G: begin
              if (LINEA == 0)
                stato = E;
              else
                stato = A;
              U = 0;
          end
        endcase
    end

endmodule // b02
