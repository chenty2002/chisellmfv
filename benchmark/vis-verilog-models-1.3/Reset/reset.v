typedef enum {s0, s1} State;

module reset(clock,sel);
    input clock;
    input [1:0] sel;

    reg [2:0] st;

    initial st = 0;

    always @ (posedge clock) begin
	st[0] = sel[0];
	st[1] = ~st[1];
	st[2] = sel[1] | st[2];
    end

endmodule // reset
