#FAIL:
AG((opcodeEx[2:0]=b000 * destEx[1:0]=b00 * bubbleEx=0) -> AF(regFile<*0*>[3:0]=b0001));

#FAIL: if the instruction in the execution stage is "load 1 in
# Register 0" and the pipeline has no bubble in that stage, eventually
# the 1 will show up in the destination register.
AG((opcodeEx[2:0]=b001 * destEx[1:0]=b00 * bubbleEx=0) -> AF(regFile<*0*>[3:0]=b0001));

#PASS: if the instruction in the execution stage is "load 1 in
# Register 0," then if eventually we get no bubble in the execution
# stage, then eventually the 1 shows up in the destination register.
# This property relaxes a bit the previous one.

AG((opcodeEx[2:0]=b001 * destEx[1:0]=b00) -> (EF(bubbleEx=0) -> EF(regFile<*0*>[3:0]=b0001)));

# PASS: it is always possible for the bubble in the execution stage to
# disappear.
AG(EF(bubbleEx=0));
