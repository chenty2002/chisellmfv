/* This model has for each component a failure mode that allows the
component to nondeterministically fail, from which it can not 
recover. Each model has a failure mode that is realistic. */

/************************************************************************/
	typedef enum{conduct,open} WR;
	typedef enum{discharged, charged} CH;
	typedef enum{pulling,neutral} RL;
	typedef enum{lit,unlit} LP;
	typedef enum{B,middle,C} CT;
	typedef enum{present1,present2, absent} TR;
	typedef enum{on,off} TS;
	typedef enum{good, faulty1, faulty2} FT;
	typedef enum{go,stop} IN;
	typedef enum{stop1, stop2, go1, go2, go3, go4} ST;
	typedef enum{t1, t2, t3, t4, t5, t6, t7, t8,t9,t10} TRS;

module  dchek(clk);
        input clk;

        WR wire track1, track2, track3;
	CH wire charger;
	RL wire relay;
	LP wire lamp;
	CT wire contact;
	TR wire train;
	TS wire ts_power;
	IN wire interpretation;

        track_mod   trk1(clk,track1);
        track_mod   trk2(clk,track2);
        track_mod   trk3(clk,track3);
	charger_mod chg(clk,ts_power,lamp,charger);
        relay_mod   rly(clk,charger,relay);
        lamp_mod    lmp(clk,contact,track3,ts_power,charger,lamp);
	contact_mod cnt(clk,relay,contact);
	train_mod   trn(clk,train);
	track_system_mod tsm(clk,track1,track2,train,contact,ts_power);
	interpretation_mod int(clk,lamp,interpretation);

	property_mod prop(clk, train, interpretation);

endmodule       /* dychek */

/************************************************************************/
module  track_mod(clk, track);
        input clk;
	output track;
	WR wire track;
	FT reg state;
	FT wire r_state;
	initial state = good;
assign r_state = $ND(good,faulty1);
assign track = (state == good)?conduct:open;

always @(posedge clk) begin
	if (state == faulty1) begin
		state = faulty1;
//		track = open;
		end
	if (state == good) begin
		state = r_state;
//		track = conduct;
		end
	end
endmodule /* track_mod */

/************************************************************************/
module charger_mod (clk,ts_power,lamp, out);
	input clk,ts_power,lamp;
	output out;
	TS wire ts_power;
	LP wire lamp;
	CH wire out;
	CH reg charger;
	FT reg state;
	FT wire r_state;
	initial state = good;
	initial charger = discharged;
assign r_state = $ND(good,faulty1,faulty2);
assign out = state == faulty1?discharged:
	     state == faulty2?charged:
		charger;

always @(posedge clk) begin
	if (state == faulty1) begin
		state = faulty1;
//		out = discharged;
		end
	if (state == faulty2) begin
		state = faulty2;
//		out = charged;
		end
	if (state == good) begin
		state = r_state;
//		out = charger;
		if (ts_power == on) charger = charged;
		else if (lamp == lit) charger = discharged;
		else charger = charger;
		end
	end
endmodule /* charger_mod */

/************************************************************************/
module relay_mod (clk, charger, relay);
	input clk, charger;
	output relay;
	CH wire charger;
	RL wire relay;
	FT reg state;
	FT wire r_state;
	initial state = good;
assign r_state = $ND(good,faulty1,faulty2);
assign relay = state == faulty1?neutral:
		state == faulty2?pulling:
		state == good && charger == charged?pulling:neutral;

always @(posedge clk) begin
	if (state == faulty1) begin
		state = faulty1;
//		relay = neutral;
		end
	if (state == faulty2) begin
		state = faulty2;
//		relay = pulling;
		end
	if (state == good) begin
	state = r_state;
//	if (charger == charged) relay = pulling;
//	else relay = neutral;
	end
end
endmodule /* relay_mod */

/************************************************************************/
module contact_mod(clk,relay,contact);
	input clk,relay;
	output contact;
	RL wire relay;
	CT wire contact;
	FT reg state;
	FT wire r_state;
	initial state = good;
assign r_state = $ND(good,faulty1,faulty2);
assign contact = state == faulty1?B:
		 state == faulty2?C:
		 state == good && relay == pulling?C:B;

always @(posedge clk) begin
	if (state == faulty1) begin
		state = faulty1;
//		contact = B;
		end
	if (state == faulty2) begin
		state = faulty2;
//		contact = C;
		end
	if (state == good) begin
	state = r_state;
//	if (relay == pulling) contact = C;
//	else contact = B;
	end
end
endmodule /* contact_mod */

/************************************************************************/
module train_mod (clk, out);
	input clk;
	output out;
	TR wire out;
	TRS reg train;
	TRS wire r1_train;
	TRS wire r2_train;
	TRS wire r3_train;
	TRS wire r4_train;
	TRS wire r5_train;
	TRS wire r6_train;
	TRS wire r7_train;
	TRS wire r8_train;
	TRS wire r9_train;
	TRS wire r10_train;
	initial train = t1;
