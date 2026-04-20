\define IDENTITY	(b<*0*>[2:0]=0 * b<*1*>[2:0]=1 * b<*2*>[2:0]=2 *
			 b<*3*>[2:0]=3 * b<*4*>[2:0]=4 * b<*5*>[2:0]=5 *
			 b<*6*>[2:0]=6 * b<*7*>[2:0]=7)

\define REVERSE		(b<*0*>[2:0]=0 * b<*1*>[2:0]=7 * b<*2*>[2:0]=6 *
			 b<*3*>[2:0]=5 * b<*4*>[2:0]=4 * b<*5*>[2:0]=3 *
			 b<*6*>[2:0]=2 * b<*7*>[2:0]=1)

#PASS: The home position is reachable from any initial permutation.
permutation=1 <-> EF(\IDENTITY);

#FAIL: We want to find the shortest solution to the instance described
# on pp. 758-759 of "Winning Ways."
\IDENTITY -> AG(!\REVERSE);

#FAIL: The counterexample gives us a state that requires 63 moves to
# get to the identity.
permutation=1 -> EX:62(\IDENTITY);


