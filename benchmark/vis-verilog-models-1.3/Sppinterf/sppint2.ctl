#PASS:
# If
# 1. the control FSM is idle
# 2. m[0]=0x39
# 3. ad=0
# 4. there is a read request
# 5. there is no write or address request completing
# 6. the correct sequence of write and read command is issued in the next
#    two clock cycles
# then, either
# 1. the least significant nibble of m[0] (9) appears on the output, or
# 2. there is a reset that causes m[0] to be cleared
AG(writeb=1 * readb=0 * ad[1:0]=0 * mem<*0*>[7:0]=57 * wrint=0 * aleint=0 *
  fsm1.fsmstate=reposo -> AX(readb=1 -> AX(readb=0 ->
  AF(dout[3:0]=0 + dout[3:0]=9))));

#PASS: A couple of trivial invariant on the control FSM.  They can be easily
# verified by inspection of the case statement.
AG((wrint=0 + rdint=0) * (wrint=0 + aleint=0) * (rdint=0 + aleint=0));
AG(nibble=1 -> rdint=1);

#PASS: Unless wrint is asserted, the memory contents will not change when
# the clock ticks.
AG(wrint=0 * mem<*0*>[7:0]=0 -> AX(mem<*0*>[7:0]=0));

#PASS: Sanity check.  We can write a given value (57 = 0x39) to mem[0].
EF(mem<*0*>[7:0]=57);

#PASS: if we are reading the least significant nibble, and we follow the
# protocol, we shall read the most significant nibble later
# unless we have a reset (dout=0).
AG(rdint=1 * nibble=0 * readb=0 * writeb=1 ->
   AX(readb=1 -> AF(dout[3:0]=0 + nibble=1)));

#FAIL: if there is a read request for address 0 while in the idle state of
# the FSM, and mem[0] contains 57, then eventually writeb=0, or dout will be 0
# (in case of reset) or it will be 9 (the least significant nibble of 57).
AG(writeb=1 * readb=0 * ad[1:0]=0 * mem<*0*>[7:0]=57 * fsm1.fsmstate=reposo ->
   AF(writeb=0 + dout[3:0]=0 + dout[3:0]=9));

#PASS: The least significant nibble of the currently addressed word will
# eventually appear on the data output if rdint=1 and nibble=0.
AG(rdint=1 * nibble=0 * ad[1:0]=0 * mem<*0*>[7:0]=57 ->
   AF(dout[3:0]=0 + dout[3:0]=9));

#FAIL: The most significant nibble of the currently addressed word will
# eventually appear on the data output if rdint=1 and nibble=0.
AG(rdint=1 * nibble=0 * ad[1:0]=0 * mem<*0*>[7:0]=57 ->
   AF(dout[3:0]=0 + dout[3:0]=3));

#PASS: The most significant nibble of the currently addressed word will
# eventually appear on the data output if rdint=1 and nibble=0.
AG(rdint=1 * nibble=1 * ad[1:0]=0 * mem<*0*>[7:0]=57 ->
   AF(dout[3:0]=0 + dout[3:0]=3));
