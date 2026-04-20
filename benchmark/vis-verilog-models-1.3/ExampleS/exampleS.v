typedef enum { R, G, U, D } m_state;
typedef enum { I, B } r_state;

module resource(clk);
input clk;
wire req, grant, use, release;

/* INSTANTIATION 0:  assert req implies (req until grant) then (use until release)
*/
	a0 a0(clk, req, grant, use, release);


// RESOURCE REQUESTOR

assign req = (m_st == R);
assign use = (m_st == U);
assign release = (m_st == D);
m_state reg m_st;
m_state wire r_m_st;
initial m_st = R;

assign r_m_st = $ND(U,D);

always @(posedge clk) begin
    case (m_st)
	R: if (grant) m_st = G;
	G: m_st = U;
	U: begin
		m_st = r_m_st;
	end
	D: m_st = R;
    endcase;
end

// RESOURCE GRANTER

r_state reg r_st;
r_state wire r_r_st;
initial r_st = I;

assign grant = (r_st == B);
assign r_r_st = $ND(B,I);

always @(posedge clk) begin
    case (r_st)
	I: begin
	    if (req) r_st = r_r_st; 
	    else r_st = I;
	end
	B: if (release) r_st = I;
    endcase;
end
endmodule


/* TESLA ver 0.2: Sequence Detection FSMS: */

typedef enum { S, D, X, T } state;

/* DEFINITION 0: assert req implies (req until grant) then (use until release)
*/
module a0(clk, req, grant, use, release);
input clk, req, grant, use, release;
wire clk, trigger, failure, e0, r0, s0, f0, e3, r3, s3, f3, req, grant, use, release;
	assign e0 = 1;
	assign r0 = s3 || f0;
	assign trigger = s0;
	a0_seq0 a0_seq0(clk, e0, r0, s0, f0, req);
	assign e3 = trigger;
	assign r3 = 0;
	a0_seq3 a0_seq3(clk, e3, r3, s3, f3, req, grant, use, release);
endmodule

module a0_seq0(clk, e, r, s, f, req);
input clk, e, r, req;
output s, f;
//AP
	state reg st;
	initial st = S;
	assign s = (((st == S) && e && (req)));
	assign f = (((st == S) && e && ! (req)) || (st == T));
	always @(posedge clk) begin
		case (st)
			S: begin
				if (e && (req)) st = S;
				else if (e && !(req)) st = T;
			end
			default: if (r) st = S;
		endcase;
	end
endmodule

module a0_seq3(clk, e, r, s, f, req, grant, use, release);
input clk, e, r, req, grant, use, release;
output s, f;
// THEN
wire e1, r1, s1, f1, e2, r2, s2, f2, req, grant, use, release;
	a0_seq1 a0_seq1(clk, e1, r1, s1, f1, req, grant);
	a0_seq2 a0_seq2(clk, e2, r2, s2, f2, use, release);
	assign r1 = r;
	assign r2 = r;
	then then(clk, s1, r, e2);
	assign s = s2;
	assign f = f1 || f2;
	assign e1 = e;
endmodule

module then(clk, e, r, s);
input clk, e, r;
output s;
//THEN_DEF
	state reg st;
	initial st = S;
	assign s = (st == D);
	always @(posedge clk) begin
		case (st)
			S: if (e) st = X;
			X: st = D;
			default: if (r) st = S;
		endcase;
	end
endmodule

module a0_seq1(clk, e, r, s, f, req, grant);
input clk, e, r, req, grant;
output s, f;
//UNTIL
	state reg st;
	initial st = S;
	assign s = (((st == S) && e && (grant)) || ((st == X) && (! r) && (grant)));
	assign f = (((st == S) && e && ! (req) && !(grant)) || ((st == X) && !(req) && !(grant)) || (st == T));
	always @(posedge clk) begin
		case (st)
			S: begin
				if (e && (grant)) st = S;
				else if (e && (req)) st = X;
				else if(e && !(req) && !(grant)) st = T;
			end
			X: begin
				if (r || (grant)) st = S;
				else if (!(req) && !(grant)) st = T;
			end
			default: if (r) st = S;
		endcase;
	end
endmodule

module a0_seq2(clk, e, r, s, f, use, release);
input clk, e, r, use, release;
output s, f;
//UNTIL
	state reg st;
	initial st = S;
	assign s = (((st == S) && e && (release)) || ((st == X) && (! r) && (release)));
	assign f = (((st == S) && e && ! (use) && !(release)) || ((st == X) && !(use) && !(release)) || (st == T));
	always @(posedge clk) begin
		case (st)
			S: begin
				if (e && (release)) st = S;
				else if (e && (use)) st = X;
				else if(e && !(use) && !(release)) st = T;
			end
			X: begin
				if (r || (release)) st = S;
				else if (!(use) && !(release)) st = T;
			end
			default: if (r) st = S;
		endcase;
	end
endmodule

