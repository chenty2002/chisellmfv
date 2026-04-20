// Model of the chameleons game.
//
// N chameleons are arranged in a circle.  Chameleons can be RED, GREEN,
// or BLUE.  At each step, if two adjacent chameleons are of different color,
// they can switch to the third color.
//
// If all chameleons are of the same color, the game has reached a stable
// configuration.

// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {RED, GREEN, BLUE} color;

module chameleon(clock,first);
    parameter    BITS = 2;
    parameter 	 MSB = BITS - 1;
    parameter    N = 1 << BITS;
    input 	 clock;
    // The first element of the pair to check is chosen at random.
    // The second element is the one immediately following.
    input [MSB:0]  first;

    color reg	 cham[N-1:0];
    reg [MSB:0]  select;
    reg 	 stable;
    wire [MSB:0] second;
    integer 	 i;

    // All possible initial states.
    initial begin
	for (i = 0; i < N; i = i + 1)
	  cham[i] = $ND(RED,GREEN,BLUE);
	select = first;
	stable = 1;
	for (i = 0; i < N-1; i = i + 1)
	  stable = stable && (cham[i] == cham[i+1]);
    end

    assign second = first + {{MSB{1'b0}},1'b1};

    // Latch first for the fairness conditions.
    always @ (posedge clock) begin
	select = first;
    end

    always @ (posedge clock) begin
	if (cham[first] == RED) begin
	    if (cham[second] == GREEN) begin
		cham[first] = BLUE;
		cham[second] = BLUE;
	    end else if (cham[second] == BLUE) begin
		cham[first] = GREEN;
		cham[second] = GREEN;
	    end
	end else if (cham[first] == GREEN) begin
	    if (cham[second] == RED) begin
		cham[first] = BLUE;
		cham[second] = BLUE;
	    end else if (cham[second] == BLUE) begin
		cham[first] = RED;
		cham[second] = RED;
	    end
	end else if (cham[first] == BLUE) begin
	    if (cham[second] == RED) begin
		cham[first] = GREEN;
		cham[second] = GREEN;
	    end else if (cham[second] == GREEN) begin
		cham[first] = RED;
		cham[second] = RED;
	    end
	end
	stable = 1;
	for (i = 0; i < N-1; i = i + 1)
	  stable = stable && (cham[i] == cham[i+1]);
    end

endmodule // chameleon
