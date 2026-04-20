#PASS: the contents of the fifth entry of the stack cannot change in the
# next clock cycle unless the stack pointer is either 4 or 5.
AG(!(sp[2:0]=b100) * !(sp[2:0]=b101) ->
   (reg_file<*5*>[11:0]=b101010101010 ->
    AX(reg_file<*5*>[11:0]=b101010101010)));

#PASS: the 0-th entry of the stack is never written and the stack pointer
# is never 6 or 7.
AG(reg_file<*0*>[11:0]=0 * !sp[2:1]=b11);

#PASS: It is always possible to write 101010101010 in the fifth entry of
# the stack.
AG(EF(reg_file<*5*>[11:0]=b101010101010));

#PASS: It is always possible to write 101010101010 in the five usable
# entries of the stack and in RE, 010101010101 in uPC, and 3 in the stack
# pointer.
AG(EF(reg_file<*0*>[11:0]=0 * reg_file<*1*>[11:0]=b101010101010 *
      reg_file<*2*>[11:0]=b101010101010 * reg_file<*3*>[11:0]=b101010101010 *
      reg_file<*4*>[11:0]=b101010101010 * reg_file<*5*>[11:0]=b101010101010 *
      RE[11:0]=b101010101010 * uPC[11:0]=b010101010101 * sp[2:0]=b011));

#PASS: The antecedent is never satisfied for the reachable states.
AG(reg_file<*0*>[11:0]=b000000000010 -> AX(reg_file<*0*>[11:0]=b000000000001));

#PASS: The antecedent is never satisfied for the reachable states.
AG(sp[2:0]=6 -> AX(sp[2:0]=7));
