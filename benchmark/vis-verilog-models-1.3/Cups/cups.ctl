#FAIL: The counterexample to this property gives the shortest winning
#      strategy.
AG done=0;

#FAIL: The successor sought exists only for one of the two initial states.
EX(Large[3:0]=4 * Medium[3:0]=8);

#PASS: Moves are reversible.  Hence, if the first property fails, this one
#      must pass.
AG EF done=1;

#PASS: Cups never overflow.
!EF(Large[3:0]={13,14,15});
!EF(Medium[3]=1 * !Medium[2:0]=0);
!EF(Small[3]=1 + Small[2:0]={6,7});

#FAIL:
AF done=1;
