#PASS:
AG EX TRUE;
AF AG !stato=INIT;
AG(d_in[0]=1 -> AF stato=LOAD_OLD);
AG(stato=LOAD_OLD * d_in[8:1]==old[7:0] * d_in[0]=1 -> AX d_in[8:1]=0);
AG(stato=EXECUTE -> AG !stato=RECEIVE);

#FAIL:
AG EF stato={INIT,RECEIVE};
AG(d_in[0]=1 -> AX stato=LOAD_OLD);
AG(stato=LOAD_OLD -> (d_in[1]=1 -> d_out[0]=1));
