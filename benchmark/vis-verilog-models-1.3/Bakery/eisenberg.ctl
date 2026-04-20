#PASS: Sanity check: Processes do not stay in the critical region forever.
# Requires fairness constraints.
AG(pc<*0*>=L12 -> AF(pc<*0*>=L13));
AG(pc<*1*>=L12 -> AF(pc<*1*>=L13));
AG(pc<*2*>=L12 -> AF(pc<*2*>=L13));
#PASS: Absence of starvation.  Requires fairness constraints.
AG(pc<*0*>=L1 -> AF(pc<*0*>=L12));
AG(pc<*1*>=L1 -> AF(pc<*1*>=L12));
AG(pc<*2*>=L1 -> AF(pc<*2*>=L12));
#PASS: Mutual exclusion.
AG(!(pc<*0*>=L12 * pc<*1*>=L12));
AG(!(pc<*0*>=L12 * pc<*2*>=L12));
AG(!(pc<*1*>=L12 * pc<*2*>=L12));
