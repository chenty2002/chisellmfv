// This model describes the following puzzle.
// We have three cups: large, medium, and small.
//  - The large cup has a capacity of 12 ounces.
//  - The medium cup has a capacity of 8 ounces.
//  - The small cup has a capacity of 5 ounces.
// Initially, the large cup contains 12 ounces of water.  How can we pour
// the water from one cup to the other so that in the end we are guaranteed
// that there are 6 ounces in each of the larger cups?
// The cups have no graduation or mark that may help in measuring volumes.
//
// This model is based on the observation that pouring water so that the
// cup that receives is filled or the cup that is poured is emptied
// allows one to keep track of how much water is in each cup.  A solution
// is therefore built out of these moves only.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {LARGE, MEDIUM, SMALL} Cup;

module cups(clock,to,from,done);
    input clock;
    input to, from;
    output done;

    Cup wire to, from;
    Cup reg freg, treg;
    wire [3:0] resiS, resiM, resiL;
    reg [3:0] Small, Medium, Large;

    initial begin
	Large = 4'd12;
	Medium = 4'd0;
	Small = 4'd0;
	freg = LARGE;
	treg = $ND(MEDIUM,SMALL);
    end

    // Latch inputs so that we can refer to them in properties.
    always @ (posedge clock) begin
	freg = from;
	treg = to;
    end

    assign done = (Large == 4'd6) && (Medium == 4'd6);

    always @ (posedge clock) begin
	if (freg == LARGE) begin
	    if (treg == MEDIUM) begin
		if (Large >= resiM) begin
		    Large = Large - resiM;
		    Medium = 4'd8;
		end else begin
		    Medium = Medium + Large;
		    Large = 4'd0;
		end
	    end else if (treg == SMALL) begin
		if (Large >= resiS) begin
		    Large = Large - resiS;
		    Small = 4'd5;
		end else begin
		    Small = Small + Large;
		    Large = 4'd0;
		end
	    end
	end else if (freg == MEDIUM) begin
	    if (treg == LARGE) begin
		if (Medium >= resiL) begin
		    Medium = Medium - resiL;
		    Large = 4'd12;
		end else begin
		    Large = Large + Medium;
		    Medium = 4'd0;
		end
	    end else if (treg == SMALL) begin
		if (Medium >= resiS) begin
		    Medium = Medium - resiS;
		    Small = 4'd5;
		end else begin
		    Small = Small + Medium;
		    Medium = 4'd0;
		end
	    end
	end else if (freg == SMALL) begin
	    if (treg == LARGE) begin
		if (Small >= resiL) begin
		    Small = Small - resiL;
		    Large = 4'd12;
		end else begin
		    Large = Large + Small;
		    Small = 4'd0;
		end
	    end else if (treg == MEDIUM) begin
		if (Small >= resiM) begin
		    Small = Small - resiM;
		    Medium = 4'd8;
		end else begin
		    Medium = Medium + Small;
		    Small = 4'd0;
		end
	    end
	end
    end

    assign resiS = 4'd5 - Small;
    assign resiM = 4'd8 - Medium;
    assign resiL = 4'd12 - Large;

endmodule // cups
