// This model encodes the famous cabbage/goat/wolf puzzle.
// A man has to cross a river on a boat.  He is traveling with a cabbage,
// a goat, and a wolf.
// If left unattended, the wolf will eat the goat, and the goat will eat
// the cabbage.  Only one passenger can be carried by the boat besides the
// man himself.
// How can the man proceed to successfully cross the river without losing
// either the cabbage or the goat?

// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {NONE, CABBAGE, GOAT, WOLF} passenger;
typedef enum {LEFT, RIGHT} side;

module cgw(clock, select, safe, final);
    input  clock, select;
    output safe, final;

    passenger wire select;

    side reg boat, cabbage, goat, wolf;

    initial begin
	boat = LEFT;
	cabbage = LEFT;
	goat = LEFT;
	wolf = LEFT;
    end

    always @ (posedge clock) begin
	if (select == CABBAGE && boat == cabbage)
	  cabbage = cabbage == RIGHT ? LEFT : RIGHT;
	else if (select == GOAT && boat == goat)
	  goat = goat == RIGHT ? LEFT : RIGHT;
	else if (select == WOLF && boat == wolf)
	  wolf = wolf == RIGHT ? LEFT : RIGHT;
    end

    // This is the restrictive version of the transition relation, in which
    // the selected passenger (if any) must be on the same side as the boat
    // for the boat to cross the river.  This is not strictly necessary,
    // because we can let the boat cross the river even when the passenger
    // is not on the same side, provided no passenger switches side.
    // However, this stricter version makes the counterexample easier to
    // read.  In the more relaxed version, NONE can be omitted from the
    // definition of the passenger type.
    always @ (posedge clock)
      if (select == NONE || select == CABBAGE && cabbage == boat ||
	  select == GOAT && goat == boat || select == WOLF && wolf == boat)
	boat = boat == RIGHT ? LEFT : RIGHT;

    assign safe = boat == goat || (goat != wolf && goat != cabbage);

    assign final = goat == RIGHT && wolf == RIGHT &&
	   cabbage == RIGHT && boat == RIGHT;

endmodule // cgw
