// Simple digital lock.
// The combination is entered by asserting inputs up and down.
// (Pressing both has the same effect as pressing neither.)
// At each clock cycle, if up is 1 and down is 0, the "knob" of the lock is
// turned clockwise by one notch; if up is 0 and down is one, the knob is
// turned counterclockwise by one notch.
// The lock opens if:
//   12 is reached while turning clockwise
//   21 is then reached by turning counterclockwise
//   15 is the reached by turning clockwise
// When this sequence of events occurs, the lock remains open for a least
// one clock cycle, and for as long as the knob does not move from 15.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>
//
module lock(clock,up,down,open,position);
    parameter	   MSB = 4;
    input 	   clock;
    input 	   up;
    input 	   down;
    output 	   open;
    output [MSB:0] position;

    reg [MSB:0]    position;	// 0 - 2**(MSB+1) - 1
    reg [1:0] 	 state;		// number of correct numbers read
    reg 	 upReg;		// latched up
    reg 	 downReg;	// latched down

    initial begin
	position = 0;
	state = 0;
	upReg = 0;
	downReg = 0;
    end

    always @ (posedge clock) begin
	if (up & ~down)
	  position = position + 1;
	else if (down & ~up)
	  position = position - 1;
	upReg = up & ~down;
	downReg = down & ~up;
    end // always @ (posedge clock)

    always @ (posedge clock) begin
	case (state)
	  0: if (position == 12 && upReg)
	    state = 1;
	  1: if (upReg)
	    state = 0;
	  else
	    if (position == 21 && downReg)
	      state = 2;
	  2: if (downReg)
	    state = 0;
	  else
	    if (position == 15 && upReg)
	      state = 3;
	  3: if (upReg || downReg)
	    state = 0;
	endcase // case(state)
    end // always @ (posedge clock)

    assign open = state == 3;

endmodule // lock
