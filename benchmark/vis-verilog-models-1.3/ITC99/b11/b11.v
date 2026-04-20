// Verilog translation of the original b11 circuit from the ITC99
// benchmark set.

// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {s_reset, s_datain, s_spazio, s_mul, s_somma, s_rsum,
	      s_rsot, s_compl, s_dataout} State;

module b11(x_in, stbi, clock, x_out);
    input [5:0]  x_in;
    input 	 stbi;
    input 	 clock;
    output [5:0] x_out;

    reg [5:0] 	 x_out;
    State reg    stato;
    reg [5:0] 	 r_in;
    reg [5:0] 	 cont;
    reg [8:0] 	 cont1;

    initial begin
	stato = s_reset;
	r_in = 0;
	cont = 0;
	cont1 = 0;
	x_out = 0;
    end

    always @ (posedge clock) begin
	case (stato)
	  s_reset: begin
	      cont = 0;
	      r_in = x_in;
	      x_out = 0;
	      stato = s_datain;
	  end
	  s_datain: begin
	      r_in = x_in;
	      if (stbi)
		stato = s_datain;
	      else
		stato = s_spazio;        
	  end
	  s_spazio: begin
	      if (r_in == 0 || r_in == 63) begin
		  if (cont < 25)
		    cont = cont + 1;
		  else
		    cont = 0;
		  cont1 = {3'b0, r_in};
		  stato = s_dataout;
	      end else if (r_in <= 26) begin
		  stato = s_mul;
	      end else begin
		  stato = s_datain;
	      end
	  end
	  s_mul: begin
	      if (r_in[0])
		cont1 = {2'b0, cont, 1'b0}; // mult by 2 and extend
	      else 
		cont1 = {3'b0, cont};
	      stato = s_somma;
	  end
	  s_somma: begin
	      if (r_in[1]) begin
		  cont1 = {3'b0, r_in} + cont1;
		  stato = s_rsum;
	      end else begin
		  cont1 = {3'b0, r_in} - cont1;
		  stato = s_rsot;
	      end
	  end
	  s_rsum: begin
	      if (!cont1[8] && cont1 > 26) begin
		  cont1 = cont1 - 26;
		  stato = s_rsum;
	      end else begin
		  stato = s_compl;
	      end
	  end
	  s_rsot: begin
	      if (!cont1[8] && cont1 > 63) begin
		  cont1 = cont1 + 26;
		  stato = s_rsot;
	      end else begin
		  stato = s_compl;
	      end
	  end
	  s_compl: begin
	      if (r_in[3:2] == 0)
		cont1 = cont1 - 21;
	      else if (r_in[3:2] == 1)
		cont1 = cont1 - 42;
	      else if (r_in[3:2] == 2)
		cont1 = cont1 + 7;
	      else
		cont1 = cont1 + 28;
	      stato = s_dataout;
	  end
	  s_dataout: begin
	      if (cont1[8])
		x_out = -(cont1[5:0]);
	      else
		x_out = cont1[5:0];
	      stato = s_datain;
	  end
	endcase
    end

endmodule // b11