assign r1_train = $ND(t1,t2);
assign r2_train = $ND(t2,t3);
assign r3_train = $ND(t3,t4);
assign r4_train = $ND(t4,t5);
assign r5_train = $ND(t5,t6);
assign r6_train = $ND(t6,t7);
assign r7_train = $ND(t7,t8);
assign r8_train = $ND(t8,t9);
assign r9_train = $ND(t9,t10);
assign r10_train = $ND(t10,t1);
assign out = (train == t1)? absent : present1;

always @(posedge clk) begin
	case (train)
	t1: train = r1_train;
	t2: train = r2_train;
	t3: train = r3_train;
	t4: train = r4_train;
	t5: train = r5_train;
	t6: train = r6_train;
	t7: train = r7_train;
	t8: train = r8_train;
	t9: train = r9_train;
	t10: train = r10_train;
 	endcase
end
endmodule /* train_mod */

/************************************************************************/
module lamp_mod (clk, contact, wire3, ts_power, charger, lamp);
	input clk, contact, wire3, ts_power, charger;
	output lamp;
	CT wire contact;
	WR wire wire3;
	CH wire charger;
	TS wire ts_power;
	LP wire lamp;
	FT reg state;
	FT wire r_state;
	initial state = good;
assign r_state = $ND(good,faulty1,faulty2);
assign lamp = state == faulty1? unlit:
	      state == faulty2? lit:
	      state == good && ((contact == C)&&(wire3 == conduct)&&
                ((ts_power == on) | (charger == charged)))?lit:unlit;

always @(posedge clk) begin
	if (state == faulty1) begin
		state = faulty1;
//		lamp = unlit;
		end
	if (state == faulty2) begin
		state = faulty2;
//		lamp = lit;
		end
	if (state == good) begin
	state = r_state;
//	if ((contact == C)&&(wire3 == conduct)&&
//		((ts_power == on) | (charger == charged)))
//		lamp = lit;
//	else lamp = unlit;
	end
end
endmodule /* lamp_mod */

/************************************************************************/
module track_system_mod (clk, wire1, wire2, train, contact, ts_power);
	input clk, wire1, wire2, contact, train;
	output ts_power;
	TR wire train;
	WR wire wire1, wire2;
	CT wire contact;
	TS wire ts_power;
	FT reg state;
	FT wire r_state;
	initial state = good;
assign r_state = $ND(good,faulty1,faulty2);
assign ts_power = state == faulty1?on:
	      	  state == faulty2?off:
		  state == good && ((wire1 == conduct) && (wire2 == conduct) &&
                ((train != absent) | (contact == B)))?on:off;

always @(posedge clk) begin
	if (state == faulty1) begin
		state = faulty1;
//		ts_power = on;
		end
	if (state == faulty2) begin
		state = faulty2;
//		ts_power = off;
		end
	if (state == good) begin
	state = r_state;
//	if ((wire1 == conduct) && (wire2 == conduct) && 
//		((train != absent) | (contact == B)))
//		ts_power = on;
//	else ts_power = off;
	end
end
endmodule /* track_system_mod */

/************************************************************************/
module interpretation_mod (clk, lamp, out);
	input clk, lamp;
	output out;
	LP wire lamp;
	IN wire out;
	ST reg state;
	initial state = stop1;

assign out = state == go3 | state == go4?go:stop;

always @(posedge clk) begin
	case(state)
		stop1: begin
		if (lamp == lit) state = stop2;
		else state = stop1;
		end

		stop2: begin
		if (lamp == unlit) state = go1;
		else state = stop1;
		end

		go1: begin
		if (lamp == lit) state = go2;
		else state = stop1;
		end

		go2: begin
		if (lamp == unlit) state = go3;
		else state = stop1;
		end

		go3: begin
		if (lamp == lit) state = go4;
		else state = stop1;
		end

		go4: begin
		if (lamp == unlit) state = go3;
		else state = stop1;
		end
	endcase
//	if ((state == go3) | (state == go4)) out = go;
/*	if (state == go3 | go4) out = go; */
//	else out = stop;
end
endmodule /* interpretation_mod */

	typedef enum{good,bad} STT;

/************************************************************************/
module property_mod (clk, train, interpretation);
	input clk,train,interpretation;
	TR wire train;
	IN wire interpretation;
	STT reg state;
	initial state = good;

always @(posedge clk) begin
	if (((train == present2)&&(interpretation == go)) | (state == bad))
		 state = bad;
	else state = good;
end
endmodule /* property_mod */

/* we want to put a cycle set around state = good, so that if ever
	state gets to bad it is a non-safe failure. */

/************************************************************************/
