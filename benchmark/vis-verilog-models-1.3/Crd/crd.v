// The crossroads
// Originally written by R. P. Kurshan
// Translated by Rajeev K. Ranjan

typedef enum {no_cars, car_waiting, cars_passing} traffic_status;
typedef enum {stop, go, slow} traffic_signal;
typedef enum {go_slow, go_A, go_B} police_signal;
typedef enum {STOPPED_init, STOPPED, GO_init, GO}	car_status;
typedef enum {go_A_init, go_A_state, go_B_init, go_B_state} police_state;


module environment(clk, status_A, test);
input clk;
output status_A;
output test;

traffic_signal wire signal_A, signal_B;
traffic_status wire status_A, status_B;
police_signal wire signal;
wire test;

POLICEMAN police (clk, status_A, status_B, signal);

assign signal_A = (signal == go_A) ? go : (signal == go_slow) ? slow : stop;
assign signal_B = (signal == go_B) ? go : (signal == go_slow) ? slow : stop;


ROAD road_A (clk, signal_A, status_A);
ROAD road_B (clk, signal_B, status_B);
assign test = ((status_A == cars_passing) && (status_B == cars_passing)) ? 0 :1;

collision col(clk, status_A, status_B);
starvation starv(clk,status_A);

endmodule


module POLICEMAN(clk, status_A, status_B, signal);
input clk;
input status_A, status_B;
output signal;
traffic_status wire status_A, status_B;
police_signal wire signal;
police_state reg state;
wire r_state, ri_state;

assign  ri_state = $ND(0,1);
	initial
		begin
		case(ri_state)
		0:state = go_A_init;
		1:state = go_B_init;
		endcase
	end

	assign signal = ((state == go_A_init) || (state == go_B_init)) ? go_slow : 
					(state == go_A_state) ? go_A : go_B;
	assign r_state = $ND(0,1);
	always @(posedge clk) begin
		case(state)
			go_A_init: begin
					case(r_state)
					0:state = go_A_init;
					1:state = go_A_state;
					endcase
				   end 
			go_B_init: begin
					case(r_state)
					0:state = go_B_init;
					1:state = go_B_state;
					endcase
				   end 
		//	go_A_state:
		//		   if (status_B == car_waiting)
		//			state = go_B_init;
		//	go_B_state:
		//		   if (status_A == car_waiting)
		//			state = go_A_init;

           default:
				begin
 				if ((signal == go_A) && (status_B == car_waiting))
					state = go_B_init;
				else
				if ((signal == go_B) && (status_A == car_waiting))
                                        state = go_A_init;
				end
		endcase
	end
endmodule
			

module ROAD(clk, signal, status);
input clk;
input signal;
output status;
traffic_signal wire signal;
traffic_status  wire status;
car_status reg state;
wire r_state;

	initial state = STOPPED_init;
	
	assign status = (state == STOPPED_init) ? no_cars:
			 (state == STOPPED) ? car_waiting :
			 (state == GO_init) ? cars_passing:
			 (state == GO) ? no_cars: no_cars;
	assign r_state = $ND(0,1);

	always @(posedge clk) begin
		case(state)
			STOPPED_init:
				begin
					case(r_state)
					0:state = STOPPED_init;
					1:state = STOPPED;
					endcase
				end

			STOPPED:
				begin
					if (signal == go)
					state = GO_init;
				end
			GO_init:
				begin
					if (signal == stop)
					   state = STOPPED_init;
					else
					   begin
						case(r_state)
						0:state = GO_init;
						1:state = GO;
						endcase
					   end
				end
			GO:
				state = STOPPED_init;
			default: ;
		endcase
	end
endmodule

typedef enum {GOOD, BAD} status;

module collision(clk, status_A, status_B);
input clk, status_A, status_B;
traffic_status wire status_A, status_B;
status reg state;

       initial state = GOOD;
       always @(posedge clk) begin
	      case(state)
       		GOOD: 
		      if ((status_A == cars_passing) && (status_B == cars_passing))
		             state = BAD;
	       endcase
	end
endmodule

		       
typedef enum {OK, NOT_OK} prop1_status;

module starvation(clk, stat);
input clk, stat;
traffic_status wire stat;
prop1_status reg state;

initial state = OK;
always @(posedge clk) begin
       case(state)
       		OK:
		    begin 
		    if (stat == car_waiting)
		       state = NOT_OK;
		    end
		NOT_OK:
		    begin
		    if (stat == cars_passing)
		       state = OK;
		    end
		default:;

	endcase
end
endmodule
		       
