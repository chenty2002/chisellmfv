// Elevators are numbered 1,...,`elev.
// Floors are numbered 0,...,`floor-1.
// Number of bits required to represent a floor number.

typedef enum {UP,DOWN} dir;
typedef enum {STOPPED,MOVING} mov;
typedef enum {OPEN,OPENING,CLOSED,CLOSING} dr;
typedef enum {ON,OFF} onoff;

//*****************************************************************************
// Connect the elevators to the controller and choose a random initial
// floor for each elevator car.
//*****************************************************************************
module main(clk, random_up, random_down, random, r_stop
	    ,random_push1, init11
	    ,random_push2, init22
	    );
    input	      clk;
    input [0:2]    random_up;
    input [0:2]    random_down;
    input [1:2]   random;
    input [1:2]   r_stop;
    input [0:2] random_push1;
    input [0:1] init11;
    input [0:2] random_push2;
    input [0:1] init22;
    wire [1:2]    stop_next;
    wire [1:2]    inc;
    wire [1:2]    dec;
    wire [1:2]    continue;

    wire [0:1] init1;
    wire [0:1] init2;

    // Choose initial state for each elevator.  Notice that $ND(0,1,2)
    // would give problems of nondeterministic tables.
    assign init1  = init11 >= 3 ? 2 : init11;
    assign init2  = init22 >= 3 ? 2 : init22;

    elevator e1 (clk,stop_next[1],inc[1],dec[1],continue[1],
		    random_push1,random[1],r_stop[1],init1);
    elevator e2 (clk,stop_next[2],inc[2],dec[2],continue[2],
		    random_push2,random[2],r_stop[2],init2);
    main_control main_control(clk,inc,dec,stop_next,continue,random_up,
			      random_down
			      ,init1
			      ,init2
			      );

endmodule // main


//*****************************************************************************
// Deal with floor buttons and communicate with elevator cars.
// This module receives one bit of inc and dec from each car, and controls
// each car via one bit of stop_next and continue.  It keeps track of the
// car positions, which is redundant because each car stores its own position.
//*****************************************************************************
module main_control(clk,inc,dec,stop_next,continue,random_up,random_down
		    ,init1
		    ,init2
		    );
    input clk;
    input [1:2]   inc;
    input [1:2]   dec;
    output [1:2]  stop_next;
    output [1:2]  continue;
    input [0:2]    random_up; // nondeterministic requests to go up
    input [0:2]    random_down; // nondeterministic requests to go down
    // initial position of elevator cars
    input [0:1]    init1;
    input [0:1]    init2;

    reg [0:1]      locations[1:2]; // positions of the cars
    onoff reg         up_floor_buttons[0:2];   // up b. at floors
    onoff reg         down_floor_buttons[0:2]; // down b. at floors
    wire [0:2]     buttons;	// floors currently requesting pick-up
    wire [0:3-2] top;
    wire [1:3-1] bottom;
    wire [1:2]    button_above; // there are pick-up requests above
    wire [1:2]    button_below; // there are pick-up requests below
    dir reg           direction[1:2];

    initial begin
	// Initially all cars are at the selected floors and there are no
	// pick-up requests.
	locations[1]=init1;
	locations[2]=init2;
	for (i=0; i<=2; i=i+1) begin
	    up_floor_buttons[i] = OFF;
	    down_floor_buttons[i] = OFF;
	end
	for (i=1; i<=2; i=i+1) begin
	    direction[i] = UP;
	end
    end

    // Compute if each elevator should continue in the same direction.
    assign buttons[0] = up_floor_buttons[0]==ON || down_floor_buttons[0]==ON;
    assign buttons[1] = up_floor_buttons[1]==ON || down_floor_buttons[1]==ON;
    assign buttons[2] = up_floor_buttons[2]==ON || down_floor_buttons[2]==ON;

    assign bottom[1] = buttons[0];
    assign bottom[2] = bottom[1] || buttons[1];
    assign top[3-2] = buttons[3-1];
    assign top[0] = top[1] || buttons[1];
    // Schedule the next pickup for each elevator car.  A car stops at the
    // next floor if there is a request pending for the current direction.
    assign button_below[1] =
	      ((locations[1]==2) && bottom[2]) ||
	      (locations[1]==1 && bottom[1]);
    assign button_above[1] =
	   ((locations[1]==0) && top[0]) ||
	   ((locations[1]==3-2) && top[3-2]);
    assign continue[1] = button_above[1] && direction[1]==UP ||
	button_below[1] && direction[1]==DOWN;
    assign stop_next[1] = ((locations[1] != 3-1)&&(direction[1]==UP))?
	((up_floor_buttons[locations[1]+1]==ON)?1:0):
	(((locations[1] != 0)&&(direction[1]==DOWN))?
	 ((down_floor_buttons[locations[1]-1]==ON)?1:0):0);

    assign button_below[2] =
	      ((locations[2]==2) && bottom[2]) ||
	      (locations[2]==1 && bottom[1]);
    assign button_above[2] =
	   ((locations[2]==0) && top[0]) ||
	   ((locations[2]==3-2) && top[3-2]);
    assign continue[2] = button_above[2] && direction[2]==UP ||
	button_below[2] && direction[2]==DOWN;
    assign stop_next[2] = ((locations[2] != 3-1)&&(direction[2]==UP))?
	((up_floor_buttons[locations[2]+1]==ON)?1:0):
	(((locations[2] != 0)&&(direction[2]==DOWN))?
	 ((down_floor_buttons[locations[2]-1]==ON)?1:0):0);


    always @ (posedge clk) begin
	// Randomly push floor buttons.
	for (i=0; i<=3-1; i=i+1) begin
	    if (random_up[i]) up_floor_buttons[i]=ON;
	    if (random_down[i]) down_floor_buttons[i]=ON;
	end

	// Turn off scheduled floor buttons.
	// It is important to turn these off after the random pushes, since we
	// want the scheduled buttons to be OFF even though they may have been 
	// randomly pushed.
	for (i=1; i<=2; i=i+1) begin
	    if ((locations[i]!=3-1)&&(direction[i]==UP)) begin
		if (up_floor_buttons[locations[i]+1]==ON) begin
		    up_floor_buttons[locations[i]+1] = OFF;
		end
	    end
	    if ((locations[i]!=0)&&(direction[i]==DOWN)) begin
		if (down_floor_buttons[locations[i]-1]==ON) begin
		    down_floor_buttons[locations[i]-1] = OFF;
		end
	    end
	end
    end

    // Keep track of locations and directions.
    always @ (posedge clk) begin
	for (i=1; i<=2; i=i+1) begin
	    if (locations[i]==3-1) direction[i] = DOWN;
	    if (locations[i]==0) direction[i] = UP;
	    if (inc[i]) begin
		locations[i] = locations[i]+1;
		direction[i] = UP;
	    end
	    if (dec[i]) begin
		locations[i] = locations[i]-1;
		direction[i] = DOWN;
	    end
	end
    end

