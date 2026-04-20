#PASS:
AG EX TRUE;
AG(stato=s_rsum -> !cont1[5:0]=0);
AG AF stato=s_datain;

#FAIL:
AG EF stato=s_reset;
AG AF stato=s_dataout;
