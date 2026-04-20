// Vending machine that dispenses one item in exchange for 25c.
// The machine accepts nickels, dimes, and quarters.  It gives change if
// it can; otherwise, it returns the coins that were deposited.
//
// Author: Fabio Somenzi <Fabio@Colorado.EDU>

typedef enum {NONE, NICKEL, DIME, QUARTER} Coin;
typedef enum {ACCEPTING, CHANGE, REFUND, BEVERAGE} State;

// Generates a random input coin if enable is asserted; otherwise,
// no coin.
module environment(clock);
    parameter    BITS = 8;
    input 	 clock;
    output [4:0] balance;

    Coin wire    change, nd;
    Coin reg     deposit;
    wire 	 beverage, enable;

    initial deposit = nd;

    vending #(BITS) v(clock,deposit,change,beverage,enable);

    monitor m(clock,deposit,beverage,change,balance);

    assign nd = $ND(NONE, NICKEL, DIME, QUARTER);

    always @ (posedge clock) begin
	if (enable)
	  deposit = nd;
	else
	  deposit = NONE;
    end

endmodule // environment


module vending(clock,deposit,change,beverage,enable);
    parameter      BITS = 8;
    input 	   clock;
    input 	   deposit;	// input coin
    output 	   change;	// output coin (change or refund)
    output 	   beverage;	// causes beverage to be released
    output 	   enable;	// coins are only accepted when enable==1

    Coin wire      deposit;
    Coin reg       change;

    State reg      state;
    // Total numbers of nickels, dimes, and quarters deposited since reset.
    reg [BITS-1:0] t5, t10, t25;
    // Numbers of nickels, dimes, and quarters deposited in this transaction.
    reg [2:0] 	   l5;
    reg [1:0] 	   l10;
    reg [0:0] 	   l25;

    // Number of nickel-equivalents deposited so far during the
    // current transaction.
    wire [3:0] 	   total;

    // #(nickels) + 2 #(dimes) + 5 #(quarters)
    assign total = {1'b0,l5} + {1'b0,l10,1'b0} + {1'b0,l25,1'b0,l25};

    //  Initially the machine has no money and is ready to start a
    // transaction.
    initial begin
	t5 = 0;
	t10 = 0;
	t25 = 0;
	l5 = 0;
	l10 = 0;
	l25 = 0;
	state = ACCEPTING;
	change = NONE;
    end

    assign beverage = (state == BEVERAGE);
    assign enable = (state == ACCEPTING && total < 5);

    always @ (posedge clock) begin
	case (state)
	  ACCEPTING: begin
	      if (total >= 5) begin
		  change = deposit;
		  state = CHANGE;
	      end else begin
		  case (deposit)
		    NICKEL: begin
			if (t5 == {BITS{1'b1}}) begin
			    change = NICKEL;
			end else begin
			    change = NONE;
			    t5 = t5 + 1;
			    l5 = l5 + 1;
			end
		    end
		    DIME: begin
			if (t10 == {BITS{1'b1}}) begin
			    change = DIME;
			end else begin
			    change = NONE;
			    t10 = t10 + 1;
			    l10 = l10 + 1;
			end
		    end
		    QUARTER: begin
			if (t25 == {BITS{1'b1}}) begin
			    change = QUARTER;
			end else begin
			    change = NONE;
			    t25 = t25 + 1;
			    l25 = l25 + 1;
			end
		    end
		    NONE: begin
			change = NONE;
		    end
		  endcase
	      end
	  end
	  // On entry to this state we have between 25c and 45c from the
	  // current transaction.  If we have more than 30c, then we have
	  // at least one quarter, in which case we know we can always
	  // give change.
	  CHANGE: begin
	      if (total == 5) begin
		  change = NONE;
		  state = BEVERAGE;
	      end else if (total == 6) begin
		  if (t5 > 0) begin
		      change = NICKEL;
		      t5 = t5 - 1;
		      // Updating l5 here is not strictly necessary because
		      // we are going to reset it in the next state, and in
		      // any case, we do not guarantee that total will be
		      // up to date.
		      state = BEVERAGE;
		  end else begin
		      change = NONE;
		      state = REFUND;
		  end
	      end else begin	// at least 35c
		  if (l10 > 0) begin
		      change = DIME;
		      t10 = t10 - 1;
		      l10 = l10 - 1;
		  end else begin
		      change = NICKEL;
		      t5 = t5 - 1;
		      l5 = l5 - 1;
		  end
	      end
	  end
	  BEVERAGE: begin
	      change = NONE;
	      l5 = 0;
	      l10 = 0;
	      l25 = 0;
	      state = ACCEPTING;
	  end
	  // On entry to this state, we have exactly three dimes from
	  // the current transaction, and no nickels at all.
	  REFUND: begin
	      if (l10 > 0) begin
		  l10 = l10 - 1;
		  t10 = t10 - 1;
		  change = DIME;
	      end else begin
		  state = ACCEPTING;
		  change = NONE;
	      end
	  end
	endcase // case(state)
    end // always @ (posedge clock)

endmodule // vending


// This module monitors the balance, that is, the difference between the
// net amount deposited in the machine and the value of the goods received.
module monitor (clock,deposit,beverage,change,balance);
    input        clock;
    input 	 deposit;
    input 	 beverage;
    input 	 change;
    output [4:0] balance;	// from -16 to 15

    Coin wire    deposit, change;

    reg [4:0] 	 balance;

    wire [4:0] 	 valD, valC, valB;

    assign valD = (deposit == QUARTER) ? 5'd5 :
	   (deposit == DIME) ? 5'd2 :
	   (deposit == NICKEL) ? 5'd1 : 5'd0;

    assign valC = (change == QUARTER) ? 5'd5 :
	   (change == DIME) ? 5'd2 :
	   (change == NICKEL) ? 5'd1 : 5'd0;

    assign valB = (beverage == 1) ? 5'd5 : 5'd0;

    initial balance = 0;

    always @ (posedge clock) begin
	balance = balance + valD - (valC + valB);
    end

endmodule // monitor
