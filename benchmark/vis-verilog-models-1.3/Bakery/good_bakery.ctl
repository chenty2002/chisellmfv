#PASS: Absence of starvation.  Requires fairness constraints.
AG(pc<*0*>=L1 -> AF(pc<*0*>=L9));
AG(pc<*1*>=L1 -> AF(pc<*1*>=L9));
AG(pc<*2*>=L1 -> AF(pc<*2*>=L9));
#PASS: Processes do not stay in the critical and noncritical regions forever.
# If these fail, we forgot to read the fairness constraints.
AG(pc<*0*>=L9 -> AF(pc<*0*>=L1));
AG(pc<*1*>=L9 -> AF(pc<*1*>=L1));
AG(pc<*2*>=L9 -> AF(pc<*2*>=L1));
#PASS: Mutual exclusion.
AG(!(pc<*0*>=L9 * pc<*1*>=L9));
AG(!(pc<*0*>=L9 * pc<*2*>=L9));
AG(!(pc<*1*>=L9 * pc<*2*>=L9));
#FAIL:
EF(pc<*0*>=L6 * pc<*1*>=L6 * defer<*0*>[1]=0 * defer<*1*>[0]=0);
