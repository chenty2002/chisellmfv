// This is model can be used to verify the following claim.
//
// We are given two hourglasses: one measures 4 minutes, and the other
// measures 7 minutes.  The only intervals we cannot measure are:
// 1, 2, 3, 5, and 6 minutes.
//
// We model this puzzle as follows.  The clock ticks mark the occurrence of
// events.  An event occurs when the houglass with the least amount of sand
// in its upper half terminates.  If we only turn an hourglass in response to
// an event, then we can keep track of how sand is divided in both hourglasses.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

module ghg(clock, turnSmall, turnLarge, startTime, done, failed);
    parameter     MSB = 13;
    input         clock;
    input         turnSmall, turnLarge;
    input [MSB:0] startTime;
    output 	  done, failed;

    reg [MSB:0]   elapsed;
    // These two registers hold how many minutes' worth of sand is in each
    // hourglass top half.
    reg [2:0] 	  Small, Large;
    reg 	  ts, tl;

    parameter 	  SMALL = 4;
    parameter 	  LARGE = 7;

    initial begin
 	elapsed = startTime;
	Small = 0;
	Large = 0;
	ts = 0;
	tl = 0;
    end

    assign done = elapsed == 0;
    assign failed = elapsed == 1 || elapsed == 2 || elapsed == 3 ||
	   elapsed == 5 || elapsed == 6;

    always @ (posedge clock) begin
	ts = turnSmall;
	tl = turnLarge;
    end

    always @ (posedge clock) begin
	if (Small < Large) begin
	    if (Small > 0) begin
		if (elapsed >= Small) begin
		    elapsed = elapsed - {{MSB-2{1'b0}}, Small};
		    Large = Large - Small;
		    Small = 0;
		end
	    end else begin
		if (elapsed >= Large) begin
		    elapsed = elapsed - {{MSB-2{1'b0}}, Large};
		    Large = 0;
		end
	    end
	end else begin
	    if (Large > 0) begin
		if (elapsed >= Large) begin
		    elapsed = elapsed - {{MSB-2{1'b0}}, Large};
		    Small = Small - Large;
		    Large = 0;
		end
	    end else begin
		if (elapsed >= Small) begin
		    elapsed = elapsed - {{MSB-2{1'b0}}, Small};
		    Small = 0;
		end
	    end
	end
	if (ts) Small = SMALL - Small;
	if (tl) Large = LARGE - Large;
    end

endmodule // ghg
