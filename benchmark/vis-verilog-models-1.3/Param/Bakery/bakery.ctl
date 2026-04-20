#FAIL: Absence of starvation.  Requires fairness constraints.
AG(pc<*0*>=L1 -> AF(pc<*0*>=L9));
#AG(pc<*1*>=L1 -> AF(pc<*1*>=L9));
#PASS: Processes do not stay in the critical region forever.
# Requires fairness constraints.
#AG(pc<*0*>=L9 -> AF(pc<*0*>=L10));
#AG(pc<*1*>=L9 -> AF(pc<*1*>=L10));
#FAIL: Mutual exclusion.
#AG(!(pc<*0*>=L9 * pc<*1*>=L9));
