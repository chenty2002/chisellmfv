#PASS: Cell 27 is addressable.
EF(posn[4:0]=27);
#PASS: All cells are writable.
EF(M<*27*>=1);
EF(M<*26*>=1);
EF(M<*25*>=1);
EF(M<*24*>=1);
EF(M<*23*>=1);
EF(M<*22*>=1);
EF(M<*21*>=1);
EF(M<*20*>=1);
EF(M<*19*>=1);
EF(M<*18*>=1);
EF(M<*17*>=1);
EF(M<*16*>=1);
EF(M<*15*>=1);
EF(M<*14*>=1);
EF(M<*13*>=1);
EF(M<*12*>=1);
EF(M<*11*>=1);
EF(M<*10*>=1);
EF(M<*9*>=1);
EF(M<*8*>=1);
EF(M<*7*>=1);
EF(M<*6*>=1);
EF(M<*5*>=1);
EF(M<*4*>=1);
EF(M<*3*>=1);
EF(M<*2*>=1);
EF(M<*1*>=1);
EF(M<*0*>=1);
#PASS: The contents of a cell cannot change unless it is addressed.
AG((M<*0*>=1) -> !E(!posn[4:0]=0 U (!posn[4:0]=0 * M<*0*>=0)));
#FAIL: Incorrect version of previous formula.
AG((M<*0*>=1) -> !E(!posn[4:0]=0 U M<*0*>=0));
#FAIL: There is no Cell 28.
EF(posn[4:0]=28);
