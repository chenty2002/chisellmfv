`define elev 2
`define floor 3
`define width 2
typedef enum {UP,DOWN} dir;
typedef enum {STOPPED,MOVING} mov;
typedef enum {OPEN,OPENING,CLOSED,CLOSING} dr;
typedef enum {ON,OFF} onoff;

//***********************************************  
module main(clk);
input clk;
wire[1:`elev] stop_next;
wire[1:`elev] inc;
wire[1:`elev] dec;
wire[1:`elev] continue;
wire[0:`width-1] init1, init2, init11, init22;

    wire [0:`width-1] e1location;
    wire [0:`width-1] e2location;
    dir wire e1direction, e2direction;
    dr  wire e1door, e2door;
/*    onoff wire mc_up_floor_buttons;*/
    wire 	      p, q, e1dOPENING, e2dOPENING, e1dOPEN, e2dOPEN;
/*    assign 	      p = (mc_up_floor_buttons[1]==ON);*/
    
    assign 	      q=((e1location[1]==0)&&(e1location[0]==1)&&(e1door==OPEN)&&(e1direction==UP))||((e2location[1]==0)&&(e2location[0]==1)&&(e2door==OPEN)&&(e2direction==UP));
    assign 	      e1dOPENING = e1door==OPENING;
    assign 	      e2dOPENING = e2door==OPENING;
    assign 	      e1dOPEN = e1door==OPEN;
    assign 	      e2dOPEN = e2door==OPEN;
    
Buechi  Buechi(clk,q,e1dOPENING,e2dOPENING,e2dOPEN,e1dOPEN,p,fair0,fair1,fair2,fair3, scc);

assign init11=$ND(0,1,2,3);
assign init22=$ND(0,1,2,3);
assign init1 = init11==3?2:init11;
assign init2 = init22==3?2:init22;
    
elevator e1(clk,stop_next[1],inc[1],dec[1],continue[1],init1,e1location,e1direction,e1door);
elevator e2(clk,stop_next[2],inc[2],dec[2],continue[2],init2,e2location,e2direction,e2door);
main_control main_control(clk,inc,dec,stop_next,continue,init1,init2,p);
endmodule
//***********************************************  

typedef enum {n3,n4,n11,n13,n15,n16,n18,n19,n20,n21,n24,n26,n31,n32,n33,n38,n47,Trap} states;

