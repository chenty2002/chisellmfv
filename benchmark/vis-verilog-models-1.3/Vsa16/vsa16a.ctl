#PASS: The program counter is always aligned on a half-word boundary.
AG(PC[0]=0 * NPC[0]=0);

#PASS: Register R0 is never written.
AG(Registers<*0*>[15:0]=0);

#PASS: The state is between 0 and 4.
AG(State[2]=0 + State[1:0]=0);

#PASS: If the two source fields are the same, the ALU input register
# will hold the same  value during the EXE, MEM, and WB states.
AG(adFld1[1:0]==adFld2[1:0] -> (State[2:1]=0 + A[15:0]==B[15:0]));

#PASS: in case of branch, Cond is consistent with A in the MEM and WB states.
AG(opcode[2:0]=b010 * State[2]=1 -> 
   ((Cond=1 -> A[15:0]=0) * (A[15:0]=0 -> Cond=1)));

#PASS: a XOR a = 0
AG(opcode[2:0]=b011 * funFld[2:0]=b100 * adFld1[1:0]==adFld2[1:0] ->
   (State[2]=1 -> ALUOutput[15:0]=0));

#PASS: a OR a = a AND a = a
AG(opcode[2:0]=b011 * funFld[2:1]=b01 * adFld1[1:0]==adFld2[1:0] ->
   (State[2]=1 -> ALUOutput[15:0]==A[15:0]));

#PASS: a - a = 0
AG(opcode[2:0]=b011 * funFld[2:0]=b001 * adFld1[1:0]==adFld2[1:0] ->
   (State[2]=1 -> ALUOutput[15:0]=0));

#PASS: if the destination is R1 or R3, R2 is not affected by this instruction.
AG(State[2:0]=1 *
   (opcode[2:0]=b011 * adFld3[0]=1 + !(opcode[2:0]=b011) * adFld2[0]=1) * 
   Registers<*2*>[15:0]=21 -> AX:4(Registers<*2*>[15:0]=21));
