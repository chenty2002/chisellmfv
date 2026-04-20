#PASS:
AG EX TRUE;
AG AF stato=ANALISI_REQ;
AG AF stato=ASSEGNA;
AG GRANT_O[3:0]={b0000,b1000,b0100,b0010,b0001};
AG(ru1=0 -> AX(ru1=1 -> AF(GRANT_O[3]=1)));
AG(ru2=0 -> AX(ru2=1 * ru1=0 -> AF(GRANT_O[2]=1)));
AG(ru3=0 -> AX(ru3=1 * ru2=0 * ru1=0 -> AF(GRANT_O[1]=1)));
AG(ru4=0 -> AX(ru4=1 * ru3=0 * ru2=0 * ru1=0 -> AF(GRANT_O[0]=1)));

#FAIL:
AG EF stato=INIT;
AG AF(ru1=1 -> GRANT_O[3]=1);
