#PASS: the two minmaxes are equivalent.
AG equal=1;

#PASS: the max, min, and last values are the same.
AG mm.max[7:0] == mmr.max[7:0];
AG mm.min[7:0] == mmr.min[7:0];
AG mm.last[7:0] == mmr.last[7:0];
