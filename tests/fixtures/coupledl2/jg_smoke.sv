module JGSmoke(
  input logic clock,
  input logic reset,
  input logic unconstrained
);
  SMOKE_PROVEN: assert property (@(posedge clock) disable iff (reset)
    (unconstrained || !unconstrained));
  SMOKE_CEX: assert property (@(posedge clock) disable iff (reset)
    unconstrained);
endmodule
