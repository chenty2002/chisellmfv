#PASS: mutual exclusion
AG(!(st0=EATING * st1=EATING));
AG(!(st1=EATING * st2=EATING));
AG(!(st2=EATING * st3=EATING));
AG(!(st3=EATING * st0=EATING));

#PASS: no starvation (requires fairness condition)
AG(st0=HUNGRY -> AF(st0=EATING));
AG(st1=HUNGRY -> AF(st1=EATING));
AG(st2=HUNGRY -> AF(st2=EATING));
AG(st3=HUNGRY -> AF(st3=EATING));
