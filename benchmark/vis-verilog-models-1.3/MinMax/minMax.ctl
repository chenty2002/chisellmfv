#PASS: a specified reset state can eventually be reached from every state.
AG(EF(min[8:0]=b111111111 * last[8:0]=0 * max[8:0]=0));

#PASS: a specified reset state can be reached from all states in one cycle.
AG(EX(min[8:0]=b111111111 * last[8:0]=0 * max[8:0]=0));

#FAIL: a specified reset state must eventually be reached from every state.
# This formula fails in spite of the fairness conditions.
AG(AF(min[8:0]=b111111111 * last[8:0]=0 * max[8:0]=0));

#PASS: The non-reset states reachable in one clock cycle from the reset
# states have min == last == max.
AG(min[8:0]=b111111111 * max[8:0]=b000000000 ->
   AX(min[8:0]=b111111111 * max[8:0]=b000000000 +
      min[8:0] == last[8:0] * last[8:0] == max[8:0]));

#PASS: The non-reset states reachable in two clock cycles from the reset
# states have either min == last or last == max.
AG(min[8:0]=b111111111 * max[8:0]=b000000000 ->
   AX:2(min[8:0]=b111111111 * max[8:0]=b000000000 +
        min[8:0] == last[8:0] + last[8:0] == max[8:0]));