module Buechi(clock,q,e1dOPENING,e2dOPENING,e2dOPEN,e1dOPEN,p,fair0,fair1,fair2,fair3, scc);
  input clock,q,e1dOPENING,e2dOPENING,e2dOPEN,e1dOPEN,p;
  output fair0,fair1,fair2,fair3,scc;
  states reg state;
  states wire ND_n21_n3_n31_n33;
  states wire ND_n3_n31;
  states wire ND_n15_n20;
  states wire ND_n13_n16_n19_n26_n3_n31_n38_n4;
  states wire ND_n11_n16_n19_n26_n3_n32_n33_n47;
  states wire ND_n13_n16_n3_n31;
  states wire ND_n3_n33;
  states wire ND_n16_n3;
  states wire ND_n11_n13_n16_n18_n19_n21_n24_n26_n3_n31_n32_n33_n38_n4_n47;
  states wire ND_n16_n19_n26_n3;
  states wire ND_n11_n19_n21_n24_n3_n31_n33_n4;
  states wire ND_n16_n3_n32_n33;
  states wire ND_n19_n3_n31_n4;
  states wire ND_n11_n19_n3_n33;
  states wire ND_n19_n3;
  states wire ND_n13_n16_n18_n21_n3_n31_n32_n33;
  assign ND_n21_n3_n31_n33 = $ND(n21,n3,n31,n33);
  assign ND_n3_n31 = $ND(n3,n31);
  assign ND_n15_n20 = $ND(n15,n20);
  assign ND_n13_n16_n19_n26_n3_n31_n38_n4 = $ND(n13,n16,n19,n26,n3,n31,n38,n4);
  assign ND_n11_n16_n19_n26_n3_n32_n33_n47 = $ND(n11,n16,n19,n26,n3,n32,n33,n47);
  assign ND_n13_n16_n3_n31 = $ND(n13,n16,n3,n31);
  assign ND_n3_n33 = $ND(n3,n33);
  assign ND_n16_n3 = $ND(n16,n3);
  assign ND_n11_n13_n16_n18_n19_n21_n24_n26_n3_n31_n32_n33_n38_n4_n47 = $ND(n11,n13,n16,n18,n19,n21,n24,n26,n3,n31,n32,n33,n38,n4,n47);
  assign ND_n16_n19_n26_n3 = $ND(n16,n19,n26,n3);
  assign ND_n11_n19_n21_n24_n3_n31_n33_n4 = $ND(n11,n19,n21,n24,n3,n31,n33,n4);
  assign ND_n16_n3_n32_n33 = $ND(n16,n3,n32,n33);
  assign ND_n19_n3_n31_n4 = $ND(n19,n3,n31,n4);
  assign ND_n11_n19_n3_n33 = $ND(n11,n19,n3,n33);
  assign ND_n19_n3 = $ND(n19,n3);
  assign ND_n13_n16_n18_n21_n3_n31_n32_n33 = $ND(n13,n16,n18,n21,n3,n31,n32,n33);
  assign fair0 = (state == n31) || (state == n4) || (state == n38) || (state == n13) || (state == n18) || (state == n21) || (state == n24);
  assign fair1 = (state == n4) || (state == n38) || (state == n11) || (state == n19) || (state == n47) || (state == n24) || (state == n26);
  assign fair2 = (state == n32) || (state == n33) || (state == n11) || (state == n18) || (state == n21) || (state == n47) || (state == n24);
  assign fair3 = (state == n32) || (state == n38) || (state == n13) || (state == n16) || (state == n18) || (state == n47) || (state == n26);

    assign scc = (state!=n20)&&(state!=n15);
    
  initial state = n15;
  always @ (posedge clock) begin
    case (state)
      n20:
	case (q)
	1'b0: state = n3;
	1'b1: state = Trap;
	endcase
      Trap:
	state = Trap;
      n15:
	case ({p,q})
	2'b0?: state = n15;
	2'b10: state = ND_n15_n20;
	2'b11: state = n15;
	endcase
      n3,n4,n11,n13,n16,n18,n19,n21,n24,n26,n31,n32,n33,n38,n47:
	case ({e1dOPEN,e1dOPENING,e2dOPEN,e2dOPENING,q})
	5'b00000: state = ND_n19_n3_n31_n4;
	5'b????1: state = Trap;
	5'b00010: state = ND_n3_n31;
	5'b00100: state = ND_n11_n19_n21_n24_n3_n31_n33_n4;
	5'b00110: state = ND_n21_n3_n31_n33;
	5'b01000: state = ND_n19_n3;
	5'b01010: state = n3;
	5'b01100: state = ND_n11_n19_n3_n33;
	5'b01110: state = ND_n3_n33;
	5'b10000: state = ND_n13_n16_n19_n26_n3_n31_n38_n4;
	5'b10010: state = ND_n13_n16_n3_n31;
	5'b10100: state = ND_n11_n13_n16_n18_n19_n21_n24_n26_n3_n31_n32_n33_n38_n4_n47;
	5'b10110: state = ND_n13_n16_n18_n21_n3_n31_n32_n33;
	5'b11000: state = ND_n16_n19_n26_n3;
	5'b11010: state = ND_n16_n3;
	5'b11100: state = ND_n11_n16_n19_n26_n3_n32_n33_n47;
	5'b11110: state = ND_n16_n3_n32_n33;
	endcase
    endcase
  end
endmodule




//************************************************
module main_control(clk,inc,dec,stop_next,continue,init1,init2,p);
input clk,inc,dec,init1,init2;
output stop_next,continue, p;
wire[1:`elev] inc;
wire[1:`elev] continue;
wire[1:`elev] dec;
wire[1:`elev] stop_next;
reg [0:`width-1] locations[1:`elev];
onoff reg up_floor_buttons[0:`floor-1];
onoff reg down_floor_buttons[0:`floor-1];
wire[0:`floor-1] random_up;
wire[0:`width-1] init1,init2;
wire[0:`floor-1] random_down;
wire[0:`floor-1] buttons;
wire[1:`elev] button_above,button_below;
dir reg direction[1:`elev];

    wire      p;
    assign    p = (up_floor_buttons[1]==ON);
 

