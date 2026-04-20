/* Dining philosophers of E. W. Dijkstra
        based on S/R implementation by R. Kurshan
	Ramin Hojati, May 1993

*/
typedef enum {THINKING, HUNGRY, EATING, READING} t_state;

/************************************************************************/
module diners(clk);
input clk;

t_state wire s0, s1, s2;

philosopher ph0(clk, s0, s1, s2, EATING);
philosopher ph1(clk, s1, s2, s0, READING);
philosopher ph2(clk, s2, s0, s1, HUNGRY);

starvation str(clk, s0);

endmodule

/************************************************************************/
module philosopher(clk, out, left, right, init);
input clk;
input left, right, init;
output out;
t_state wire left, right, init, out;
t_state reg state;
t_state wire r0_state,r1_state;


initial state = init;

assign r0_state = $ND(THINKING,HUNGRY);
assign r1_state = $ND(THINKING,EATING);
assign out = state;

always @(posedge clk) begin
    case(state)
        READING:
		if (left == THINKING) state = THINKING;

        THINKING:
            begin
		if ( right == READING ) state = READING;
		else state = r0_state; 
            end
    
        EATING:
                  state = r1_state; 

        HUNGRY:
		if ( left != EATING && right != HUNGRY && right != EATING) 
                state = EATING; 
        endcase
end
endmodule

/************************************************************************/
module starvation( clk, starv );
	input	clk;
	input	starv;
	t_state wire starv;
	reg	state;

initial state = 0;

always @(posedge clk) begin
    case(state)	
	0: if ( starv == HUNGRY ) state = 1;

	1: if ( starv == THINKING ) state = 0;

    endcase
end
endmodule	
