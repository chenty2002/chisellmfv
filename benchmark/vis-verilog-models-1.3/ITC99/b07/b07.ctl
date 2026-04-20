#PASS:
AG EX TRUE;
AF AG !stato=S_RESET;
AG EF stato=S_START;
AG(!(PUNTI_RETTA[7:0]=0 + stato=S_START) ->
   !E(!stato=S_START U PUNTI_RETTA[7:0]=0));
AG !x[7:0]=148;

#FAIL:
AG AF stato=S_START;
AG(stato=S_START -> AF x[7:0]=2);
AG !x[7:0]=255;
