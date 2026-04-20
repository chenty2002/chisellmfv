\define allocd_0	alloc=1 * nack=0 * alloc_addr[3:0]=0
\define freed_0		free=1 * free_addr[3:0]=0

#PASS: If entry 0 is allocated, it is not allocated again until it is freed.
AG(\allocd_0 -> !EX(E(!\freed_0 U (!\freed_0 * \allocd_0))));