initial begin
	 locations[1]=init1;
	 locations[2]=init2;
	 up_floor_buttons[0]=OFF;
	 up_floor_buttons[1]=OFF;
	 up_floor_buttons[2]=OFF;
	 down_floor_buttons[0]=OFF;
	 down_floor_buttons[1]=OFF;
	 down_floor_buttons[2]=OFF;
	 direction[1]=UP;
	 direction[2]=UP;
end

//compute if elevator should continue in same direction. We skip the next floor assuming
// that the stop_next computation will take care of this.
assign buttons[0] = up_floor_buttons[0]==ON || down_floor_buttons[0]==ON;
assign buttons[1] = up_floor_buttons[1]==ON || down_floor_buttons[1]==ON;
assign buttons[2] = up_floor_buttons[2]==ON || down_floor_buttons[2]==ON;
assign button_below[1] = 
	((locations[1]==2)&&(buttons[0]||buttons[1]))
        || (locations[1]==1 && buttons[0]);
assign button_above[1] = ((locations[1]==0)&&(buttons[2]||buttons[1]))
        || ((locations[1]==1)&&(buttons[2]));
assign button_below[2] = 
        ((locations[2]==2)&&(buttons[0]||buttons[1]))
        || (locations[2]==1 && buttons[0]);
assign button_above[2] = ((locations[2]==0)&&(buttons[2]||buttons[1]))
        || ((locations[2]==1)&&(buttons[2]));
assign continue[1] = button_above[1] && direction[1]==UP || button_below[1] && direction[1]==DOWN;
assign continue[2] = button_above[2] && direction[2]==UP || button_below[2] && direction[2]==DOWN;

//schedule the next pickup
	assign stop_next[1]=((locations[1] != `floor-1)&&(direction[1]==UP))?
		((up_floor_buttons[locations[1]+1]==ON)?1:0):
                (((locations[1] != 0)&&(direction[1]==DOWN))?
                ((down_floor_buttons[locations[1]-1]==ON)?1:0):0);
        assign stop_next[2]=((locations[2] != `floor-1)&&(direction[2]==UP))?
                ((up_floor_buttons[locations[2]+1]==ON)?1:0):
                (((locations[2] != 0)&&(direction[2]==DOWN))?
                ((down_floor_buttons[locations[2]-1]==ON)?1:0):0);
	assign random_up[0] = $ND(0,1);
	assign random_down[0] = $ND(0,1);
	assign random_up[1] = $ND(0,1);
	assign random_down[1] = $ND(0,1);
	assign random_up[2] = $ND(0,1);
	assign random_down[2] = $ND(0,1);


