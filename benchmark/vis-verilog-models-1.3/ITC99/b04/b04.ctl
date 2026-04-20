#PASS:
AG EX TRUE;
AF AG stato=sC;
# RMAX >= RMIN
AG(RMAX[7]=1 -> RMIN[7]=1);
AG(RMAX[7]==RMIN[7] -> (RMAX[6]=0 -> RMIN[6]=0));
AG(RMAX[7:6]==RMIN[7:6] -> (RMAX[5]=0 -> RMIN[5]=0));
AG(RMAX[7:5]==RMIN[7:5] -> (RMAX[4]=0 -> RMIN[4]=0));
AG(RMAX[7:4]==RMIN[7:4] -> (RMAX[3]=0 -> RMIN[3]=0));
AG(RMAX[7:3]==RMIN[7:3] -> (RMAX[2]=0 -> RMIN[2]=0));
AG(RMAX[7:2]==RMIN[7:2] -> (RMAX[1]=0 -> RMIN[1]=0));
AG(RMAX[7:1]==RMIN[7:1] -> (RMAX[0]=0 -> RMIN[0]=0));

#FAIL:
AG EF stato={sA,sB};
