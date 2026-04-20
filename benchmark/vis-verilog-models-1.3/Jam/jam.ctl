#PASS:
AG(!(empty[2:0]=7));
AG(EF(done=1));
#FAIL: We use this failing invariant to produce the strategy.
AG(done=0);
