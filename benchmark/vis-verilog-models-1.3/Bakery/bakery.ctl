#FAIL: Absence of starvation.  Requires fairness constraints.
AG(pc<*0*>=L1 -> AF(pc<*0*>=L9));
AG(pc<*1*>=L1 -> AF(pc<*1*>=L9));
#PASS: Processes do not stay in the critical region forever.
# Requires fairness constraints.
AG(pc<*0*>=L9 -> AF(pc<*0*>=L10));
AG(pc<*1*>=L9 -> AF(pc<*1*>=L10));
#FAIL: Mutual exclusion.
AG(!(pc<*0*>=L9 * pc<*1*>=L9));
#FAIL: this property states that the initial state is a reset state.
# It fails because on exit from the loop L4-L8, j==2, and it does not
# change until L4 is reached again.
AG(EF(ticket<*0*>[1:0]=0 * ticket<*1*>[1:0]=0 * choosing<*0*>=0 *
      choosing<*1*>=0 * pc<*0*>=L1 * j<*0*>[1:0]=0 * pc<*1*>=L1 *
      j<*1*>[1:0]=0));
