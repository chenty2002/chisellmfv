// Verilog translation of the original b10 circuit from the ITC99
// benchmark set.

// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {STARTUP, STANDBY, GET_IN, START_TX, SEND, TX_2_RX, RECEIVE,
	      RX_2_TX, END_TX, TEST_1, TEST_2} State;

module b10(r_button, g_button, key, start, test, cts, ctr, rts, rtr,
	   clock, v_in, v_out);
    input        r_button, g_button, key, start, test;
    output 	 cts, ctr;
    input 	 rts, rtr;
    input 	 clock;
    input [3:0]  v_in;
    output [3:0] v_out;

    reg 	 cts, ctr;
    reg [3:0] 	 v_out;
    State reg    stato;
    reg 	 voto0, voto1, voto2, voto3;
    reg [3:0] 	 sign;
    reg 	 last_g, last_r;

    initial begin
	stato = STARTUP;
	voto0 = 0;
	voto1 = 0;
	voto2 = 0;
	voto3 = 0;
	sign = 0;
	last_g = 0;
	last_r = 0;
	cts = 0;
	ctr = 0;
	v_out = 0;
    end

    always @ (posedge clock) begin
	case (stato)

	  STARTUP: begin
	      voto0 = 0;
	      voto1 = 0;
	      voto2 = 0;
	      voto3 = 0;
	      cts   = 0;
	      ctr   = 0;
	      if (test == 0) begin
		  sign = 0;
		  stato = TEST_1;
	      end else begin
		  stato = STANDBY;
	      end
	  end
	  STANDBY: begin
	      if (start) begin
		  voto0 = 0;
		  voto1 = 0;
		  voto2 = 0;
		  voto3 = 0;
		  stato = GET_IN;
	      end

	      cts = rtr;
	  end
	  GET_IN: begin
	      if (!start)
		stato = START_TX;
	      else if (key) begin
		  voto0 = key;
		  if ((g_button ^ last_g) & g_button)
		    voto1 = ~voto1;
		  if ((r_button ^ last_r) & r_button )
		    voto2 = ~voto2;
		  last_g = g_button;
		  last_r = r_button;
	      end else begin
		  voto0 = 0;
		  voto1 = 0;
		  voto2 = 0;
		  voto3 = 0;
	      end
	  end
	  START_TX: begin
	      voto3 = voto0 ^ voto1 ^ voto2;
	      stato = SEND;
	      voto0 = 0;
	  end
	  SEND: begin
	      if (rtr) begin
		  v_out = {voto3, voto2, voto1, voto0};
		  cts = 1;
		  if (!voto0 && voto1 && voto2 && !voto3)
		    stato = END_TX;
		  else
		    stato = TX_2_RX;
	      end
	  end
	  TX_2_RX: begin
	      if (!rts) begin
		  ctr = 1;
		  stato = RECEIVE;
	      end
	  end
	  RECEIVE: begin
	      if (rts) begin
		  {voto3, voto2, voto1, voto0} = v_in;
		  ctr = 0;
		  stato = RX_2_TX;
	      end
	  end
	  RX_2_TX: begin
	      if (!rtr) begin
		  cts = 0;
		  stato = SEND;
	      end
	  end
	  END_TX: begin
	      if (!rtr) begin
		  cts = 0;
                  stato = STANDBY;
	      end
	  end
	  TEST_1: begin
	      {voto3, voto2, voto1, voto0} = v_in;
	      sign = 4'b1000;
	      if (voto0 && voto1 && voto2 && voto3)
		stato = TEST_2;
	  end
	  TEST_2: begin
	      voto0 = ~sign[0];
	      voto0 =  sign[1];  // probably buggy
	      voto0 =  sign[2];
	      voto0 = ~sign[3];
	      stato = SEND;
	  end
	endcase
    end

endmodule // b10
