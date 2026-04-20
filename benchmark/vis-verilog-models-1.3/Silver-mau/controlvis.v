module control(
	Clk,
        Rst_n,
	WorkMAU,
	AccessMode,
	Match,
	Valid,
	ReadDoneFromBCU_n,
	WriteDoneFromBCU_n,
	Write,
	BCURequest_n,
	BCUWriteRequest_n,
	BCUDataOE,
	CacheDataSelect,
	MAUNotReady_n
	);

     input		Clk;
     input		Rst_n;
     input		WorkMAU;
     input	[1:0]	AccessMode;
     input		Match;
     input		Valid;
     input   		ReadDoneFromBCU_n;
     input   		WriteDoneFromBCU_n;
     output		Write;
     output		BCURequest_n;
     output		BCUWriteRequest_n;
     output		BCUDataOE;
     output		CacheDataSelect;
     output		MAUNotReady_n;

     reg	[2:0]	State;

     reg	[5:0]	vector;
      //wire	[5:0]	Vector;

     parameter  
                STATE_IDLE              = 0,
                STATE_READ_HIT          = 1,
                STATE_READ_MISS         = 2,
                STATE_READ_DATA         = 3,
                STATE_WRITE_HIT         = 4,
                STATE_WRITE_MISS        = 5;
     reg		rRst_n;
     reg		rWorkMAU;
     reg	[1:0]	rAccessMode;
     reg		rMatch;
     reg		rValid;
     reg   		rReadDoneFromBCU_n;
     reg   		rWriteDoneFromBCU_n;

     initial begin
	State = STATE_IDLE;
	vector = 6'b011001;
	rRst_n = 0;
     	rWorkMAU = 0;
	rAccessMode =0;
	rMatch = 0;
	rValid = 0;
	rReadDoneFromBCU_n = 0;
	rWriteDoneFromBCU_n = 0;
     end
   always @ (posedge Clk)
     begin
	rRst_n = Rst_n;
	rWorkMAU = WorkMAU;
	rAccessMode = AccessMode;
	rMatch = Match;
	rValid = Valid;
	rReadDoneFromBCU_n = ReadDoneFromBCU_n;
	rWriteDoneFromBCU_n = WriteDoneFromBCU_n;
     end // always @ (posedge clk)
   

   //assign Vector = vector;
   
   assign Write		= vector[5];
   assign BCURequest_n	= vector[4];
   assign BCUWriteRequest_n	= vector[3];
   assign BCUDataOE		= vector[2];
   assign CacheDataSelect	= vector[1];
   assign MAUNotReady_n	= vector[0];


   always @(posedge Clk or rWorkMAU) begin
      if (!Rst_n) State = STATE_IDLE;
      else
	case (State)
	  STATE_IDLE:
	    if (rWorkMAU) begin
	       if (rAccessMode[0]) begin	          // write
		  if (rValid && rMatch) begin          // write hit
		     State = STATE_WRITE_HIT;
		  end
		  else begin                         // write miss
		     State = STATE_WRITE_MISS;
		  end
	       end
	       else if (!rAccessMode[0]) begin 	  // read
		  if (rValid && rMatch) begin          // read hit
		     State = STATE_READ_HIT;
		  end
		  else begin                         // read miss
		     State = STATE_READ_MISS;
		  end
	       end
	    end // if (rWorkMAU)
	  STATE_READ_HIT:
	    State = STATE_IDLE;
          STATE_READ_MISS:
	    begin
	       if (!rReadDoneFromBCU_n) begin         // data is ready 
		  State = STATE_READ_DATA;// update cache
	       end
	    end
          STATE_READ_DATA:
	    begin
	       State = STATE_IDLE;
	    end
          STATE_WRITE_HIT:
	    begin
	       if (!rWriteDoneFromBCU_n) begin
		  State = STATE_IDLE;
	       end
	    end
          STATE_WRITE_MISS:
	    begin
	       if (!rWriteDoneFromBCU_n) begin
		  State = STATE_IDLE;
	       end
	    end
	endcase
   end


   always@(State) begin
      case (State)
	STATE_IDLE:	 vector = 6'b011001;
	STATE_READ_HIT:	 vector = 6'b011000;
	STATE_READ_MISS: vector = 6'b001000;
	STATE_READ_DATA: vector = 6'b111010;
	STATE_WRITE_HIT: vector = 6'b110100;
	STATE_WRITE_MISS:vector = 6'b010100;
      endcase
   end
 /*  assign Vector =
  (State == STATE_IDLE) ?  6'b011001 :
  (State == STATE_READ_HIT) ?	6'b011000 :
  (State == STATE_READ_MISS) ?	6'b001000 :
  (State == STATE_READ_DATA) ?	6'b111010 :
  (State == STATE_WRITE_HIT) ?	6'b110100 :
  (State == STATE_WRITE_MISS) ?	6'b010100 : 6'b011001;
*/
endmodule // control