endmodule // main_control


//*****************************************************************************
//
//*****************************************************************************
module elevator(clk,stop_next,inc,dec,continue,random_push,random,r_stop,init);
    input  clk,stop_next,continue,random_push,random,r_stop,init;
    output inc,dec;

    onoff reg buttons[0:2];
    wire [0:2] random_push;
    wire [0:1] init;
    reg [0:1]  location;
    dir reg direction;
    mov reg movement;
    dr reg door;
    reg	 open_next;
    wire button_above, button_below;
    wire [0:3-2] top;
    wire [1:3-1] bottom;
    wire button_above, button_below;

    initial begin
	open_next = 0;
    	location = init;
    	direction = UP;
    	door = OPEN;
    	movement = STOPPED;
	for (i=0; i<=2; i=i+1) begin
	    buttons[i] = OFF;
	end
    end

    assign bottom[1] = buttons[0]==ON;
    assign bottom[2] = bottom[1] || buttons[1]==ON;
    assign top[3-2] = buttons[3-1]==ON;
    assign top[0] = top[1] || buttons[1]==ON;
    assign button_below =
	   (location==2 && bottom[2]) ||
	   (location==1 && bottom[1]);
    assign button_above =
	   (location==0 && top[0]) ||
	   (location==3-2 && top[3-2]);

    always @ (posedge clk) begin
	// Randomly push buttons. 
	// But when door is open turn button off for that floor. 
	for (i=0; i<=2; i=i+1) begin
	    if (i == location) buttons[i] = OFF;
	    else if (random_push[i]) buttons[i] = ON;
	end

	// Record a request to stop at the next floor.
	// It is important that this happens last since we want to
	// insure that the stop_next request is always recorded by
	// pushing the button.
	if (stop_next) begin
	    if (direction==UP) buttons[location+1] = ON;
	    else buttons[location-1] = ON;
	end
    end


    always @ (posedge clk) begin
	// Schedule the door to open at the next floor.
	if (door != CLOSED) open_next = 0;
	else if (movement==MOVING &&
		 (stop_next||(direction==UP&&buttons[location+1]==ON)||
		  (direction==DOWN&&buttons[location-1]==ON)))
	    open_next = 1;
    end


    always @ (posedge clk) begin
	// Door operation: open the door if button[location] is on.
	// Random pause between different states.
	case (door)
	  CLOSED: if (open_next&&movement==STOPPED)
	      door = OPENING;
	  OPENING: if (random) door = OPEN;
	  OPEN: if (random) door = CLOSING;
	  CLOSING: if (random) door = CLOSED;
	endcase
    end


    // Move to next floor. Increase or decrease location when arrived.
    // Signal to main control (through inc or dec) that have arrived at
    // next floor.
    wire stop_moving;
    wire start_moving;
    assign start_moving = (continue || button_above&&direction==UP) || 
	(button_below && direction == DOWN);
    assign stop_moving = r_stop&&(movement == MOVING);
    assign inc = (stop_moving)&&(direction == UP);
    assign dec = (stop_moving)&&(direction == DOWN);

    always @ (posedge clk) begin
	if (door == CLOSED) begin
	    case (movement)
	      STOPPED: if (door==CLOSED&&start_moving&&!open_next) 
		  movement = MOVING;
	      MOVING: if (stop_moving) begin
		  movement = STOPPED;
		  if (direction == UP) location = location+1;
		  if (direction == DOWN) location = location-1;
	      end
	    endcase
	end
    end

    // Determine direction of movement.
    always @ (posedge clk) begin
	case (direction) 
	  UP: if ((!button_above)&&!continue) 
	      direction = DOWN;
	  DOWN: if ((!button_below)&&!continue) 
	      direction = UP;
	endcase
	if(location==3-1) direction = DOWN;
	if(location==0) direction = UP;
    end

endmodule // elevator
