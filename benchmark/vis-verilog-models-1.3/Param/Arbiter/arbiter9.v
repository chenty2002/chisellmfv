typedef enum {A, B, C, D, E, F, G, H, I, X} selection;
typedef enum {IDLE, READY, BUSY} controller_state;
typedef enum {NO_REQ, REQ, HAVE_TOKEN} client_state;

module main(clk, ackA, ackB, ackC, ackD, ackE, ackF, ackG, ackH, ackI);
input clk;
output ackA, ackB, ackC, ackD, ackE, ackF, ackG, ackH, ackI;

selection wire sel;
wire active;
wire pass_tokenA, pass_tokenB, pass_tokenC, pass_tokenD,
  pass_tokenE, pass_tokenF, pass_tokenG, pass_tokenH, pass_tokenI;
wire reqA, reqB, reqC, reqD, reqE, reqF, reqG, reqH, reqI;

assign active = pass_tokenA || pass_tokenB || pass_tokenC ||
  pass_tokenD || pass_tokenE || pass_tokenF || pass_tokenG ||
  pass_tokenH || pass_tokenI;

controller controllerA(clk, reqA, ackA, sel, pass_tokenA, A);
controller controllerB(clk, reqB, ackB, sel, pass_tokenB, B);
controller controllerC(clk, reqC, ackC, sel, pass_tokenC, C);
controller controllerD(clk, reqD, ackD, sel, pass_tokenD, D);
controller controllerE(clk, reqE, ackE, sel, pass_tokenE, E);
controller controllerF(clk, reqF, ackF, sel, pass_tokenF, F);
controller controllerG(clk, reqG, ackG, sel, pass_tokenG, G);
controller controllerH(clk, reqH, ackH, sel, pass_tokenH, H);
controller controllerI(clk, reqI, ackI, sel, pass_tokenI, I);
arbiter arbiter(clk, sel, active);

client clientA(clk, reqA, ackA);
client clientB(clk, reqB, ackB);
client clientC(clk, reqC, ackC);
client clientD(clk, reqD, ackD);
client clientE(clk, reqE, ackE);
client clientF(clk, reqF, ackF);
client clientG(clk, reqG, ackG);
client clientH(clk, reqH, ackH);
client clientI(clk, reqI, ackI);

endmodule

module controller(clk, req, ack, sel, pass_token, id);
input clk, req, sel, id;
output ack, pass_token;

selection wire sel, id;
reg ack, pass_token;
controller_state reg state;

initial state = IDLE;
initial ack = 0;
initial pass_token = 1;

wire is_selected;
assign is_selected = (sel == id);

always @(posedge clk) begin
  case(state)
    IDLE:
      if (is_selected)
        if (req)
          begin
          state = READY;
          pass_token = 0; /* dropping off this line causes a safety bug */
          end
        else
          pass_token = 1;
      else
        pass_token = 0;
    READY:
      begin
      state = BUSY;
      ack = 1;
      end
    BUSY:
      if (!req)
        begin
        state = IDLE;
        ack = 0;
        pass_token = 1;
        end
  endcase
end
endmodule

module arbiter(clk, sel, active);
input clk, active;
output sel;

selection wire sel;
selection reg state;

initial state = A;

assign sel = active ? state: X;

always @(posedge clk) begin
  if (active)
    case(state) 
      A:
        state = B;
      B:
        state = C;
      C:
        state = D;
      D:
        state = E;
      E:
        state = F;
      F:
        state = G;
      G:
	state = H;
      H:
	state = I;
      I:
        state = A;
    endcase
end
endmodule

module client(clk, req, ack);
input clk, ack;
output req;

reg req;
client_state reg state;

wire rand_choice;

initial req = 0;
initial state = NO_REQ;

assign rand_choice = $ND(0,1);

always @(posedge clk) begin
  case(state)
    NO_REQ:
      if (rand_choice)
        begin
        req = 1;
        state = REQ;
        end
    REQ:
      if (ack)
        state = HAVE_TOKEN;
    HAVE_TOKEN:
      if (rand_choice)
        begin
        req = 0;
        state = NO_REQ;
        end
  endcase
end
endmodule
