#PASS:
AG EX TRUE;
AG !ROM_OR[3:0]=0;
AG EF STATO=start_st;
AG AF (O[3]==OUT_R[3] + STATO=the_end);

#FAIL:
AG(STATO=loop_st -> AF !OUT_R[3:0]=0);
