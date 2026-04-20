\define FULL		nitems[2:0]=4
\define EMPTY		nitems[2:0]=0
\define LEGAL		(nitems[2]=0 + nitems[1:0]=0)

#PASS: The heap property is never violated.
AG error=0;

#PASS: the number of items is always between 0 and 4.
AG \LEGAL;

#PASS: No PUSH commands are accpted if the heap is full.
AG((ready=1 * \FULL) -> !E(!state=POP1 U state=PUSH1));
#PASS: No POP commands are accpted if the heap is empty.
AG((ready=1 * \EMPTY) -> !E(!state=PUSH1 U state=POP1));

#PASS: No PUSH commands are accpted if the heap is full.
AG((ready=1 * \FULL) -> !EX state=PUSH1);
#PASS: No POP commands are accpted if the heap is empty.
AG((ready=1 * \EMPTY) -> !EX state=POP1);

#PASS: Yet another variation on the theme.
AG((ready=1 * \FULL) -> AX(state=POP1 + \FULL));
AG((ready=1 * \EMPTY) -> AX(state=PUSH1 + \EMPTY));

#PASS: Sanity checks on the allowed transitions.
AG(state={IDLE,PUSH2} + !EX state=PUSH1);
AG(state=PUSH1 + !EX state=PUSH2);
AG(state={IDLE,POP3} + !EX state=POP1);
AG(state=POP1 + !EX state=POP2);
AG(state=POP2 + !EX state=POP3);
AG(state={IDLE,TEST2} + !EX state=TEST1);
AG(state=TEST1 + !EX state=TEST2);
