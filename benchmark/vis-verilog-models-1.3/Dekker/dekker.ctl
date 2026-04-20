#PASS: Mutual exclusion.
AG(!(pc<*0*>=L5 * pc<*1*>=L5));

#PASS: Starvation freedom.
AG(pc<*0*>=L1 -> AF(pc<*0*>=L5));
AG(pc<*1*>=L1 -> AF(pc<*1*>=L5));

#FAIL:
AG(c<*1*>=0 -> AF(pc<*1*>=L5));
