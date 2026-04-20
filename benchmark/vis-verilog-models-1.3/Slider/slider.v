/************************************************************************/


typedef enum {BLNK, one, two, three, four, five, six, seven, eight} STATE;
typedef enum {left, right, top, down} DIRN;

module  game(clk);
  input clk;

STATE wire init11, init12, init13, init21, init22, init23, init31, init32, init33;

STATE wire state11, state12, state13, state21, state22, state23, state31, state32, state33;
STATE wire ns_state11, ns_state12, ns_state13, ns_state21, ns_state22, ns_state23, 
		   ns_state31, ns_state32, ns_state33;

assign init11 = BLNK;
assign init12 = one;
assign init13 = two;
assign init21 = three;
assign init22 = four;
assign init23 = five;
assign init31 = six;
assign init32 = seven;
assign init33 = eight;

square A11( clk, init11, ns_state11, state11 );
square A31( clk, init31, ns_state31, state31 );
square A13( clk, init13, ns_state13, state13 );
square A33( clk, init33, ns_state33, state33 );

square A12( clk, init12, ns_state12, state12 );
square A21( clk, init21, ns_state21, state21 );
square A23( clk, init23, ns_state23, state23 );
square A32( clk, init32, ns_state32, state32 );

square A22( clk, init22, ns_state22, state22 );

nsfunction B( clk, state11, state12, state13, state21, state22, state23, 
		           state31, state32, state33, ns_state11, ns_state12, 
				   ns_state13, ns_state21, ns_state22, ns_state23, ns_state31, 
				   ns_state32, ns_state33 );


endmodule      
/************************************************************************/
module  square(clk, init, in, out );
  input clk;
  input init, in;
  output out;

  STATE wire in;
  STATE wire init;
  STATE wire out;
  STATE reg state;
  initial state = init;

assign out = state;

always @(posedge clk) 

  begin
	state = in;
  end
			
endmodule 
/************************************************************************/


module nsfunction ( clk, state11, state12, state13, state21, state22, state23, 
		            state31, state32, state33, ns_state11, ns_state12, 
				    ns_state13, ns_state21, ns_state22, ns_state23, ns_state31, 
				    ns_state32, ns_state33 );
input clk;
input state11, state12, state13, state21, state22, state23, state31, state32, state33;
output ns_state11, ns_state12, ns_state13, ns_state21, ns_state22, ns_state23,
	   ns_state31, ns_state32, ns_state33;

STATE wire state11, state12, state13, state21, state22, state23, state31, state32, state33;
STATE wire ns_state11, ns_state12, ns_state13, ns_state21, ns_state22, ns_state23,
	       ns_state31, ns_state32, ns_state33;

DIRN wire nd;

assign nd = $ND(left, right, top, down );

assign ns_state11 =  (((state12 == BLNK)&&(nd == left))||((state21 == BLNK)&&(nd == top))) 
						 ? BLNK : 
							 ((state11 == BLNK)&&(nd == right)) 
								? state12 : 
								   ((state11 == BLNK)&&(nd == down)) ?
									  state21 : state11;

assign ns_state13 =  (((state12 == BLNK)&&(nd == right))||((state23 == BLNK)&&(nd == top))) 
						 ? BLNK : 
							 ((state13 == BLNK)&&(nd == left)) 
								? state12 : 
								   ((state13 == BLNK)&&(nd == down)) ?
									  state23 : state13;

assign ns_state31 =  (((state21 == BLNK)&&(nd == down))||((state32 == BLNK)&&(nd == left))) 
						 ? BLNK : 
							 ((state31 == BLNK)&&(nd == top)) 
								? state21 : 
								   ((state31 == BLNK)&&(nd == right)) ?
									  state32 : state31;

assign ns_state33 =  (((state32 == BLNK)&&(nd == right))||((state23 == BLNK)&&(nd == down))) 
						 ? BLNK : 
							 ((state33 == BLNK)&&(nd == left)) 
								? state32 : 
								   ((state33 == BLNK)&&(nd == top)) ?
									  state23 : state33;
assign ns_state12 = (((state11 == BLNK)&&(nd == right))||((state13 == BLNK)&&(nd == left))||
                      ((state22 == BLNK)&&(nd == top))) 
						? BLNK :
							((state12 == BLNK)&&(nd == left))
							   ? state11 :
								  ((state12 == BLNK)&&(nd == right))
									 ? state13 : 
										((state12 == BLNK)&&(nd == down)) 
										   ? state22 
											   : state12;

assign ns_state21 = (((state11 == BLNK)&&(nd == down))||((state22 == BLNK)&&(nd == left))||
                      ((state31 == BLNK)&&(nd == top))) 
						? BLNK :
							((state21 == BLNK)&&(nd == top))
							   ? state11 :
								  ((state21 == BLNK)&&(nd == right))
									 ? state22 : 
										((state21 == BLNK)&&(nd == down)) 
										   ? state31 
											   : state21;

assign ns_state23 = (((state13 == BLNK)&&(nd == down))||((state22 == BLNK)&&(nd == right))||
                      ((state33 == BLNK)&&(nd == top))) 
						? BLNK :
							((state23 == BLNK)&&(nd == left))
							   ? state22 :
								  ((state23 == BLNK)&&(nd == top))
									 ? state13 : 
										((state23 == BLNK)&&(nd == down)) 
										   ? state33 
											   : state23;

assign ns_state32 = (((state31 == BLNK)&&(nd == right))||((state22 == BLNK)&&(nd == down))||
                      ((state33 == BLNK)&&(nd == left))) 
						? BLNK :
							((state32 == BLNK)&&(nd == left))
							   ? state31 :
								  ((state32 == BLNK)&&(nd == right))
									 ? state33 : 
										((state32 == BLNK)&&(nd == top)) 
										   ? state22 
											   : state32;

assign ns_state22 = (((state12 == BLNK)&&(nd == down))||((state21 == BLNK)&&(nd == right))||
                      ((state23 == BLNK)&&(nd == left))||((state32 == BLNK)&&(nd == top))) 
						? BLNK :
							((state22 == BLNK)&&(nd == left))
							   ? state21 :
								  ((state22 == BLNK)&&(nd == right))
									 ? state23 : 
										((state22 == BLNK)&&(nd == top)) 
										   ? state12 :
											  ((state22 == BLNK )&&(nd == down))
												 ? state32
											       : state22;
endmodule
