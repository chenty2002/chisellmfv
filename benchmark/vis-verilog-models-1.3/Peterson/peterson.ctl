#PASS: Mutual exclusion.
AG(!(pc<*0*>=L4 * pc<*1*>=L4));

#PASS: Starvation freedom.
AG(interested<*0*>=1 -> AF(pc<*0*>=L4));
AG(interested<*1*>=1 -> AF(pc<*1*>=L4));
