// Microwave oven model taken from Chapter 4 of "Model Checking"
// by Clarke, Grumberg, and Peled.

// Author: Fabio Somenzi <Fabio@Colorado.EDU>

module microwave(clock, reset, openDoor, closeDoor, done);
    input     clock;
    input     reset, openDoor, closeDoor, done;

    reg       Start, Close, Heat, Error;

    initial begin
	Start = 0;
	Close = 0;
	Heat  = 0;
	Error = 0;
    end

    always @ (posedge clock) begin
	case ({Error,Heat,Close,Start})
	  4'b0000:
	    if (closeDoor) {Error,Heat,Close,Start} = 4'b0010;
	    else           {Error,Heat,Close,Start} = 4'b1001;
	  4'b1001:	   			   
	                   {Error,Heat,Close,Start} = 4'b1011;
	  4'b1011:	   			   
	    if (reset)     {Error,Heat,Close,Start} = 4'b0010;
	    else           {Error,Heat,Close,Start} = 4'b1001;
	  4'b0010:	   			   
	    if (openDoor)  {Error,Heat,Close,Start} = 4'b0000;
	    else           {Error,Heat,Close,Start} = 4'b0011;
	  4'b0011:	   			   
	                   {Error,Heat,Close,Start} = 4'b0111;
	  4'b0111:	   			   
	                   {Error,Heat,Close,Start} = 4'b0110;
	  4'b0110:	   			   
	    if (openDoor)  {Error,Heat,Close,Start} = 4'b0000;
	    else if (done) {Error,Heat,Close,Start} = 4'b0010;
	endcase
    end

endmodule // microwave
