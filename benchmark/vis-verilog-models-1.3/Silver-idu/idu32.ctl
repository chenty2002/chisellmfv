#There is always a path to go to reset state
AG (EF Reset=1);

#It is possible to have OpCode = Insn when Step is 1
EF(Step = 1 -> OpCode[5:0]== Insn[5:0]);

#There is always a path where Step is asserted
AG (EF Step=1); 

#Whenever Work is true and Opcode is true to 2 then Decode is also 2
AG(Work=1 * OpCode[5:3]=2 -> EF(Decode[2:0]=2));

AG(Work=1 * OpCode[4:3]=1 -> EF(Decode[1:0]=1));

#AG(Work=1 * OpCode[5:0]=26 -> EF(Decode[4:0]=26));
#It is possible to have work not true and Decode[0] not true
EF(Work=0 * Decode[0]=0);

AG(Decode[0]=1 * Decode[1:0]=1 -> EF(Decode[0] == OpCode[2]));

AG(Work=1-> EF Decode[5:0]=0);

AG(Work=1 * OpCode[5:0]=10 + OpCode[5:0]=11 + OpCode[5:0]=12 + OpCode[5:0]=13 -> EF(Decode[1:0]=1));

AG(Work=1 -> A(Work=1 U (EF Decode[1:0]=1)));

EF(OpCode[5:0]=34 -> A(OpCode[5:0]=34 U Decode[4]=1));

#Too slow it works, but commented to move things ahead
AG(Work=1 * OpCode[5:0]=8 + OpCode[5:0]=9 + OpCode[5:0]=29 + OpCode[5:0]=43 -> EF(Decode[1]=1));

#AG(Work=1 -> A(Work=1 U (EF Decode[1]=1)));

EF(OpCode[5:0]= 34 -> A(OpCode[5:0]=34 U Decode[1]=1));

AG(Work=0 -> AG(Decode[5:3] = 0));
#RegA[5] = RegA_int[5]  and RegA_int= Insn[25:21]
AG((Decode[4]=1 * Decode[0]=0) + (Decode[5]=1 * Decode[0]=0))-> AG(RegA[5]==Insn[25]);

EF(Decode[3] =1 -> AG (RegDest[4:0] == Insn[4:0]));

A(Decode[3]=1 * Insn[4:0]=31 U RegDest[5]=0);

A(FctCode[6:0]==Insn[11:5] U (Decode[4]=1 * Decode[1]=0));

AG(Decode[4]=1 * Decode[1]=1 -> AF (FctCode[6:0] == Insn[11:5]));

AG(Decode[3]=1 * Insn[12]=1 -> AG(Const[21]=1 * Const[20:15] == Insn[20:15]));

AG(Decode[4]=1 * Decode[0]=1 -> AG(Operand1[31:26]==RegAValue[31:26] * Operand2[31:26]==PC[31:26]));
