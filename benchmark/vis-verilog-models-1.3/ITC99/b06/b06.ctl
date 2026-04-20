#PASS:
AG EX TRUE;
AF AG !state=s_init;
AG ENABLE_COUNT==ACKOUT;
AG !USCITE[2:1]=2;
AX AG !CC_MUX[2:1]=0;

#FAIL:
AG EF state=s_init;