always@(posedge clk) begin
// randomly push floor buttons
for (i=0;i<=`floor-1;i=i+1)begin
	if (random_up[i]) up_floor_buttons[i]=ON;
	if (random_down[i]) down_floor_buttons[i]=ON;
end

//turn off scheduled floor buttons.
// it is important to turn these off after the random pushes, since we
// want the scheduled buttons to be OFF even though they may have been 
// randomly pushed.
for (i=1;i<=`elev;i=i+1) begin
	if ((locations[i] != `floor-1)&& (direction[i] == UP)) begin
	if (up_floor_buttons[locations[i]+1]==ON) begin
		up_floor_buttons[locations[i]+1] = OFF;
		end
	end
	if ((locations[i] != 0)&& (direction[i] == DOWN)) begin
	if (down_floor_buttons[locations[i]-1]==ON) begin
		down_floor_buttons[locations[i]-1] = OFF;
		end
	end
end
end

//keep track of locations and directions
always@(posedge clk) begin
for (i=1;i<=`elev;i=i+1) begin
	if (locations[i]==`floor-1) direction[i] = DOWN;
	if (locations[i]==0) direction[i]=UP;
	if(inc[i]) begin
		locations[i]=locations[i]+1;
		direction[i]=UP;
		end
	if(dec[i]) begin
		locations[i]=locations[i]-1;
		direction[i]=DOWN;
		end
end
end

endmodule
//***********************************************  

//***********************************************  
module elevator(clk,stop_next,inc,dec,continue,init , location,direction,door);
input clk,stop_next,continue,init;
output inc,dec;
output location, direction, door;//by chao
onoff reg buttons[0:`floor-1];
wire [0:`width-1] init;
reg[0:`width-1] location;
dir reg direction;
mov reg movement;
dr reg door;
reg open_next;
wire button_above, button_below;

//initial begin
initial	open_next = 0;
initial	location = init;
initial	direction = UP;
initial	door = OPEN;
initial	movement = STOPPED;
initial	buttons[0]=OFF;
initial	buttons[1]=OFF;
initial	buttons[2]=OFF;
//end

wire[0:`floor-1] random_push;
wire button_above,button_below;
assign random_push = $ND(0,1,2,3,4,5,6,7);
assign button_below = 
		((location==2)&&(buttons[1]==ON||buttons[0]==ON)) ||
		((location==1)&&buttons[0]==ON);
assign button_above = ((location==0)&&(buttons[2]==ON||
			buttons[1]==ON)) ||
			((location==1)&&(buttons[2]==ON));

//******************************
always@(posedge clk) begin
// randomly push buttons. 
// But when door is open turn button off for that floor. 
for (i=0;i<=`floor-1;i=i+1) begin
	if (i == location) buttons[i]=OFF;
	else if (random_push[i]) buttons[i]=ON;
end

// record a request to stop at the next floor
// it is important that this happens last since we want to
// insure that the stop_next request is always recorded by
// pushing the button.
if(stop_next) begin
	if (direction==UP) buttons[location+1]=ON;
	else buttons[location-1]=ON;
	end
end
//*******************************

//*******************************
always@(posedge clk) begin
//schedule the door to open at the next floor
if(door != CLOSED) open_next=0;
else if (movement==MOVING&&(stop_next||(direction == UP&&buttons[location+1]==ON)||
				(direction == DOWN&&buttons[location-1]==ON)))
		open_next=1;
end
//*******************************

wire random;
assign random = $ND(0,1);

//*******************************
always@(posedge clk) begin
//Door operation: open the door if button[location] is on.
//Random pause between different states.
	case (door)
		CLOSED: if (open_next&&movement==STOPPED)
			door=OPENING;
		OPENING: if (random) door = OPEN;
		OPEN: if (random) door = CLOSING;
		CLOSING: if (random) door = CLOSED;
		endcase
end
//*******************************

// Move to next floor. Increase or decrease location when arrived.
// Signal to main control (through inc or dec) that have arrived at next floor.
wire stop_moving;
wire start_moving;
wire r_stop;
assign start_moving = (continue || button_above&&direction==UP) || 
			(button_below && direction == DOWN);
assign r_stop = $ND(0,1);
assign stop_moving = r_stop&&(movement == MOVING);
assign inc = (stop_moving)&&(direction == UP);
assign dec = (stop_moving)&&(direction == DOWN);
//*******************************
always@(posedge clk) begin
if (door == CLOSED) begin
	case (movement)
		STOPPED: if (door==CLOSED&&start_moving&&!open_next) 
			movement=MOVING;
		MOVING: if (stop_moving) begin
			movement=STOPPED;
			if (direction == UP) location = location+1;
			if (direction == DOWN) location = location-1;
			end
		endcase
	end
end
//*******************************
		
// Determine direction of movement

//*******************************
always@(posedge clk) begin
	case (direction) 
		UP: if((!button_above)&&!continue) 
			direction = DOWN;
		DOWN: if((!button_below)&&!continue) 
			direction = UP;
		endcase
	if(location==`floor-1) direction=DOWN;
	if(location==0) direction=UP;
	end
//*******************************

endmodule
//***********************************************  
