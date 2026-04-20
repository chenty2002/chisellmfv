# These properties are written keeping in mind that all states are initial.
# For an explanation of the atomic propositions (permutation, oddInversions,
# and parity) see the .v file.
\define IDENTITY	(b<*0*>[2:0]=0 * b<*1*>[2:0]=1 * b<*2*>[2:0]=2 *
			 b<*3*>[2:0]=3 * b<*4*>[2:0]=4 * b<*5*>[2:0]=5 *
			 b<*6*>[2:0]=6 * b<*7*>[2:0]=7)

#PASS:
(permutation=1 * oddInversions=0) <-> EF(\IDENTITY);
#PASS:
permutation=1 -> parity=0;
#PASS:
permutation=1 <-> AG(permutation=1);
#PASS:
(permutation=1 * oddInversions=0) -> AG(oddInversions=0);
#PASS:
parity=0 <-> AG(parity=0);
